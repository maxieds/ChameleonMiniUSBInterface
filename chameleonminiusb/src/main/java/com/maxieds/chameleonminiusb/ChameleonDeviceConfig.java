package com.maxieds.chameleonminiusb;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.support.annotation.IntRange;

import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.CLEAR_ACTIVE_SLOT;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.GET_ACTIVE_SLOT;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.GET_MEMORY_SIZE;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.GET_RSSI_VOLTAGE;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.GET_UID_SIZE;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.GET_VERSION;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.QUERY_CONFIG;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.QUERY_READONLY;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.QUERY_UID;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.SET_ACTIVE_SLOT;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.SET_CONFIG;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.SET_UID;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.DOWNLOAD;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.EXPECTING_BINARY_DATA;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.IDLE;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.PAUSED;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.UPLOAD;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.WAITING_FOR_RESPONSE;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.WAITING_FOR_XMODEM_DOWNLOAD;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.WAITING_FOR_XMODEM_UPLOAD;
import static com.maxieds.chameleonminiusb.Utils.BYTE;
import static com.maxieds.chameleonminiusb.XModem.BYTE_NAK;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;
import com.maxieds.chameleonminiusb.ChameleonCommands.ChameleonCommandResult;
import com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class ChameleonDeviceConfig implements ChameleonUSBInterface {

    private static final String TAG = ChameleonDeviceConfig.class.getSimpleName();

    public static final int SHORT_PAUSE = 25;
    public static final int MEDIUM_PAUSE = 1250;

    /**** This is the global Chameleon Board device configuration that should be refered to from
     **** all instances in *both* this library code *AND* in the customized day-to-day Android
     **** code being developed for the client bread company! Create an array here if you end up
     **** needing more than one device connected at a time, though I should mention that this in
     **** itself is a complicated prospect since the RevE firmware does not really have any
     **** mechanism for uniquely identifying devices (i.e., only by vendor and product IDs) on the
     **** fly. However, the RevG devices do have built-in unique device identifiers, so this
     **** could be a possibility at some point if the specs grow or change...
     ****/
    public static ChameleonDeviceConfig THE_CHAMELEON_DEVICE;

    public static boolean chameleonDeviceIsConfigured() {
        return THE_CHAMELEON_DEVICE != null && THE_CHAMELEON_DEVICE.isConfigured();
    }

    public boolean isConfigured() {
        return usbReceiversRegistered && chameleonDeviceConfigured;
    }

    /**** This is the core of the serial USB comminucation setup and configuration for
     **** interacting with the Chameleon boards. They're aren't that many parameters to configure
     **** here (except possibly the desired data transfer speed), however, this is important in that
     **** it effectively controls setup/shutdown of the serial communication. This should be used in
     **** conjunction with the running ChameleonMiniUSBActivity to automatically configure this
     **** data --AND-- shut it down correctly when the USB device is detected as detached by the
     **** running Android OS. This combination is significantly more reliable for *long-term* (re)use
     **** of a plugged in Chameleon device that will be used to ingest *MULTIPLE* Mifare dumps over
     **** time rather than just a single plug-and-play use followed by immediate disconnection:
     ****/

    /**** Constants and helper functions for determining the Chameleon Mini Revision types: ****/
    public static final int CMUSB_REVG_VENDORID = 0x16d0;
    public static final int CMUSB_REVG_PRODUCTID = 0x04b2;
    public static final int CMUSB_REVE_VENDORID = 0x03eb;
    public static final int CMUSB_REVE_PRODUCTID = 0x2044;

    public static enum ChameleonBoardType_t {
        REVE_REBOOTED,
        REVG,
        REVE_OTHER,
        REV_CUSTOM_FIRMWARE,
        REV_OLDER_FIRMWARE,
    };

    public static ChameleonBoardType_t getChameleonBoardRevision(int usbVendorID, int usbProductID) {
        if(usbVendorID == CMUSB_REVE_VENDORID && usbProductID == CMUSB_REVE_PRODUCTID) {
            return ChameleonBoardType_t.REVE_REBOOTED;
        }
        else if(usbVendorID == CMUSB_REVE_VENDORID) {
            return ChameleonBoardType_t.REVE_OTHER;
        }
        else if(usbVendorID == CMUSB_REVG_VENDORID && usbProductID == CMUSB_REVG_PRODUCTID) {
            return ChameleonBoardType_t.REVG;
        }
        else if(usbVendorID == CMUSB_REVG_VENDORID) {
            return ChameleonBoardType_t.REV_CUSTOM_FIRMWARE;
        }
        else {
            return ChameleonBoardType_t.REV_OLDER_FIRMWARE;
        }
    }

    protected static ChameleonBoardType_t localChameleonBoardRev;
    public static ChameleonBoardType_t getChameleonBoardType() { return localChameleonBoardRev; }
    public static String getChameleonBoardTypeByName() { return localChameleonBoardRev.name(); }

    public static boolean isRevisionEDevice() {
        return localChameleonBoardRev == ChameleonBoardType_t.REVE_REBOOTED || localChameleonBoardRev == ChameleonBoardType_t.REVE_OTHER;
    }

    public static boolean isRevisionGDevice() {
        return localChameleonBoardRev == ChameleonBoardType_t.REVG;
    }

    /**** Handle the setup of the serial USB communications: ****/
    public static UsbSerialDevice serialPort;
    public static UsbDevice chameleonUSBDevice;
    public static final Semaphore serialPortLock = new Semaphore(1, true);
    public static boolean usbReceiversRegistered = false;
    private static boolean chameleonDeviceConfigured = false;
    public static final int USB_DATA_BITS = 16; // 8
    public static final int USB_BAUD_RATE = 256000; // 115200

    public static enum SerialUSBStates {
        IDLE,
        PAUSED,
        WAITING_FOR_RESPONSE,
        EXPECTING_BINARY_DATA,
        UPLOAD,
        WAITING_FOR_XMODEM_UPLOAD,
        DOWNLOAD,
        WAITING_FOR_XMODEM_DOWNLOAD,
        ERROR_OCCURED,
        UNEXPECTED_INCOMING_RXDATA,
    };

    public static SerialUSBStates serialUSBState = IDLE;
    public static int SERIAL_USB_COMMAND_TIMEOUT = 3000; // in milliseconds
    public static byte[] serialUSBFullCommandResponse, serialUSBBinaryDataResponse;
    public static ChameleonCommandResult parsedSerialUSBCmdResponse;
    public static String LAST_CHAMELEON_CMD = "";

    public static UsbSerialDevice configureSerialPort(UsbSerialInterface.UsbReadCallback readerCallback) {

        if(serialPort != null) {
            shutdownSerialConnection();
            serialPort = null;
        }

        UsbManager usbManager = (UsbManager) mainApplicationActivity.getDefaultContext().getSystemService(Context.USB_SERVICE);
        UsbDevice device = null;
        UsbDeviceConnection connection = null;
        HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();
        if(usbDevices != null && !usbDevices.isEmpty()) {
            for(Map.Entry<String, UsbDevice> entry : usbDevices.entrySet()) {
                device = entry.getValue();
                if(device == null)
                    continue;
                int deviceVID = device.getVendorId();
                int devicePID = device.getProductId();
                if(deviceVID == CMUSB_REVG_VENDORID && devicePID == CMUSB_REVG_PRODUCTID) {
                    connection = usbManager.openDevice(device);
                    localChameleonBoardRev = getChameleonBoardRevision(deviceVID, devicePID);
                    break;
                }
                else if(deviceVID == CMUSB_REVE_VENDORID && devicePID == CMUSB_REVE_PRODUCTID) {
                    connection = usbManager.openDevice(device);
                    localChameleonBoardRev = getChameleonBoardRevision(deviceVID, devicePID);
                    break;
                }
            }
        }
        if(device == null || connection == null) {
            LibraryLogging.e(TAG, "USB STATUS: Connection to device unavailable.");
            serialPort = null;
            return serialPort;
        }
        else {
            PendingIntent permIntent = PendingIntent.getBroadcast(mainApplicationActivity.getDefaultContext(), 0, new Intent("com.android.example.USB_PERMISSION"), 0);
            usbManager.requestPermission(device, permIntent);
            if(!usbManager.hasPermission(device)) {
                LibraryLogging.w(TAG, "ChameleonMiniUSB library does not have permission to access the USB device!");
                serialPort = null;
                return serialPort;
            }
            chameleonUSBDevice = device;
        }
        serialPort = UsbSerialDevice.createUsbSerialDevice(device, connection);
        if(serialPort != null && serialPort.open()) {
            serialPort.setBaudRate(USB_BAUD_RATE); // 115200
            serialPort.setDataBits(USB_DATA_BITS); // slight optimization from UsbSerialInterface.DATA_BITS_8? ... yes, better
            serialPort.setStopBits(UsbSerialInterface.STOP_BITS_1);
            serialPort.setParity(UsbSerialInterface.PARITY_NONE);
            serialPort.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
            serialPort.read(readerCallback);
        }
        usbReceiversRegistered = true;
        chameleonDeviceConfigured = true;
        return serialPort;

    }

    public static boolean shutdownSerialConnection() {
        if(serialPort != null) {
            serialPort.close();
            serialPort = null;
        }
        chameleonDeviceConfigured = false;
        serialUSBState = IDLE;
        XModem.EOT = true;
        return true;

    }

    /**
     * Sets up the handling of the serial data responses received from the device
     * (command responses and spontaneous LIVE log data).
     */
    public static UsbSerialInterface.UsbReadCallback usbReaderCallback = new UsbSerialInterface.UsbReadCallback() {

        @Override
        public void onReceivedData(byte[] liveRxData) {

            // typically generate logs of the bytes in human-readable format for parsing and/or verifying in realtime:
            String hexDataStr = Utils.trimString(Utils.byteArrayToString(liveRxData), 48);
            String asciiDataStr = Utils.trimString(Utils.bytes2Ascii(liveRxData), 48);
            String summaryByteStr = String.format(Locale.ENGLISH, "[%s]\n[%s]",
                    hexDataStr.length() == 0 ? "NO-DATA" : hexDataStr,
                    asciiDataStr.length() == 0 ? "NO-DATA" : asciiDataStr);
            if(serialUSBState.compareTo(WAITING_FOR_RESPONSE) == 0 || serialUSBState.compareTo(EXPECTING_BINARY_DATA) == 0) {
                summaryByteStr = "ISSUING CMD: \"" + LAST_CHAMELEON_CMD + "\"\n" + summaryByteStr;
            }
            else {
                summaryByteStr = "SERIAL USB STATE: \"" + serialUSBState.name() + "\"\n" + summaryByteStr;
            }
            final String summaryPrintStr = summaryByteStr;
            mainApplicationActivity.getRunOnUiThreadHandler(new Runnable() {
                public void run() {
                    LibraryLogging.v(TAG, summaryPrintStr);
                }
            });

            if(liveRxData.length == 0) {
                return;
            }
            else if(serialUSBState.compareTo(PAUSED) == 0) {
                return;
            }
            else if(serialUSBState.compareTo(DOWNLOAD) == 0) {
                XModem.performXModemSerialDownload(liveRxData);
                return;
            }
            else if(serialUSBState.compareTo(UPLOAD) == 0) {
                XModem.performXModemSerialUpload(liveRxData);
                return;
            }
            else if(serialUSBState.compareTo(WAITING_FOR_XMODEM_UPLOAD) == 0) {
                String strLogData = new String(liveRxData);
                if(strLogData.length() >= 11 && strLogData.substring(0, 11).equals("110:WAITING")) {
                    LibraryLogging.d(TAG, "Now ready to upload card data -> STATE:UPLOAD.");
                    serialUSBState = UPLOAD;
                    serialPort.write(new byte[]{BYTE_NAK});
                    XModem.eotSleepHandler.postDelayed(XModem.eotSleepRunnable, 50);
                    return;
                }
            }
            else if(serialUSBState.compareTo(WAITING_FOR_XMODEM_DOWNLOAD) == 0) {
                String strLogData = new String(liveRxData);
                if(strLogData.length() >= 11 && strLogData.substring(0, 11).equals("110:WAITING")) {
                    serialUSBState = DOWNLOAD;
                    serialPort.write(new byte[]{BYTE_NAK});
                    XModem.eotSleepHandler.postDelayed(XModem.eotSleepRunnable, 50);
                    return;
                }
            }
            else if(serialUSBState.compareTo(WAITING_FOR_RESPONSE) == 0 || serialUSBState.compareTo(EXPECTING_BINARY_DATA) == 0) {
                serialUSBFullCommandResponse = liveRxData;
                ChameleonCommandResult parsedCmdResult = new ChameleonCommandResult();
                parsedCmdResult.processCommandResponse(liveRxData);
                parsedSerialUSBCmdResponse = parsedCmdResult;
                int binaryBufSize = liveRxData.length - parsedCmdResult.cmdResponseMsg.length() - 2;
                if(binaryBufSize > 0) {
                    serialUSBBinaryDataResponse = new byte[binaryBufSize];
                    System.arraycopy(liveRxData, liveRxData.length - binaryBufSize, serialUSBBinaryDataResponse, 0, binaryBufSize);
                }
                else {
                    serialUSBBinaryDataResponse = null;
                }
                serialUSBState = IDLE;
            }
            else {
                LibraryLogging.e(TAG, "UNEXPECTED_RXDATA: [" + Utils.byteArrayToString(liveRxData) + "] (Current State = " + serialUSBState.name() + ")");
            }
        }
    };

    /**
     * We seek to generate an exhaustive list of properties and settings for the attached
     * Chameleon device for verbose debugging and error-checking purposes:
     */
    public static String[] getChameleonMiniUSBDeviceParams() {
        if(chameleonUSBDevice == null) {
            LibraryLogging.w(TAG, "The chameleon UsbDevice is NULL!");
            return new String[] { "CHAMELEON USBDEVICE STRUCT IS NULL!" };
        }
        else if(!THE_CHAMELEON_DEVICE.isConfigured()) {
            LibraryLogging.i(TAG, "The chameleon UsbDevice is NULL!");
            return new String[] { "CHAMELEON DEVICE NOT CONFIGURED!" };
        }
        return new String[] {
                "USB Vendor ID: " + chameleonUSBDevice.getVendorId(),
                "USB Product ID: " + chameleonUSBDevice.getProductId(),
                "USB Manufacturer: " + chameleonUSBDevice.getManufacturerName(),
                "USB Product Name: " + chameleonUSBDevice.getProductName(),
                "USB Serial Number: " + chameleonUSBDevice.getSerialNumber(),
                "USB Version: " + chameleonUSBDevice.getVersion(),
                "SUSB Version:" + sendCommandToChameleon(GET_VERSION, null).cmdResponseData,
                "SUSB Config: " + sendCommandToChameleon(QUERY_CONFIG, null).cmdResponseData,
                "SUSB UID: " + sendCommandToChameleon(QUERY_UID, null).cmdResponseData,
                "SUSB Readonly: " + sendCommandToChameleon(QUERY_READONLY, null).cmdResponseData,
                "SUSB MemSize: " + sendCommandToChameleon(GET_MEMORY_SIZE, null).cmdResponseData,
                "SUSB UIDSize: " + sendCommandToChameleon(GET_UID_SIZE, null).cmdResponseData,
                "SUSB ActiveSlot: " + sendCommandToChameleon(GET_ACTIVE_SLOT, null).cmdResponseData,
                "SUSB RSSI: " + sendCommandToChameleon(GET_RSSI_VOLTAGE, null).cmdResponseData,
        };
    }

    /**** Handle actual communicating with the Chameleon Mini over serial USB. This includes
     **** providing an easy-to-use mechanism for translating between the RevE versus RevG
     **** variants of the common RevE command set. ****/

    public static ChameleonCommandResult sendRawStringToChameleon(String cmdString, boolean acquireSerialPortLock) {
        ChameleonCommandResult cmdResult = new ChameleonCommands.ChameleonCommandResult();
        if(acquireSerialPortLock) {
            try {
                if(!serialPortLock.tryAcquire(SERIAL_USB_COMMAND_TIMEOUT, TimeUnit.MILLISECONDS)) {
                    return cmdResult;
                }
            } catch (InterruptedException ie) {
                return cmdResult;
            }
        }
        LAST_CHAMELEON_CMD = cmdString;
        if(!chameleonDeviceIsConfigured()) {
            LibraryLogging.e(TAG, "Chameleon device not configured for command \"" + cmdString + "\"");
            return null;
        }
        cmdResult.issuingCmd = cmdString;
        cmdString += (isRevisionEDevice() ? "\r\n" : "\n\r");
        byte[] sendBuf = cmdString.getBytes(StandardCharsets.UTF_8);
        SerialUSBStates nextSerialUSBState = WAITING_FOR_RESPONSE;
        if(cmdResult.issuingCmd.length() >= 6 && cmdResult.issuingCmd.substring(0, 6).equalsIgnoreCase("upload")) {
            nextSerialUSBState = WAITING_FOR_XMODEM_UPLOAD;
        }
        else if(cmdResult.issuingCmd.length() >= 8 && cmdResult.issuingCmd.substring(0, 8).equalsIgnoreCase("download")) {
            nextSerialUSBState = WAITING_FOR_XMODEM_DOWNLOAD;
        }
        serialUSBState = nextSerialUSBState;
        serialPort.write(sendBuf);
        for(int i = 0; i < SERIAL_USB_COMMAND_TIMEOUT / 50; i++) {
            if(serialUSBState != nextSerialUSBState)
                break;
            try {
                Thread.sleep(50);
            } catch(InterruptedException ie) {
                break;
            }
        }
        if(serialUSBState == nextSerialUSBState) {
            LibraryLogging.e(TAG, "Unable to get response for command: \"" + cmdResult.issuingCmd + "\"");
            serialUSBState = IDLE;
            cmdResult.isValid = false;
            serialPortLock.release();
            return cmdResult;
        }
        cmdResult.processCommandResponse(serialUSBFullCommandResponse);
        if(acquireSerialPortLock)
            serialPortLock.release();
        return cmdResult;
    }

    public static ChameleonCommandResult sendRawStringToChameleon(String cmdString) {
        return sendRawStringToChameleon(cmdString, true);
    }

    public static <CmdArgType> ChameleonCommandResult sendCommandToChameleon(StandardCommandSet cmd, CmdArgType cmdArg, boolean acquireSerialPortLock) {
        int RevEGBoardIndex = isRevisionEDevice() ? 0 : 1;
        String cmdFormatStr = ChameleonCommands.getCommandFormatString(cmd)[RevEGBoardIndex];
        String fullCmdStr = String.format(Locale.ENGLISH, cmdFormatStr, cmdArg);
        return sendRawStringToChameleon(fullCmdStr, acquireSerialPortLock);
    }

    public static <CmdArgType> ChameleonCommandResult sendCommandToChameleon(StandardCommandSet cmd, CmdArgType cmdArg) {
        return sendCommandToChameleon(cmd, cmdArg, true);
    }

    /**** Chameleon Board UID configuration and real-time / live setting functions ****/
    public static byte[] chameleonUIDPrefixBytes = Utils.byteArrayFromString("BC5926C8");
    public static int chameleonUIDNumBytes = 8;

    public static enum ChameleonUIDTypeSpec_t {
        TRULY_RANDOM,
        PREFIXED_RANDOMIZED,
        INCREMENT_EXISTING,
        SPECIFY_SUFFIX_BYTES,
    };

    public static boolean changeChameleonUID(ChameleonUIDTypeSpec_t uidOperation, String suffixBytes) {

        if(!chameleonDeviceIsConfigured()) {
            return false;
        }
        else if(uidOperation == ChameleonUIDTypeSpec_t.SPECIFY_SUFFIX_BYTES && suffixBytes == null) {
            return false;
        }
        try {
            chameleonUIDNumBytes = Integer.parseInt(sendCommandToChameleon(GET_UID_SIZE, null).cmdResponseData);
        } catch(Exception nfe) {}
        if(uidOperation == ChameleonUIDTypeSpec_t.SPECIFY_SUFFIX_BYTES &&
                chameleonUIDNumBytes != chameleonUIDPrefixBytes.length + suffixBytes.length() / 2) {
            return false;
        }

        byte[] nextUIDBytes = new byte[chameleonUIDNumBytes];
        if(uidOperation == ChameleonUIDTypeSpec_t.INCREMENT_EXISTING) {
            String priorUIDString = sendCommandToChameleon(QUERY_UID, null).cmdResponseData;
            if(priorUIDString == null) {
                return false;
            }
            byte[] priorBytes = Utils.byteArrayFromString(priorUIDString);
            boolean searchingForNoCarry = true;
            for(int b = priorBytes.length - 1; b >= 0; b--) {
                byte lsb = priorBytes[b];
                if(lsb == BYTE(0xff))
                    continue;
                priorBytes[b] = BYTE(lsb + BYTE(0x01));
                searchingForNoCarry = false;
            }
            if(searchingForNoCarry) {
                Arrays.fill(priorBytes, BYTE(0x00));
            }
            nextUIDBytes = priorBytes;
        }
        else if(uidOperation == ChameleonUIDTypeSpec_t.TRULY_RANDOM) {
            nextUIDBytes = Utils.generateRandomBytes(chameleonUIDNumBytes);
        }
        else if(uidOperation == ChameleonUIDTypeSpec_t.PREFIXED_RANDOMIZED) {
            nextUIDBytes = Utils.generateRandomBytes(chameleonUIDPrefixBytes, chameleonUIDNumBytes - chameleonUIDPrefixBytes.length);
        }

        String uidTextString = Utils.byteArrayToString(nextUIDBytes);
        ChameleonCommandResult uidSetCmdResult = sendCommandToChameleon(SET_UID, uidTextString);
        LibraryLogging.i(TAG, "UID Reset to:\n" + uidSetCmdResult.toString());
        return true;

    }

    /**** NFC Tag Types that the Chameleon Boards can emulate (all may not be enabled by
     **** default in the standard compile of the firmware...):
     ****/

    /**
     * All possible emulated tag configurations that can be enabled in the RevE Rebooted source.
     * @ref https://github.com/iceman1001/ChameleonMini-rebooted/blob/master/Firmware/Chameleon-Mini/Configuration.h
     * @since 2018.08.08 (last access date of the RevE firmware source)
     */
    public static enum ChameleonEmulatedConfigType_t {
        NONE,
        MF_ULTRALIGHT,
        MF_ULTRALIGHT_EV1_80B,
        MF_ULTRALIGHT_EV1_164B,
        MF_CLASSIC_1K,
        MF_CLASSIC_1K_7B,
        MF_CLASSIC_4K,
        MF_CLASSIC_4K_7B,
        MF_DETECTION,
        ISO15693_GEN,
        ISO14443A_SNIFF,
        ISO14443A_READER,
        ISO15693_SNIFF,
        COUNT, // NOT an actual configuration, just here for convenience
    }

    /**** ChameleonUSBInterface implementation: ****/

    public static ChameleonLibraryInterfaceReceiver mainApplicationActivity;

    public boolean chameleonUSBInterfaceInitialize(ChameleonLibraryInterfaceReceiver mainActivity) {
        return chameleonUSBInterfaceInitialize(mainActivity, LibraryLogging.LocalLoggingLevel.LOG_ADB_ERROR);
    }

    public boolean chameleonUSBInterfaceInitialize(ChameleonLibraryInterfaceReceiver mainActivity, LibraryLogging.LocalLoggingLevel localLoggingLevel) {

        // setup configurations and constants:
        mainApplicationActivity = mainActivity;
        LibraryLogging.localLoggingLevel = localLoggingLevel;
        THE_CHAMELEON_DEVICE = this;

        // permissions we will need to run the service (and in general):
        String[] permissions = {
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.INTERNET",
                "com.android.example.USB_PERMISSION",
        };
        mainApplicationActivity.getRequestPermissionsHandler(permissions, 0);

        // setup the serial port (if possible):
        serialPort = configureSerialPort(usbReaderCallback);

        return true;

    }

    public void onNewIntent(Intent intent) {
        if(intent == null || intent.getAction() == null) {
            return;
        }
        String intentAction = intent.getAction();
        if(intentAction.equals("ACTION_USB_DEVICE_ATTACHED")) {
            shutdownSerialConnection();
            serialPort = configureSerialPort(usbReaderCallback);
        }
        else if(intentAction.equals("ACTION_USB_DEVICE_DETACHED")) {
            shutdownSerialConnection();
        }
    }

    public boolean chameleonUSBInterfaceShutdown() {
        shutdownSerialConnection();
        if(LibraryLogging.writeLogsToFileOnShutdown) {
            LibraryLogging.LogEntry.writeLogsToXMLFile();
            LibraryLogging.LogEntry.writeLogsToPlainTextFile();
        }
        usbReceiversRegistered = false;
        return true;
    }

    public boolean chameleonPresent() {
        shutdownSerialConnection();
        serialPort = configureSerialPort(usbReaderCallback);
        return serialPort != null && chameleonDeviceConfigured;
    }

    public boolean chameleonPresent(ChameleonBoardType_t expectedRevType) {
        return chameleonPresent() && expectedRevType == localChameleonBoardRev;
    }

    public boolean prepareChameleonEmulationSlot(@IntRange(from=1,to=8) int slotNumber, boolean clearSlot) {
        ChameleonCommandResult setSlotResult = sendCommandToChameleon(SET_ACTIVE_SLOT, slotNumber);
        if(clearSlot) {
            ChameleonCommandResult clearSlotResult = sendCommandToChameleon(CLEAR_ACTIVE_SLOT, null);
            return setSlotResult.isValid && clearSlotResult.isValid;
        }
        return setSlotResult.isValid;
    }

    public boolean prepareChameleonEmulationSlot(@IntRange(from=1,to=8) int slotNumber, boolean clearSlot,
                                                 ChameleonEmulatedConfigType_t chameleonConfigType) {
        boolean slotOpSuccess = prepareChameleonEmulationSlot(slotNumber, clearSlot);
        ChameleonCommandResult setConfigResult = sendCommandToChameleon(SET_CONFIG, chameleonConfigType.name());
        return slotOpSuccess && setConfigResult.isValid;
    }

    private boolean executeChameleonUpload() {
        while(!XModem.EOT) {
            try {
                Thread.sleep(2 * SHORT_PAUSE);
            } catch(InterruptedException ie) {
                break;
            }
        }
        try {
            Thread.sleep(MEDIUM_PAUSE);
        } catch(InterruptedException ie) {}
        if(XModem.uploadUseInputStream() && ChameleonDeviceConfig.THE_CHAMELEON_DEVICE.verifyChameleonUpload(XModem.getUploadInputStream())) {
            return true;
        }
        else if(!XModem.uploadUseInputStream() && ChameleonDeviceConfig.THE_CHAMELEON_DEVICE.verifyChameleonUpload(XModem.getUploadByteStream())) {
            return true;
        }
        else {
            return false;
        }
    }

    public boolean chameleonUpload(InputStream dumpDataStream) {
        XModem.uploadCardFileByXModem(dumpDataStream);
        return executeChameleonUpload();
    }

    public boolean chameleonUpload(byte[] dumpDataBytes) {
        XModem.uploadCardFileByXModem(dumpDataBytes);
        return executeChameleonUpload();
    }

    private int getChameleonUIDSize() {
        int uidSize = 4;
        try {
            uidSize = Integer.parseInt(sendCommandToChameleon(GET_UID_SIZE, null).cmdResponseData);
        } catch(NumberFormatException nfe) {
            uidSize = 4;
        }
        return uidSize;
    }

    private byte[] getCardSourceUIDBytes(InputStream dumpInputStream, int uidByteSize) {
        if(dumpInputStream == null || uidByteSize <= 0) {
            return null;
        }
        try {
            dumpInputStream.reset();
            if(dumpInputStream.available() < uidByteSize) {
                return null;
            }
            byte[] uidBytes = new byte[uidByteSize];
            dumpInputStream.read(uidBytes, 0, uidByteSize);
            return uidBytes;
        } catch(IOException ioe) {
            LibraryLogging.e(TAG, ioe.getMessage());
            ioe.printStackTrace();
            return null;
        }
    }

    private byte[] getCardSourceUIDBytes(byte[] dumpInputBytes, int uidByteSize) {
        if(dumpInputBytes == null || uidByteSize <= 0 || dumpInputBytes.length < uidByteSize) {
            return null;
        }
        byte[] uidBytes = new byte[uidByteSize];
        System.arraycopy(dumpInputBytes, 0, uidBytes, 0, uidByteSize);
        return uidBytes;
    }

    private boolean diffChameleonUIDBytes(byte[] actualUIDBytes) {
        try {
            String actualUIDStr = Utils.byteArrayToString(actualUIDBytes).replace(" ", "");
            String reportedUIDStr = sendCommandToChameleon(QUERY_UID, null).cmdResponseData;
            if(actualUIDStr.equalsIgnoreCase(reportedUIDStr)) {
                return true;
            }
        } catch(Exception ioe) {
            LibraryLogging.e(TAG, "Unable to verify card upload: " + ioe.getMessage());
            ioe.printStackTrace();
        }
        return false;
    }

    private boolean verifyChameleonUpload(InputStream dumpInputStream) {
        byte[] uidBytes = getCardSourceUIDBytes(dumpInputStream, getChameleonUIDSize());
        return diffChameleonUIDBytes(uidBytes);
    }

    private boolean verifyChameleonUpload(byte[] dumpInputBytes) {
        byte[] uidBytes = getCardSourceUIDBytes(dumpInputBytes, getChameleonUIDSize());
        return diffChameleonUIDBytes(uidBytes);
    }

    public boolean chameleonDownload(File cardOutFile) {
        XModem.downloadCardFileByXModem(cardOutFile);
        while(!XModem.EOT) {
            try {
                Thread.sleep(2 * SHORT_PAUSE);
            } catch(InterruptedException ie) {
                break;
            }
        }
        try {
            Thread.sleep(MEDIUM_PAUSE);
        } catch(InterruptedException ie) {}
        if(!XModem.transmissionError()) {
            return true;
        }
        else {
            return false;
        }
    }

    public boolean chameleonUploadEncrypted(byte[] dumpDataBytes, int keyIndex, long timeStampSaltData) {
        XModem.uploadEncryptedCardFileByXModem(dumpDataBytes, keyIndex, timeStampSaltData);
        return executeChameleonUpload();
    }

    public boolean authenticateToChangeKeyData(String authPassphrase, int numChangesAllowed) {
        String cmdArgsString = String.format(Locale.ENGLISH, "%s %d", authPassphrase, numChangesAllowed);
        int cmdSuccessCode = sendCommandToChameleon(StandardCommandSet.KEYAUTH, cmdArgsString).cmdResponseCode;
        return cmdSuccessCode == ChameleonCommands.SerialRespCode.TRUE.toInteger();
    }

    public boolean authenticateToChangeKeyData(String authPassphrase) {
        return authenticateToChangeKeyData(authPassphrase, 1);
    }

    public boolean updateKeyData(int keyIndex, String keyData) {
        String cmdArgsString = String.format(Locale.ENGLISH, "%d %s", keyIndex, keyData);
        int cmdSuccessCode = sendCommandToChameleon(StandardCommandSet.SETKEY, cmdArgsString).cmdResponseCode;
        return cmdSuccessCode == ChameleonCommands.SerialRespCode.OK.toInteger() ||
                cmdSuccessCode == ChameleonCommands.SerialRespCode.OK_WITH_TEXT.toInteger() ||
                cmdSuccessCode == ChameleonCommands.SerialRespCode.TRUE.toInteger();
    }

    public boolean updateKeyData(int keyIndex, byte[] keyData) {
        return updateKeyData(keyIndex, Utils.byteArrayToString(keyData));
    }

    public String generateKeyData(int keyIndex, String initPassphrase) {

        String cmdArgsString = String.format(Locale.ENGLISH, "%d %s", keyIndex, initPassphrase);
        ChameleonCommandResult cmdSuccessCode = sendCommandToChameleon(StandardCommandSet.GENKEY, cmdArgsString);
        if(cmdSuccessCode.cmdResponseCode == ChameleonCommands.SerialRespCode.OK_WITH_TEXT.toInteger()) {
            return cmdSuccessCode.cmdResponseData;
        }
        return null;

    }

}

