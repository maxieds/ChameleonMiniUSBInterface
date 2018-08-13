package com.maxieds.chameleonminiusb;

import android.content.Intent;
import android.content.IntentFilter;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

import static android.content.ContentValues.TAG;
import static com.maxieds.chameleonminiusb.LibraryLogging.LocalLoggingLevel.LOG_ADB_OFF;

public class LibraryLogging {

    public static enum LocalLoggingLevel {
        LOG_ADB_OFF,
        LOG_ADB_INFO,
        LOG_ADB_WARN,
        LOG_ADB_ERROR,
        LOG_ADB_DEBUG,
        LOG_ADB_VERBOSE,
    };

    public static LocalLoggingLevel localLoggingLevel = LocalLoggingLevel.LOG_ADB_VERBOSE;
    public static boolean broadcastAllLogsToReceivers = true;
    public static boolean writeLogsToFileOnShutdown = true;

    public static class LogEntry {

        /**
         * Obtains the line number of the last calling function. Useful when combined with the
         * TAG string parameter to the ADB Log.* commands for printing more useful output.
         * @return integer line number
         */
        public static int LINE() {
            int invokingLine = Thread.currentThread().getStackTrace()[6].getLineNumber();
            lastLineNumber = invokingLine;
            return invokingLine;
        }

        public static String FUNC() {
            String invokingMethodName = Thread.currentThread().getStackTrace()[6].getMethodName();
            lastMethodName = invokingMethodName;
            return invokingMethodName;
        }

        public static int lastLineNumber;
        public static String lastMethodName;

        /**
         * Accounting for the library's logging functionality:
         */
        public static long uniqueLogCounter = 0L;
        public static ArrayList<LogEntry> loggingQueue = new ArrayList<LogEntry>();

        public static LogEntry enqueueNewLog(LocalLoggingLevel level, String TAG, String[] message) {
            LogEntry logEntry = LogEntry.newInstance(level, TAG, message);
            loggingQueue.add(logEntry);
            if(broadcastAllLogsToReceivers) {
                logEntry.broadcastAsIntent();
            }
            return logEntry;
        }

        public static LogEntry enqueueNewLog(ChameleonCommands.ChameleonCommandResult cmdRxResp) {
            LogEntry logEntry = LogEntry.newInstance(cmdRxResp);
            loggingQueue.add(logEntry);
            if(broadcastAllLogsToReceivers) {
                logEntry.broadcastAsIntent();
            }
            return logEntry;
        }

        private boolean usesSourceCodeAcct;
        private long uniqueLogID;
        private String logTimestamp;
        private String logSeverity;
        private String invokingClassTag;
        private int invokingLineNumber;
        private String invokingMethodName;
        private String[] logMsgs;

        public static LogEntry newInstance(LocalLoggingLevel level, String TAG, String[] message) {
            LogEntry le = new LogEntry();
            le.usesSourceCodeAcct = true;
            le.uniqueLogID = ++uniqueLogCounter;
            le.logTimestamp = Utils.getTimestamp();
            le.logSeverity = level.name();
            le.invokingClassTag = TAG;
            le.invokingLineNumber = LINE();
            le.invokingMethodName = FUNC();
            le.logMsgs = message;
            return le;
        }

        public static LogEntry newInstance(ChameleonCommands.ChameleonCommandResult cmdRxResp) {
            LogEntry le = new LogEntry();
            le.usesSourceCodeAcct = false;
            le.uniqueLogID = ++uniqueLogCounter;
            le.logTimestamp = Utils.getTimestamp();
            le.logSeverity = "COMMAND_RESULT";
            le.invokingClassTag = "";
            le.invokingLineNumber = -1;
            le.invokingMethodName = "";
            le.logMsgs = new String[] { cmdRxResp.issuingCmd, cmdRxResp.cmdResponseMsg, cmdRxResp.cmdResponseData };
            return le;
        }

        public Intent broadcastAsIntent() {
            if(ChameleonDeviceConfig.THE_CHAMELEON_DEVICE.mainApplicationActivity == null) {
                return null;
            }
            Intent logMsgIntent = new Intent("com.maxieds.chameleonminiusb.LibraryLogging");
            logMsgIntent.putExtra("LogSeverity", logSeverity);
            logMsgIntent.putExtra("UniqueLogID", uniqueLogID);
            logMsgIntent.putExtra("Timestamp", logTimestamp);
            logMsgIntent.putExtra("SourceCodeRefs",
                    usesSourceCodeAcct ? String.format(Locale.ENGLISH, "%s:%s @ %d", invokingClassTag, invokingMethodName, invokingLineNumber) : "");
            logMsgIntent.putExtra("MessageData", logMsgs);
            ChameleonDeviceConfig.mainApplicationActivity.onReceiveNewLoggingData(logMsgIntent);
            return logMsgIntent;
        }

        private static String XMLTAG(String tagName, String tagValue) {
            return "     <" + tagName + ">" + tagValue + "</" + tagName + ">\n";
        }

        public static boolean writeLogsToXMLFile() {
            File xmlOutFile = createTimestampedXMLLogFile();
            try {
                FileWriter fileWriter = new FileWriter(xmlOutFile);
                for(int log = 0; log < loggingQueue.size(); log++) {
                    LogEntry le = loggingQueue.get(log);
                    String[] logXMLEntry = new String[] {
                            "<LogEntry>",
                            XMLTAG("LogID", String.valueOf(le.uniqueLogID)),
                            XMLTAG("TimeStamp", le.logTimestamp),
                            XMLTAG("Level", le.logSeverity),
                            XMLTAG("InvokingClass", le.invokingClassTag),
                            XMLTAG("InvokvingMethod", le.invokingMethodName),
                            XMLTAG("InvokingLineNumber", String.valueOf(le.invokingLineNumber)),
                            "     <MessageData>\n",
                    };
                    fileWriter.write(String.join("", logXMLEntry));
                    for(int msg = 0; msg < le.logMsgs.length; msg++) {
                        String msgTagLine = String.format(Locale.ENGLISH, "          <Msg>%s</Msg>\n", le.logMsgs[msg]);
                        fileWriter.write(msgTagLine);
                    }
                    fileWriter.write("     </MessageData>\n</LogEntry>");
                }
                fileWriter.flush();
                fileWriter.close();
            } catch(Exception ioe) {
                xmlOutFile.delete();
                return false;
            }
            return true;
        }

        public static boolean writeLogsToPlainTextFile() {
            File ptextOutFile = createTimestampedPlaintextLogFile();
            try {
                FileWriter fileWriter = new FileWriter(ptextOutFile);
                for(int log = 0; log < loggingQueue.size(); log++) {
                    LogEntry le = loggingQueue.get(log);
                    String[] ptLogs = new String[] {
                            String.format(Locale.ENGLISH, "============================== #% 8x @ %s ==============================", le.uniqueLogID, le.logTimestamp),
                            String.format(Locale.ENGLISH, "   [LEVEL] %s\n", le.logSeverity),
                            String.format(Locale.ENGLISH, "   [CLASS] %s\n", le.invokingClassTag),
                            String.format(Locale.ENGLISH, "   [FUNC]  %s\n", le.invokingMethodName),
                            String.format(Locale.ENGLISH, "   [LINE]  %s\n", le.invokingLineNumber),
                    };
                    fileWriter.write(String.join("", ptLogs));
                    for(int msg = 0; msg < le.logMsgs.length; msg++) {
                        String msgLine = String.format(Locale.ENGLISH, "   [MSG  ] %s\n", le.logMsgs[msg]);
                        fileWriter.write(msgLine);
                    }
                    fileWriter.write("\n");
                }
                fileWriter.flush();
                fileWriter.close();
            } catch(Exception ioe) {
                ptextOutFile.delete();
                return false;
            }
            return true;
        }

    };

    /**** Now we define local versions of the standard ADB logging functions for replacements
     **** of the defaults in our library. ****/
    public static boolean SUPPORT_ADB_LOGGING = true;

    public static void v(String TAG, String MSG) {
        if(LocalLoggingLevel.LOG_ADB_VERBOSE.compareTo(localLoggingLevel) <= 0 && localLoggingLevel.compareTo(LOG_ADB_OFF) != 0) {
            LogEntry.enqueueNewLog(LocalLoggingLevel.LOG_ADB_VERBOSE, TAG, new String[] {MSG});
            if(SUPPORT_ADB_LOGGING)
                Log.v(TAG + "::" + localLoggingLevel.name(), MSG);
        }
    }

    public static void d(String TAG, String MSG) {
        if(LocalLoggingLevel.LOG_ADB_DEBUG.compareTo(localLoggingLevel) <= 0 && localLoggingLevel.compareTo(LOG_ADB_OFF) != 0) {
            LogEntry.enqueueNewLog(LocalLoggingLevel.LOG_ADB_DEBUG, TAG, new String[] {MSG});
            if(SUPPORT_ADB_LOGGING)
                Log.d(TAG + "::" + localLoggingLevel.name(), MSG);
        }
    }

    public static void i(String TAG, String MSG) {
        if(LocalLoggingLevel.LOG_ADB_INFO.compareTo(localLoggingLevel) <= 0 && localLoggingLevel.compareTo(LOG_ADB_OFF) != 0) {
            LogEntry.enqueueNewLog(LocalLoggingLevel.LOG_ADB_INFO, TAG, new String[] {MSG});
            if(SUPPORT_ADB_LOGGING)
                Log.i(TAG + "::" + localLoggingLevel.name(), MSG);
        }
    }

    public static void w(String TAG, String MSG) {
        if(LocalLoggingLevel.LOG_ADB_WARN.compareTo(localLoggingLevel) <= 0 && localLoggingLevel.compareTo(LOG_ADB_OFF) != 0) {
            LogEntry.enqueueNewLog(LocalLoggingLevel.LOG_ADB_WARN, TAG, new String[] {MSG});
            if(SUPPORT_ADB_LOGGING)
                Log.w(TAG + "::" + localLoggingLevel.name(), MSG);
        }
    }

    public static void e(String TAG, String MSG) {
        if(LocalLoggingLevel.LOG_ADB_ERROR.compareTo(localLoggingLevel) <= 0 && localLoggingLevel.compareTo(LOG_ADB_OFF) != 0) {
            LogEntry.enqueueNewLog(LocalLoggingLevel.LOG_ADB_ERROR, TAG, new String[] {MSG});
            if(SUPPORT_ADB_LOGGING)
                Log.e(TAG + "::" + localLoggingLevel.name(), MSG);
        }
    }

    /**** Creating and writing logs to file ****/
    public static final String localLoggingBaseDirectory = "ChameleonMiniOperationLogs";
    public static final String localXMLLoggingFilePrefix = "ChameleonMiniUSBLibrary-XMLLog-";
    public static final String localPlaintextLoggingFilePrefix = "ChameleonMiniUSBLibrary-PlaintextLog-";

    public static File createTimestampedLogFile(String baseDirectory, String outputFilePath) {
        String extStoragePathPrefix = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
        File outputFile;
        try {
            File outputDir = new File(extStoragePathPrefix, baseDirectory);
            if(!outputDir.exists() && !outputDir.mkdirs()) {
                LibraryLogging.e(TAG, "Unable to create directories for writing.");
                return null;
            }
            outputFile = new File(outputDir.getPath() + File.separator + outputFilePath);
            outputFile.createNewFile();
            outputFile.setReadable(true, false);
            outputFilePath = outputFile.getAbsolutePath();
        }
        catch(IOException ioe) {
            LibraryLogging.e(TAG, "Unable to create file in path for writing: " + ioe.getMessage());
            return null;
        }
        return outputFile;
    }

    public static File createTimestampedXMLLogFile() {
        String fileName = localXMLLoggingFilePrefix + Utils.getTimestamp() + ".xml";
        return createTimestampedLogFile(localLoggingBaseDirectory, fileName);
    }

    public static File createTimestampedPlaintextLogFile() {
        String fileName = localPlaintextLoggingFilePrefix + Utils.getTimestamp() + ".txt";
        return createTimestampedLogFile(localLoggingBaseDirectory, fileName);
    }

}

