package com.maxieds.chameleonminiusb;

import android.os.Bundle;

import static com.maxieds.chameleonminiusb.Utils.FLAG_FALSE;
import static com.maxieds.chameleonminiusb.Utils.BYTE;
import static com.maxieds.chameleonminiusb.Utils.FLAG_FALSE;
import static com.maxieds.chameleonminiusb.Utils.FLAG_IS_OTHER;
import com.maxieds.chameleonminiusb.ChameleonCommands.ChameleonCommandResult;
import com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet;

import java.util.Locale;
import java.util.concurrent.Semaphore;

public class ChameleonDeviceConfig {

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
    public static Bundle lastApplicationRunConfig;

    public static boolean chameleonDeviceIsConfigured() {
        return THE_CHAMELEON_DEVICE != null && THE_CHAMELEON_DEVICE.isConfigured();
    }

    /* Setup all possible (i.e., beyond minimal) parameters on the device. Whether this is
     * necessary, or a good feature to setup in the running application really depends on the
     * end needs of the client application. If there is any *logging* going on in the end-user
     * client application, then I would suggest turning this on, for example, to generate a
     * unique UID for the device for a batch of deliveries, or for a particular employee's
     * device to distinguish delivery runs made by that person over time (just a suggestion :))
     *
     * Note that if the lastApplicationRunConfig Bundle variable is null and resetFromBundleFlag is
     * set to 1, then the operation will fail. This static Bundle variable needs to be initialized
     * by running the ChameleonMiniUSBActivity Activity (there are various ways to do this) before
     * attempting to initialize this class. If on the other hand, resetFromBundleFlag is set to a
     * flag other than a boolean-valued 0/1 parameter, if the Bundle is null, then the
     * initialization procedure will continue and choose a priori reasonably *sane* choices for
     * unknown settings such as the device UID.
     *
     * @see THE_CHAMELEON_DEVICE
     * @see globalChameleonDeviceIsConfigured()
     * @see lastApplicationRunConfig
     */
    public static boolean globalChameleonDeviceFullyConfigure(int resetFromBundleFlag) {
        if(!chameleonDeviceIsConfigured()) {
            return false;
        }
        if(resetFromBundleFlag == 1 && lastApplicationRunConfig == null) {
            return false;
        }
        else if(resetFromBundleFlag == 1) {}
        else if(FLAG_IS_OTHER(resetFromBundleFlag) || resetFromBundleFlag == FLAG_FALSE) {}
        // TODO: Bundle
        return true;
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
    public static final Semaphore serialPortLock = new Semaphore(1, true);
    private static boolean usbReceiversRegistered = false;
    public static final int USB_DATA_BITS = 16;

    public static void configureSerialPort() {}

    public static boolean handleNewUSBDeviceAttached() { return true; }

    public static boolean shutdownSerialConnection() { return true; }

    public static String[] getChameleonMiniUSBDeviceParams() { return null; }


    /**** Handle actual communicating with the Chameleon Mini over serial USB. This includes
     **** providing an easy-to-use mechanism for translating between the RevE versus RevG
     **** variants of the common RevE command set. ****/

    public static ChameleonCommandResult sendRawStringToChameleon(String cmdString) { return null; }

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
        else if(uidOperation == ChameleonUIDTypeSpec_t.INCREMENT_EXISTING) {
            String priorUIDString =
        }

        byte[] nextUIDBytes = new byte[chameleonUIDNumBytes];
        switch(uidOperation) {
            case TRULY_RANDOM:
                nextUIDBytes = Utils.generateRandomBytes(chameleonUIDNumBytes);
                break;
            case PREFIXED_RANDOMIZED:
                nextUIDBytes = Utils.generateRandomBytes(chameleonUIDPrefixBytes, chameleonUIDNumBytes - chameleonUIDPrefixBytes.length);
                break;
        }


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







}

