package com.maxieds.chameleonminiusb;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.os.Environment;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

import libbasicchameleoninterface.BuildConfig;
import sun.rmi.runtime.Log;

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
            int invokingLine = Thread.currentThread().getStackTrace()[4].getLineNumber();
            lastLineNumber = invokingLine;
            return invokingLine;
        }

        public static String FUNC() {
            String invokingMethodName = Thread.currentThread().getStackTrace()[4].getMethodName();
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
            LogEntry logEntry = new LogEntry(level, TAG, message);
            loggingQueue.add(logEntry);
            if(broadcastAllLogsToReceivers) {
                logEntry.broadcastAsIntent();
            }
            return logEntry;
        }

        long uniqueLogID;
        String logTimestamp;
        String logSeverity;
        String invokingClassTag;
        int invokingLineNumber;
        String invokingMethodName;
        String[] logMsgs;

        LogEntry(LocalLoggingLevel level, String TAG, String[] message) {
            uniqueLogID = ++uniqueLogCounter;
            logTimestamp = Utils.getTimestamp();
            logSeverity = "LOGDATA_" + level.name().split("_", 2)[2];
            invokingClassTag = TAG;
            invokingLineNumber = LINE();
            invokingMethodName = FUNC();
            logMsgs = message;
        }

        public Intent broadcastAsIntent() {
            if(ChameleonDeviceConfig.THE_CHAMELEON_DEVICE.mainApplicationActivity == null) {
                return null;
            }
            Intent logMsgIntent = new Intent(logSeverity);
            logMsgIntent.putExtra("UniqueLogID", uniqueLogID);
            logMsgIntent.putExtra("Timestamp", logTimestamp);
            logMsgIntent.putExtra("SourceCodeRefs",
                    String.format(Locale.ENGLISH, "%s:%s @ %d", invokingClassTag, invokingMethodName, invokingLineNumber));
            logMsgIntent.putExtra("MessageData", logMsgs);
            LocalBroadcastManager.getInstance(ChameleonDeviceConfig.THE_CHAMELEON_DEVICE.mainApplicationActivity).sendBroadcast(logMsgIntent);
            return logMsgIntent;
        }

        public static boolean writeLogsToXMLFile() {
            File xmlOutFile = createTimestampedXMLLogFile();
            return false;
        }

        public static boolean writeLogsToPlainTextFile() {
            File ptextOutFile = createTimestampedPlaintextLogFile();
            return false;
        }

    };

    /**** Now we define local versions of the standard ADB logging functions for replacements
     **** of the defaults in our library. ****/
    public static boolean SUPPORT_ADB_LOGGING = true;

    public static void v(String TAG, String MSG) {
        if(LocalLoggingLevel.LOG_ADB_VERBOSE.compareTo(localLoggingLevel) > 0 && localLoggingLevel.compareTo(LOG_ADB_OFF) != 0) {
            LogEntry.enqueueNewLog(LocalLoggingLevel.LOG_ADB_VERBOSE, TAG, new String[] {MSG});
            if(SUPPORT_ADB_LOGGING)
                Log.v(TAG, MSG);
        }
    }

    public static void d(String TAG, String MSG) {
        if(LocalLoggingLevel.LOG_ADB_DEBUG.compareTo(localLoggingLevel) > 0 && localLoggingLevel.compareTo(LOG_ADB_OFF) != 0) {
            LogEntry.enqueueNewLog(LocalLoggingLevel.LOG_ADB_DEBUG, TAG, new String[] {MSG});
            if(SUPPORT_ADB_LOGGING)
                Log.d(TAG, MSG);
        }
    }

    public static void i(String TAG, String MSG) {
        if(LocalLoggingLevel.LOG_ADB_INFO.compareTo(localLoggingLevel) > 0 && localLoggingLevel.compareTo(LOG_ADB_OFF) != 0) {
            LogEntry.enqueueNewLog(LocalLoggingLevel.LOG_ADB_INFO, TAG, new String[] {MSG});
            if(SUPPORT_ADB_LOGGING)
                Log.i(TAG, MSG);
        }
    }

    public static void w(String TAG, String MSG) {
        if(LocalLoggingLevel.LOG_ADB_WARN.compareTo(localLoggingLevel) > 0 && localLoggingLevel.compareTo(LOG_ADB_OFF) != 0) {
            LogEntry.enqueueNewLog(LocalLoggingLevel.LOG_ADB_WARN, TAG, new String[] {MSG});
            if(SUPPORT_ADB_LOGGING)
                Log.w(TAG, MSG);
        }
    }

    public static void e(String TAG, String MSG) {
        if(LocalLoggingLevel.LOG_ADB_ERROR.compareTo(localLoggingLevel) > 0 && localLoggingLevel.compareTo(LOG_ADB_OFF) != 0) {
            LogEntry.enqueueNewLog(LocalLoggingLevel.LOG_ADB_ERROR, TAG, new String[] {MSG});
            if(SUPPORT_ADB_LOGGING)
                Log.e(TAG, MSG);
        }
    }

    /**** Creating and writing logs to file ****/
    public static final String localLoggingBaseDirectory = BuildConfig.LOGGING_FILE_DIRECTORY;
    public static final String localXMLLoggingFilePrefix = "ChameleonMiniUSBLibrary-XMLLog-";
    public static final String localPlaintextLoggingFilePrefix = "ChameleonMiniUSBLibrary-PlaintextLog-";

    @TargetApi(27)
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

