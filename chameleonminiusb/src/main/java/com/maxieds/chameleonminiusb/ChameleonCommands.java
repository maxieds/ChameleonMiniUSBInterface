package com.maxieds.chameleonminiusb;

import android.annotation.TargetApi;
import android.util.Log;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class ChameleonCommands {

    private static final String TAG = ChameleonCommands.class.getSimpleName();

    public static enum StandardCommandSet {
        GET_VERSION,
        SET_CONFIG,
        QUERY_CONFIG,
        QUERY_UID,
        SET_UID,
        QUERY_READONLY,
        SET_READONLY,
        UPLOAD_XMODEM,
        DOWNLOAD_XMODEM,
        RESET_DEVICE,
        GET_MEMORY_SIZE,
        GET_UID_SIZE,
        GET_ACTIVE_SLOT,
        SET_ACTIVE_SLOT,
        CLEAR_ACTIVE_SLOT,
        GET_RSSI_VOLTAGE,
    };

    /**
     * Returns a String array with two elements: the first is the format string for the RevE
     * command, and the second is the format string for the corresponding RevG command.
     * Note that by "format string" we mean that if there are (any) parameters to be appended to the
     * command, then this should be done by a call to:
     * String.format(Locale.ENGLISH, cmdFormatStr, IntOrStringCmdArgument);
     * @param cmd
     * @return Array of distinct format strings for each device revision.
     * @ref (RevE) https://github.com/iceman1001/ChameleonMini-rebooted/wiki/Terminal-Commands
     * @ref (RevG) https://rawgit.com/emsec/ChameleonMini/master/Doc/Doxygen/html/_page__command_line.html
     */
    public static String[] getCommandFormatString(StandardCommandSet cmd) {
        switch(cmd) {
            case GET_VERSION:
                return new String[] {"versionmy?", "VERSION?"};
            case SET_CONFIG:
                return new String[] {"configmy=%s", "CONFIG=%s"};
            case QUERY_CONFIG:
                return new String[] {"configmy?", "CONFIG?"};
            case QUERY_UID:
                return new String[] {"uidmy?", "UID?"};
            case SET_UID:
                return new String[] {"uidmy=%s", "UID=%s"};
            case QUERY_READONLY:
                return new String[] {"readonlymy?", "READONLY?"};
            case SET_READONLY:
                return new String[] {"readonlymy=%d", "READONLY=%d"};
            case UPLOAD_XMODEM:
                return new String[] {"uploadmy", "UPLOAD"};
            case DOWNLOAD_XMODEM:
                return new String[] {"downloadmy", "DOWNLOAD"};
            case RESET_DEVICE:
                return new String[] {"resetmy", "RESET"};
            case GET_MEMORY_SIZE:
                return new String[] {"memsizemy?", "MEMSIZE?"};
            case GET_UID_SIZE:
                return new String[] {"uidsizemy?", "UIDSIZE?"};
            case GET_ACTIVE_SLOT:
                return new String[] {"settingmy?", "SETTING?"};
            case SET_ACTIVE_SLOT:
                return new String[] {"settingmy=%d", "SETTING=%d"};
            case CLEAR_ACTIVE_SLOT:
                return new String[] {"clearmy", "CLEAR"};
            case GET_RSSI_VOLTAGE:
                return new String[] {"rssimy?", "RSSI?"};
            default:
                return null;
        }
    }

    /**
     * <h1>Serial Response Code</h1>
     * The class SerialRespCode contains extended enum definitions of the possible response
     * codes returned by the device. Also provides helper methods.
     */
    public enum SerialRespCode {

        /**
         * List of the status codes and their corresponding text descriptions
         * (taken almost verbatim from the ChameleonMini source code).
         */
        OK(100),
        OK_WITH_TEXT(101),
        WAITING_FOR_MODEM(110),
        TRUE(121),
        FALSE(120),
        UNKNOWN_COMMAND(200),
        INVALID_COMMAND_USAGE(201),
        INVALID_PARAMETER(202),
        TIMEOUT(203);

        /**
         * Integer value associated with each enum value.
         */
        private int responseCode;

        /**
         * Constructor
         *
         * @param rcode
         */
        private SerialRespCode(int rcode) {
            responseCode = rcode;
        }

        /**
         * Stores a map of integer-valued response codes to their corresponding enum value.
         */
        private static final Map<Integer, SerialRespCode> RESP_CODE_MAP = new HashMap<>();

        static {
            for (SerialRespCode respCode : values()) {
                int rcode = respCode.toInteger();
                Integer aRespCode = Integer.valueOf(rcode);
                RESP_CODE_MAP.put(aRespCode, respCode);
            }
        }

        /**
         * Lookup table of String response codes prefixing command return data sent by the device.
         *
         * @ref ChameleonIO.isCommandResponse
         */
        public static final Map<String, SerialRespCode> RESP_CODE_TEXT_MAP = new HashMap<>();
        public static final Map<String, SerialRespCode> RESP_CODE_TEXT_MAP2 = new HashMap<>();

        static {
            for (SerialRespCode respCode : values()) {
                String rcode = String.valueOf(respCode.toInteger());
                String rcodeText = respCode.name().replace("_", " ");
                RESP_CODE_TEXT_MAP.put(rcode + ":" + rcodeText, respCode);
                RESP_CODE_TEXT_MAP2.put(rcode, respCode);
            }
        }

        /**
         * Retrieve the integer-valued response code associated with the enum value.
         *
         * @return int response code
         */
        public int toInteger() {
            return responseCode;
        }

        /**
         * Lookup the enum value by its associated integer response code value.
         *
         * @param rcode
         * @return SerialRespCode enum value associated with the integer code
         */
        public static SerialRespCode lookupByResponseCode(int rcode) {
            return RESP_CODE_MAP.get(rcode);
        }

    }

    public static final String NODATA = "<NO-DATA>";

    public static class ChameleonCommandResult {

        public String issuingCmd;
        public String cmdResponseMsg;
        public String cmdResponseData;
        public int cmdResponseCode;
        public boolean isValid;

        ChameleonCommandResult() {
            issuingCmd = NODATA;
            cmdResponseMsg = "";
            cmdResponseData = NODATA;
            cmdResponseCode = -1;
            isValid = false;
        }

        ChameleonCommandResult(String initCmd) {
            issuingCmd = initCmd;
            cmdResponseMsg = NODATA;
            cmdResponseData = NODATA;
            cmdResponseCode = -1;
            isValid = false;
        }

        public String toString() {
            return String.format(Locale.ENGLISH, "CMD(%s) => [%d] : %s", issuingCmd, cmdResponseCode, cmdResponseData);
        }

        /**
         * Determines whether the received serial byte data is a command response sent by the device.
         *
         * @param liveLogData
         * @return boolean whether the log data is a response to an issued command
         */
        public static boolean isCommandResponse(byte[] liveLogData) {
            String respText = (new String(liveLogData)).split("[\n\r]+")[0];
            if(SerialRespCode.RESP_CODE_TEXT_MAP.get(respText) != null)
                return true;
            respText = (new String(liveLogData)).split(":")[0];
            if(respText != null && respText.length() >= 3 && SerialRespCode.RESP_CODE_TEXT_MAP2.get(respText.substring(respText.length() - 3)) != null)
                return true;
            return false;
        }

        /**
         * Takes as input the byte array returned by the chameleon. If it is in fact a valid
         * command response, we parse it into it's component parts (storing each of them in the
         * public member functions above) and return true if the command response was valid, and
         * false otherwise.
         * @param responseBytes
         * @return boolean-valued truth of whether the input is a valid command response.
         */
        @TargetApi(27)
        public boolean processCommandResponse(byte[] responseBytes) {
            if(!isCommandResponse(responseBytes)) {
                isValid = false;
                return false;
            }
            String[] splitCmdResp = (new String(responseBytes)).split("[\n\r]+");
            cmdResponseMsg = splitCmdResp[0];
            cmdResponseCode = Integer.parseInt(splitCmdResp[0].split(":")[0]);
            if(splitCmdResp.length >= 2) {
                cmdResponseData = String.join("\n", Arrays.copyOfRange(splitCmdResp, 1, splitCmdResp.length));
            }
            else {
                cmdResponseData = NODATA;
            }
            isValid = (cmdResponseCode == SerialRespCode.OK.toInteger()) || (cmdResponseCode == SerialRespCode.OK_WITH_TEXT.toInteger());
            return true;
        }

    }

}
