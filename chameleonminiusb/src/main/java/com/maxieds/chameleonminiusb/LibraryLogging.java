package com.maxieds.chameleonminiusb;

import libbasicchameleoninterface.BuildConfig;
import sun.rmi.runtime.Log;

public class LibraryLogging {

    public static final String localLoggingBaseDirectory = BuildConfig.LOGGING_FILE_DIRECTORY;
    public static final String localXMLLoggingFilePrefix = "ChameleonMiniUSBLibrary-XMLLog-";
    public static final String localPlaintextLoggingFilePrefix = "ChameleonMiniUSBLibrary-PlaintextLog-";

    public static enum LocalLoggingLevel {
        LOG_ADB_VERBOSE,
        LOG_ADB_ASSERT,
        LOG_ADB_DEBUG,
        LOG_ADB_ERROR,
        LOG_ADB_WARN,
        LOG_ADB_INFO,
        LOG_ADB_BRIEF,
        LOG_OFF,
        //LOG_PRINT_TO_XML_FILE,
        //LOG_PRINT_TO_PLAINTEXT_FILE
    };

    public static LocalLoggingLevel localLoggingLevel = LocalLoggingLevel.LOG_ADB_ERROR;

    /**** Now we define local versions of the standard ADB logging functions for replacements
     **** of the defaults in our library. ****/
    public static boolean SUPPORT_ADB_LOGGING = true;







}

