package com.maxieds.chameleonminiusb;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.IBinder;
import android.support.annotation.IntRange;
import android.support.annotation.RequiresPermission;

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
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.UNEXPECTED_INCOMING_RXDATA;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.UPLOAD;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.WAITING_FOR_RESPONSE;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.WAITING_FOR_XMODEM_DOWNLOAD;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.WAITING_FOR_XMODEM_UPLOAD;
import static com.maxieds.chameleonminiusb.Utils.BYTE;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;
import com.maxieds.chameleonminiusb.ChameleonCommands.ChameleonCommandResult;
import com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet;

import org.apache.commons.lang3.NotImplementedException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;

public class ChameleonDeviceConfig implements ChameleonUSBInterface {

    private static final String TAG = ChameleonDeviceConfig.class.getSimpleName();

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

    ChameleonBoardType_t getChameleonBoardRevision(int usbVendorID, int usbProductID) {
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

    @RequiresPermission("com.android.example.USB_PERMISSION")
    public static UsbSerialDevice configureSerialPort(UsbSerialInterface.UsbReadCallback readerCallback) {

        if(serialPort != null) {
            shutdownSerialConnection();
            serialPort = null;
        }

        UsbManager usbManager = (UsbManager) mainApplicationActivity.getSystemService(Context.USB_SERVICE);
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
                    LibraryLogging.broadcastIntent("CHAMELEON_REVG_ATTACHED");
                    break;
                }
                else if(deviceVID == CMUSB_REVE_VENDORID && devicePID == CMUSB_REVE_PRODUCTID) {
                    connection = usbManager.openDevice(device);
                    LibraryLogging.broadcastIntent("CHAMELEON_REVE_ATTACHED");
                    break;
                }
            }
        }
        if(device == null || connection == null) {
            LibraryLogging.e(TAG, "USB STATUS: Connection to device unavailable.");
            serialPort = null;
            return serialPort;
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
        chameleonDeviceConfigured = true;
        return serialPort;

    }

    @RequiresPermission("com.android.example.USB_PERMISSION")
    public static boolean handleNewUSBDeviceAttached() {
        if(serialPort != null) {
            shutdownSerialConnection();
        }
        serialPort = configureSerialPort(usbReaderCallback);
        return true;
    }

    public static boolean shutdownSerialConnection() {
        if(serialPort != null) {
            serialPort.close();
            serialPort = null;
        }
        chameleonDeviceConfigured = false;
        serialUSBState = IDLE;
        XModem.EOT = true;
        XModem.transmissionErrorOccurred = true;
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
            if(LibraryLogging.localLoggingLevel.compareTo(LibraryLogging.LocalLoggingLevel.LOG_ADB_VERBOSE) == 0 ||
                    LibraryLogging.localLoggingLevel.compareTo(LibraryLogging.LocalLoggingLevel.LOG_ADB_DEBUG) == 0) {
                String summaryByteStr = String.format(Locale.ENGLISH, "[%s]\n[%s]",
                        Utils.trimString(Utils.byteArrayToString(liveRxData), 48),
                        Utils.trimString(Utils.bytes2Ascii(liveRxData), 48));
                LibraryLogging.v(TAG, summaryByteStr);
            }

            if(serialUSBState.compareTo(PAUSED) == 0) {
                return;
            }
            else if(serialUSBState.compareTo(DOWNLOAD) == 0) {
                throw new NotImplementedException("Need XModem.performXModemSerialDownload");
                //XModem.performXModemSerialDownload(liveRxData);
                //return;
            }
            else if(serialUSBState.compareTo(UPLOAD) == 0) {
                XModem.performXModemSerialUpload(liveRxData);
                return;
            }
            else if(serialUSBState.compareTo(WAITING_FOR_XMODEM_UPLOAD) == 0) {
                String strLogData = new String(liveRxData);
                if(strLogData.length() >= 11 && strLogData.substring(0, 11).equals("110:WAITING")) {
                    serialUSBState = UPLOAD;
                    return;
                }
            }
            else if(serialUSBState.compareTo(WAITING_FOR_XMODEM_DOWNLOAD) == 0) {
                String strLogData = new String(liveRxData);
                if(strLogData.length() >= 11 && strLogData.substring(0, 11).equals("110:WAITING")) {
                    serialUSBState = DOWNLOAD;
                    return;
                }
            }
            else if((serialUSBState.compareTo(WAITING_FOR_RESPONSE) == 0 || serialUSBState.compareTo(EXPECTING_BINARY_DATA) == 0) &&
                    ChameleonCommandResult.isCommandResponse(liveRxData)) {
                ChameleonCommandResult parsedCmdResult = new ChameleonCommandResult();
                parsedCmdResult.processCommandResponse(liveRxData);
                parsedSerialUSBCmdResponse = parsedCmdResult;
                int binaryBufSize = liveRxData.length - parsedCmdResult.cmdResponseMsg.length() - 2;
                serialUSBBinaryDataResponse = new byte[binaryBufSize];
                System.arraycopy(liveRxData, liveRxData.length - binaryBufSize, serialUSBBinaryDataResponse, 0, binaryBufSize);
                serialUSBState = IDLE;
            }
            else {
                serialUSBState = UNEXPECTED_INCOMING_RXDATA;
            }
        }
    };

    /**
     * We seek to generate an exhaustive list of properties and settings for the attached
     * Chameleon device for verbose debugging and error-checking purposes:
     */
    public static String[] getChameleonMiniUSBDeviceParams() {
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

    public static ChameleonCommandResult sendRawStringToChameleon(String cmdString) {
        if(!chameleonDeviceIsConfigured()) {
            LibraryLogging.e(TAG, "Chameleon device not configured for command \"" + cmdString + "\"");
            return null;
        }
        ChameleonCommandResult cmdResult = new ChameleonCommands.ChameleonCommandResult();
        cmdResult.issuingCmd = cmdString;
        byte[] sendBuf = cmdString.getBytes(StandardCharsets.UTF_8);
        serialUSBState = WAITING_FOR_RESPONSE;
        for(int i = 0; i < SERIAL_USB_COMMAND_TIMEOUT / 50; i++) {
            if(serialUSBState != WAITING_FOR_RESPONSE)
                break;
            try {
                Thread.sleep(50);
            } catch(InterruptedException ie) {
                break;
            }
        }
        cmdResult.processCommandResponse(serialUSBFullCommandResponse);
        return cmdResult;
    }

    public static <CmdArgType> ChameleonCommandResult sendFormattedCommandToChameleon(String cmdFormatStr, CmdArgType cmdArg) {
        String fullCmdStr = String.format(Locale.ENGLISH, cmdFormatStr, cmdArg);
        return sendRawStringToChameleon(fullCmdStr);
    }

    public static <CmdArgType> ChameleonCommandResult sendCommandToChameleon(StandardCommandSet cmd, CmdArgType cmdArg) {
        int RevEGBoardIndex = isRevisionEDevice() ? 0 : 1;
        String cmdFormatStr = ChameleonCommands.getCommandFormatString(cmd)[RevEGBoardIndex];
        String fullCmdStr = String.format(Locale.ENGLISH, cmdFormatStr, cmdArg);
        return sendRawStringToChameleon(fullCmdStr);
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

    boolean changeChameleonUID(ChameleonUIDTypeSpec_t uidOperation, String suffixBytes) {

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

    public static Context mainApplicationActivity;

    private static ServiceConnection usbServiceConn = new ServiceConnection() {
        public ChameleonMiniUSBService usbService;
        public void onServiceConnected(ComponentName className, IBinder binder) {
            LibraryLogging.d("ServiceConnection","connected");
            usbService = (ChameleonMiniUSBService) binder;
        }
        public void onServiceDisconnected(ComponentName className) {
            LibraryLogging.d("ServiceConnection","disconnected");
            usbService = null;
        }
    };

    @RequiresPermission("com.android.example.USB_PERMISSION")
    public boolean chameleonUSBInterfaceInitialize(Context mainActivity) {
        return chameleonUSBInterfaceInitialize(mainActivity, LibraryLogging.LocalLoggingLevel.LOG_ADB_ERROR);
    }

    @RequiresPermission("com.android.example.USB_PERMISSION")
    public boolean chameleonUSBInterfaceInitialize(Context mainActivity, LibraryLogging.LocalLoggingLevel localLoggingLevel) {

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
                "android.permission.BROADCAST_STICKY",
                "android.permission.FOREGROUND_SERVICE",
        };
        if (android.os.Build.VERSION.SDK_INT >= 23)
            ((Activity) mainApplicationActivity).requestPermissions(permissions, 200);

        // Start the foreground service to handle new and removed USB connections:
        Intent startChameleonUSBService = new Intent(mainApplicationActivity, ChameleonMiniUSBService.class);
        startChameleonUSBService.setAction("MONITOR_CHAMELEON_USB");
        mainApplicationActivity.startService(startChameleonUSBService);
        mainApplicationActivity.bindService(startChameleonUSBService, usbServiceConn, Context.BIND_AUTO_CREATE);

        return true;

    }

    public boolean chameleonUSBInterfaceShutdown() {
        shutdownSerialConnection();
        Intent stopChameleonUSBService = new Intent(mainApplicationActivity, ChameleonMiniUSBService.class);
        mainApplicationActivity.stopService(stopChameleonUSBService);
        mainApplicationActivity.unbindService(usbServiceConn);
        if(LibraryLogging.writeLogsToFileOnShutdown) {
            LibraryLogging.LogEntry.writeLogsToXMLFile();
            LibraryLogging.LogEntry.writeLogsToPlainTextFile();
        }
        usbReceiversRegistered = false;
        THE_CHAMELEON_DEVICE = null;
        return true;
    }

    public boolean chameleonPresent() {
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

    public String chameleonUpload(byte[] tagDataBytes) {

        // TODO

        serialUSBState = IDLE;
        ChameleonCommandResult nextUIDResult = sendCommandToChameleon(QUERY_UID, null);
        return nextUIDResult.cmdResponseData;
    }

    public String chameleonUpload(InputStream dumpDataStream) {
        XModem.uploadCardFileByXModem(dumpDataStream);
        serialUSBState = IDLE;
        ChameleonCommandResult nextUIDResult = sendCommandToChameleon(QUERY_UID, null);
        return nextUIDResult.cmdResponseData;
    }



}

