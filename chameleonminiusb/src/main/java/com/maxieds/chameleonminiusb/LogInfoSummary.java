package com.maxieds.chameleonminiusb;

import android.content.Intent;

public class LogInfoSummary {

    public String logIntentAction;
    public String logSeverity;
    public long uniqueLogID;
    public String logTimestamp;
    public String logSourceCodeRefs;
    public String[] logMessageData;

    public static LogInfoSummary extractLoggingIntentData(Intent intent) {
        LogInfoSummary lis = new LogInfoSummary();
        lis.logIntentAction = intent.getAction();
        lis.logSeverity = intent.getStringExtra("LogSeverity");
        lis.uniqueLogID = intent.getLongExtra("UniqueLogID", -1);
        lis.logTimestamp = intent.getStringExtra("Timestamp");
        lis.logSourceCodeRefs = intent.getStringExtra("SourceCodeRefs");
        lis.logMessageData = intent.getStringArrayExtra("MessageData");
        return lis;

    }

}