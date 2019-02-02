package com.maxieds.chameleonminiusb;

import android.app.DownloadManager;
import android.content.Context;
import android.os.Handler;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import static android.content.ContentValues.TAG;
import static android.content.Context.DOWNLOAD_SERVICE;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.DOWNLOAD_XMODEM;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.QUERY_READONLY;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.SET_READONLY;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.UPLOAD_ENCRYPTED;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.UPLOAD_XMODEM;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.DOWNLOAD;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.IDLE;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.UPLOAD;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.WAITING_FOR_XMODEM_DOWNLOAD;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.WAITING_FOR_XMODEM_UPLOAD;

/**
 * <h1>XModem</h1>
 * The XModem class provides utilities for storing logs to file, downloading / uploading
 * card data via XModem, and downloading the stored log data from the device.
 * Parts of this code for the XModem connections are based on
 * XModem.c/h in the Chameleon Mini firmware distribution.
 *
 * @author  Maxie D. Schmidt
 * @since   2018.08.09 (originally coded on 1/11/18)
 * @url http://rawgit.com/emsec/ChameleonMini/master/Doc/Doxygen/html/_x_modem_8h_source.html
 */
public class XModem {

    private static final String TAG = XModem.class.getSimpleName();

    /**
     * State information for the XModem connections/
     */
    public static boolean EOT = false;

    /**
     * Named XModem connection status bytes.
     */
    public static final byte BYTE_NAK = (byte) 0x15;
    public static final byte BYTE_SOH = (byte) 0x01;
    public static final byte BYTE_ACK = (byte) 0x06;
    public static final byte BYTE_CAN = (byte) 0x18;
    public static final byte BYTE_EOF = (byte) 0x1A;
    public static final byte BYTE_EOT = (byte) 0x04;
    public static final byte BYTE_ESC = (byte) 0x1B;

    /**
     * XModem connection configuration.
     */
    public static final short XMODEM_BLOCK_SIZE = 128;
    public static final byte FIRST_FRAME_NUMBER = (byte) 1;
    public static final byte CHECKSUM_INIT_VALUE = 0;
    public static int MAX_NAK_COUNT = 20; // to match the Chameleon device standard

    /**
     * Static variables used internally within the class.
     */
    private static int fileSize = 0;
    private static FileOutputStream streamDest;
    private static InputStream streamSrc;
    private static byte[] streamBytes;
    private static boolean useInputStream;
    private static int streamBytesIndex;
    private static File outfile;
    private static byte CurrentFrameNumber;
    private static byte Checksum;
    private static int currentNAKCount;
    private static boolean transmissionErrorOccurred;
    private static int uploadState;
    private static byte[] uploadFramebuffer = new byte[XMODEM_BLOCK_SIZE + 4];
    private static boolean initiallyReadOnly;
    private static int encryptedUploadKeyIndex = 0;
    private static long encryptedUploadTimeStampSalt = 0;

    public static boolean transmissionError() {
        return transmissionErrorOccurred;
    }

    public static boolean uploadUseInputStream() { return useInputStream; }

    public static InputStream getUploadInputStream() { return streamSrc; }

    public static byte[] getUploadByteStream() { return streamBytes; }

    private static int streamDataAvailable() {
        if(useInputStream && streamSrc != null) {
            try {
                return streamSrc.available();
            } catch(IOException ioe) {
                return 0;
            }
        }
        else if(streamBytes != null){
            return Math.max(0, streamBytes.length - streamBytesIndex);
        }
        return 0;
    }

    private static byte[] readStreamData(int numBytes) {
        if(numBytes <= 0 || streamDataAvailable() < numBytes) {
            return null;
        }
        byte[] payloadBytes = new byte[numBytes];
        try {
            if (useInputStream) {
                streamSrc.read(payloadBytes, 0, numBytes);
            } else {
                System.arraycopy(streamBytes, streamBytesIndex, payloadBytes, 0, numBytes);
                streamBytesIndex += numBytes;
            }
        } catch(Exception ioe) {
            return null;
        }
        return payloadBytes;
    }

    /**
     * Completes the XModem download command. Implemented this way to keep the GUI from
     * freezing waiting for the command to complete by while loop / Thread.sleep.
     * @ref ExportTools.downloadByXModem
     * @ref ExportTools.performXModemSerialDownload
     */
    public static Runnable eotSleepRunnable = new Runnable() {

        public void run() {
            if(!XModem.EOT || ChameleonDeviceConfig.serialUSBState.compareTo(WAITING_FOR_XMODEM_UPLOAD) == 0 ||
                    ChameleonDeviceConfig.serialUSBState.compareTo(WAITING_FOR_XMODEM_DOWNLOAD) == 0) {
                eotSleepHandler.postDelayed(this, 50);
            }
            else if(ChameleonDeviceConfig.serialUSBState.compareTo(DOWNLOAD) == 0){
                try {
                    streamDest.close();
                } catch (Exception ioe) {
                    LibraryLogging.e(TAG, ioe.getMessage() + "\n" + ioe.getStackTrace());
                } finally {
                    ChameleonDeviceConfig.serialUSBState = IDLE;
                    ChameleonDeviceConfig.serialPortLock.release();
                }
                if(!XModem.transmissionErrorOccurred) {
                    DownloadManager downloadManager = (DownloadManager) ChameleonDeviceConfig.mainApplicationActivity.getDefaultContext().getSystemService(DOWNLOAD_SERVICE);
                    downloadManager.addCompletedDownload(outfile.getName(), outfile.getName(), true, "application/octet-stream",
                            outfile.getAbsolutePath(), outfile.length(), true);
                }
                else {
                    outfile.delete();
                    LibraryLogging.e(TAG, "Maximum number of NAK errors exceeded. Download of data aborted.");
                }
            }
            else if(ChameleonDeviceConfig.serialUSBState.compareTo(UPLOAD) == 0) {
                ChameleonDeviceConfig.serialUSBState = IDLE;
                ChameleonDeviceConfig.serialPortLock.release();
                if(XModem.initiallyReadOnly) {
                    ChameleonDeviceConfig.sendCommandToChameleon(SET_READONLY, 1);
                }
                if(XModem.transmissionErrorOccurred) {
                    LibraryLogging.e(TAG, "File transmission errors encountered. Maximum number of NAK errors exceeded. Upload of data aborted.");
                }
            }
        }
    };
    public static Handler eotSleepHandler = new Handler();

    /**
     * Calculates the checksum of the passed byte buffer.
     * @param buffer
     * @param byteCount
     * @return byte checksum value
     */
    public static byte CalcChecksum(byte[] buffer, short byteCount) {
        byte checksum = CHECKSUM_INIT_VALUE;
        int bufPos = 0;
        while(byteCount-- != 0) {
            checksum += buffer[bufPos++];
        }
        return checksum;
    }

    /**
     * Implements the actual data exchange with the card in the upload process.
     * @param liveLogData
     */
    public static void performXModemSerialUpload(byte[] liveLogData) {
        if(XModem.EOT || liveLogData == null || liveLogData.length == 0) {
            eotSleepRunnable.run();
            return;
        }
        LibraryLogging.v(TAG, "Received Upload Data (#=" + liveLogData.length + ") ... " + Utils.byteArrayToString(liveLogData));
        byte statusByte = liveLogData[0];
        if(uploadState == 0 || uploadState == 1 && statusByte == BYTE_ACK) {
            if(uploadState == 1)
                CurrentFrameNumber++;
            else
                uploadState = 1;
            uploadFramebuffer[0] = BYTE_SOH;
            uploadFramebuffer[1] = CurrentFrameNumber;
            uploadFramebuffer[2] = (byte) (255 - CurrentFrameNumber);
            byte[] payloadBytes;
            try {
                if(streamDataAvailable() < XMODEM_BLOCK_SIZE) {
                    ChameleonDeviceConfig.serialPort.write(new byte[]{BYTE_EOT});
                    EOT = true;
                    eotSleepRunnable.run();
                    return;
                }
                payloadBytes = readStreamData(XMODEM_BLOCK_SIZE);
                if(payloadBytes == null) {
                    throw new IOException("Insufficient data to upload next block to the device.");
                }
                System.arraycopy(payloadBytes, 0, uploadFramebuffer, 3, XMODEM_BLOCK_SIZE);
            } catch(IOException ioe) {
                transmissionErrorOccurred = true;
                ChameleonDeviceConfig.serialPort.write(new byte[]{BYTE_CAN});
                EOT = true;
                eotSleepRunnable.run();
                return;
            }
            uploadFramebuffer[XMODEM_BLOCK_SIZE + 3] = CalcChecksum(payloadBytes, XMODEM_BLOCK_SIZE);
            LibraryLogging.d(TAG, "Upload Writing Data: frame=" + CurrentFrameNumber + ": " + Utils.byteArrayToString(uploadFramebuffer));
            ChameleonDeviceConfig.serialPort.write(uploadFramebuffer);
        }
        else if(statusByte == BYTE_NAK && currentNAKCount <= MAX_NAK_COUNT) {
            currentNAKCount++;
            ChameleonDeviceConfig.serialPort.write(uploadFramebuffer);
        }
        else {
            transmissionErrorOccurred = true;
            ChameleonDeviceConfig.serialPort.write(new byte[]{BYTE_CAN});
            EOT = true;
            eotSleepRunnable.run();
            return;
        }
    }

    /**
     * Called to initiate the card data upload process.
     * @ref LiveLoggerActivity.actionButtonUploadCard
     */
    public static void uploadCardFileByXModemRunner() {
        if(ChameleonDeviceConfig.serialPort == null || (streamSrc == null && streamBytes == null))
            return;
        try {
            initiallyReadOnly = (Integer.parseInt(ChameleonDeviceConfig.sendCommandToChameleon(QUERY_READONLY, null).cmdResponseData) == 0);
        } catch(Exception nfe) {
            initiallyReadOnly = false;
        }
        fileSize = 0;
        CurrentFrameNumber = FIRST_FRAME_NUMBER;
        currentNAKCount = -1;
        transmissionErrorOccurred = false;
        uploadState = 0;
        EOT = false;
        ChameleonDeviceConfig.sendCommandToChameleon(SET_READONLY, 0);
        ChameleonDeviceConfig.serialPortLock.acquireUninterruptibly();
        ChameleonDeviceConfig.sendCommandToChameleon(UPLOAD_XMODEM, null, false);
    }

    public static void uploadCardFileByXModem(InputStream cardInputStream) {
        streamSrc = cardInputStream;
        useInputStream = true;
        uploadCardFileByXModemRunner();
    }

    public static void uploadCardFileByXModem(byte[] cardInputBytes) {
        streamBytes = cardInputBytes;
        streamBytesIndex = 0;
        useInputStream = false;
        uploadCardFileByXModemRunner();
    }

    /**
     * Called to initiate the card data upload process.
     * @ref LiveLoggerActivity.actionButtonUploadCard
     */
    public static void uploadEncryptedCardFileByXModemRunner() {
        if(ChameleonDeviceConfig.serialPort == null || (streamSrc == null && streamBytes == null))
            return;
        try {
            initiallyReadOnly = (Integer.parseInt(ChameleonDeviceConfig.sendCommandToChameleon(QUERY_READONLY, null).cmdResponseData) == 0);
        } catch(Exception nfe) {
            initiallyReadOnly = false;
        }
        fileSize = 0;
        CurrentFrameNumber = FIRST_FRAME_NUMBER;
        currentNAKCount = -1;
        transmissionErrorOccurred = false;
        uploadState = 0;
        EOT = false;
        String uploadCmdArgs = String.format(Locale.ENGLISH, "%d %x", encryptedUploadKeyIndex, encryptedUploadTimeStampSalt);
        ChameleonDeviceConfig.sendCommandToChameleon(SET_READONLY, 0);
        ChameleonDeviceConfig.serialPortLock.acquireUninterruptibly();
        ChameleonDeviceConfig.sendCommandToChameleon(UPLOAD_ENCRYPTED, uploadCmdArgs, false);
    }

    public static void uploadEncryptedCardFileByXModem(byte[] cardInputBytes, int keyIndex, long timeStampSaltData) {
        streamBytes = cardInputBytes;
        streamBytesIndex = 0;
        useInputStream = false;
        encryptedUploadKeyIndex = keyIndex;
        encryptedUploadTimeStampSalt = timeStampSaltData;
        uploadEncryptedCardFileByXModemRunner();
    }

    public static void performXModemSerialDownload(byte[] liveLogData) {
        if(XModem.EOT)
            return; // waiting for conclusion of timer to cleanup the download files
        Log.v(TAG, "Received XModem data (#bytes=" + liveLogData.length + ") ... [" + Utils.byteArrayToString(liveLogData) + "]");
        byte[] frameBuffer = new byte[XMODEM_BLOCK_SIZE];
        if (liveLogData != null && liveLogData.length > 0 && liveLogData[0] != XModem.BYTE_EOT) {
            if (liveLogData[0] == XModem.BYTE_SOH && liveLogData[1] == XModem.CurrentFrameNumber &&
                    liveLogData[2] == (byte) (255 - XModem.CurrentFrameNumber)) {
                int dataBufferSize = liveLogData.length - 4;
                System.arraycopy(liveLogData, 3, frameBuffer, 0, XModem.XMODEM_BLOCK_SIZE);
                byte checksumByte = liveLogData[dataBufferSize + 3];
                XModem.Checksum = XModem.CalcChecksum(frameBuffer, XModem.XMODEM_BLOCK_SIZE);
                if (XModem.Checksum != checksumByte && currentNAKCount < MAX_NAK_COUNT) {
                    ChameleonDeviceConfig.serialPort.write(new byte[]{ XModem.BYTE_NAK });
                    currentNAKCount++;
                    return;
                }
                else if(XModem.Checksum != checksumByte) {
                    XModem.EOT = true;
                    XModem.transmissionErrorOccurred = true;
                    ChameleonDeviceConfig.serialPort.write(new byte[] { XModem.BYTE_CAN });
                    return;
                }
                try {
                    XModem.fileSize += liveLogData.length;
                    LibraryLogging.d(TAG, "Download Writing Data: frame=" + CurrentFrameNumber + ": " + Utils.byteArrayToString(frameBuffer));
                    XModem.streamDest.write(frameBuffer);
                    XModem.streamDest.flush();
                    XModem.CurrentFrameNumber++;
                    ChameleonDeviceConfig.serialPort.write(new byte[]{BYTE_ACK});
                } catch (Exception e) {
                    XModem.EOT = true;
                    e.printStackTrace();
                }
            }
            else {
                if(currentNAKCount >= MAX_NAK_COUNT) {
                    XModem.EOT = true;
                    XModem.transmissionErrorOccurred = true;
                    ChameleonDeviceConfig.serialPort.write(new byte[] { XModem.BYTE_CAN });
                    return;
                }
                ChameleonDeviceConfig.serialPort.write(new byte[]{ XModem.BYTE_NAK });
                currentNAKCount++;
            }
        }
        else {
            try {
                ChameleonDeviceConfig.serialPort.write(new byte[]{ XModem.BYTE_ACK });
            } catch (Exception ioe) {
                ioe.printStackTrace();
            }
            XModem.EOT = true;
        }
    }

    public static boolean downloadCardFileByXModem(File cardOutFile) {
        if(cardOutFile == null) {
            return false;
        }
        try {
            streamDest = new FileOutputStream(cardOutFile);
        } catch(IOException ioe) {
            Log.e(TAG, ioe.getMessage());
            ioe.printStackTrace();
            cardOutFile.delete();
            return false;
        }
        outfile = cardOutFile;
        fileSize = 0;
        CurrentFrameNumber = FIRST_FRAME_NUMBER;
        currentNAKCount = 0;
        transmissionErrorOccurred = false;
        EOT = false;
        ChameleonDeviceConfig.serialUSBState = WAITING_FOR_XMODEM_DOWNLOAD;
        ChameleonDeviceConfig.serialPortLock.acquireUninterruptibly();
        ChameleonDeviceConfig.sendCommandToChameleon(DOWNLOAD_XMODEM, null, false);
        return true;
    }

}
