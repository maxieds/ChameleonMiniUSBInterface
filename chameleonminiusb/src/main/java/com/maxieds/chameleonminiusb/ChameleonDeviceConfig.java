package com.maxieds.chameleonminiusb;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.arch.core.BuildConfig;
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
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.GET_VERSION;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.QUERY_UID;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.SET_ACTIVE_SLOT;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.SET_CONFIG;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.SET_UID;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.IDLE;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.WAITING_FOR_RESPONSE;
import static com.maxieds.chameleonminiusb.Utils.BYTE;
import com.maxieds.chameleonminiusb.ChameleonCommands.ChameleonCommandResult;
import com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet;

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
        DOWNLOAD,
        ERROR_OCCURED,
        UNEXPECTED_INCOMING_RXDATA,
    };

    public static SerialUSBStates serialUSBState = IDLE;
    public static int SERIAL_USB_COMMAND_TIMEOUT = 3000; // in milliseconds
    private static byte[] serialUSBCommandResponse;

    @TargetApi(27)
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
                    break;
                }
                else if(deviceVID == CMUSB_REVE_VENDORID && devicePID == CMUSB_REVE_PRODUCTID) {
                    connection = usbManager.openDevice(device);
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
        // this is what's going to get called when the LIVE config spontaneously prints its log data to console:
        @Override
        public void onReceivedData(byte[] liveLogData) {
            //Log.i(TAG, "USBReaderCallback Received Data: " + Utils.bytes2Hex(liveLogData));
            //Log.i(TAG, "    => " + Utils.bytes2Ascii(liveLogData));
            if(ChameleonIO.PAUSED) {
                return;
            }
            else if(ChameleonIO.DOWNLOAD) {
                //Log.i(TAG, "USBReaderCallback / DOWNLOAD");
                ExportTools.performXModemSerialDownload(liveLogData);
                return;
            }
            else if(ChameleonIO.UPLOAD) {
                //Log.i(TAG, "USBReaderCallback / UPLOAD");
                ExportTools.performXModemSerialUpload(liveLogData);
                return;
            }
            else if(ChameleonIO.WAITING_FOR_XMODEM) {
                //Log.i(TAG, "USBReaderCallback / WAITING_FOR_XMODEM");
                String strLogData = new String(liveLogData);
                if(strLogData.length() >= 11 && strLogData.substring(0, 11).equals("110:WAITING")) {
                    ChameleonIO.WAITING_FOR_XMODEM = false;
                    return;
                }
            }
            else if(ChameleonIO.WAITING_FOR_RESPONSE && ChameleonIO.isCommandResponse(liveLogData)) {
                String[] strLogData = (new String(liveLogData)).split("[\n\r]+");
                //Log.i(TAG, strLogData);
                ChameleonIO.DEVICE_RESPONSE_CODE = strLogData[0];
                if(strLogData.length >= 2)
                    ChameleonIO.DEVICE_RESPONSE = Arrays.copyOfRange(strLogData, 1, strLogData.length);
                else
                    ChameleonIO.DEVICE_RESPONSE[0] = strLogData[0];
                if(ChameleonIO.EXPECTING_BINARY_DATA) {
                    int binaryBufSize = liveLogData.length - ChameleonIO.DEVICE_RESPONSE_CODE.length() - 2;
                    ChameleonIO.DEVICE_RESPONSE_BINARY = new byte[binaryBufSize];
                    System.arraycopy(liveLogData, liveLogData.length - binaryBufSize, ChameleonIO.DEVICE_RESPONSE_BINARY, 0, binaryBufSize);
                    ChameleonIO.EXPECTING_BINARY_DATA = false;
                }
                ChameleonIO.WAITING_FOR_RESPONSE = false;
                return;
            }
            final LogEntryUI nextLogEntry = LogEntryUI.newInstance(liveLogData, "");
            if(nextLogEntry != null) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        appendNewLog(nextLogEntry);
                    }
                });
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

    @TargetApi(27)
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
        cmdResult.processCommandResponse(serialUSBCommandResponse);
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
    public static byte[] chameleonUIDPrefixBytes = BuildConfig.chameleonUIDPrefix;
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
        else if(uidOperation == ChameleonUIDTypeSpec_t.SPECIFY_SUFFIX_BYTES &&
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

    public static Activity mainApplicationActivity;

    private static ServiceConnection usbServiceConn = new ServiceConnection() {
        public ChameleonMiniUSBService usbService;
        public void onServiceConnected(ComponentName className, IBinder binder) {
            LocalLogging.d("ServiceConnection","connected");
            usbService = (ChameleonMiniUSBService) binder;
        }
        public void onServiceDisconnected(ComponentName className) {
            LocalLogging.d("ServiceConnection","disconnected");
            usbService = null;
        }
    };

    @RequiresPermission("com.android.example.USB_PERMISSION")
    public boolean chameleonUSBInterfaceInitialize(Activity mainActivity) {
        return chameleonUSBInterfaceInitialize(mainActivity, LibraryLogging.LocalLoggingLevel.LOG_ADB_ERROR);
    }

    @TargetApi(27)
    @RequiresPermission("com.android.example.USB_PERMISSION")
    public boolean chameleonUSBInterfaceInitialize(Activity mainActivity, LibraryLogging.LocalLoggingLevel localLoggingLevel) {

        // setup configurations and constants:
        mainApplicationActivity = mainActivity;
        LibraryLogging.localLoggingLevel = localLoggingLevel;
        THE_CHAMELEON_DEVICE = this;

        USB_BAUD_RATE = mainApplicationActivity.getResources().getInteger(R.integer.SerialUSBBaudRate);
        USB_DATA_BITS = mainApplicationActivity.getResources().getInteger(R.integer.SerialUSBDataBits);
        SERIAL_USB_COMMAND_TIMEOUT = mainApplicationActivity.getResources().getInteger(R.integer.SerialUSBCommandTimeout);

        ChameleonMiniUSBService.vibrateOnUSBDeviceAttach = mainApplicationActivity.getResources().getBoolean(R.bool.VibrateOnUSBDeviceAttach);
        ChameleonMiniUSBService.vibrateDurationMs = mainApplicationActivity.getResources().getInteger(R.integer.USBDeviceAttachVibrateMs);
        LibraryLogging.SUPPORT_ADB_LOGGING = mainApplicationActivity.getResources().getBoolean(R.bool.SupportADBLogging);
        LibraryLogging.broadcastAllLogsToReceivers = mainApplicationActivity.getResources().getBoolean(R.bool.BroadcastAllLogsToReceivers);
        LibraryLogging.writeLogsToFileOnShutdown = mainApplicationActivity.getResources().getBoolean(R.bool.WriteLogsToFileOnShutdown);

        // permissions we will need to run the service (and in general):
        String[] permissions = {
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.INTERNET",
                "com.android.example.USB_PERMISSION",
                "android.permission.VIBRATE",
                "android.permission.BROADCAST_STICKY",
                "android.permission.FOREGROUND_SERVICE",
        };
        if (android.os.Build.VERSION.SDK_INT >= 23)
            mainApplicationActivity.requestPermissions(permissions, 200);

        // Start the foreground service to handle new and removed USB connections:
        Intent startChameleonUSBService = new Intent(mainApplicationActivity, ChameleonMiniUSBService.class);
        startChameleonUSBService.setAction("MONITOR_CHAMELEON_USB");
        mainApplicationActivity.startService(startChameleonUSBService);
        mainApplicationActivity.bindService(startChameleonUSBService, usbServiceConn, Context.BIND_AUTO_CREATE);

        // now setup the basic serial port so that we can accept attached USB device connections:
        if(!usbReceiversRegistered) {
            //serialPort = configureSerialPort(null, usbReaderCallback);
            BroadcastReceiver usbActionReceiver = new BroadcastReceiver() {
                @RequiresPermission("com.android.example.USB_PERMISSION")
                public void onReceive(Context context, Intent intent) {
                    if (intent.getAction() != null && (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED) || intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_DETACHED))) {
                        ChameleonMiniUSBService.localService.onHandleIntent(intent);
                    }
                }
            };
            IntentFilter usbActionFilter = new IntentFilter();
            usbActionFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            usbActionFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            ChameleonMiniUSBService.localService.registerReceiver(usbActionReceiver, usbActionFilter);
            usbReceiversRegistered = true;
        }
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

    boolean chameleonUpload(byte[] tagDataBytes, ChameleonEmulatedConfigType_t chameleonConfigType);
    boolean chameleonUpload(InputStream dumpDataStream, ChameleonEmulatedConfigType_t chameleonConfigType);



}

