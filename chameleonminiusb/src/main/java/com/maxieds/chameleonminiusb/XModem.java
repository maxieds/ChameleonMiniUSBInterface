package com.maxieds.chameleonminiusb;

import android.app.DownloadManager;
import android.os.Handler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static android.content.Context.DOWNLOAD_SERVICE;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.QUERY_READONLY;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.SET_READONLY;
import static com.maxieds.chameleonminiusb.ChameleonCommands.StandardCommandSet.UPLOAD_XMODEM;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.DOWNLOAD;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.IDLE;
import static com.maxieds.chameleonminiusb.ChameleonDeviceConfig.SerialUSBStates.UPLOAD;
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
    public static int fileSize = 0;
    public static FileOutputStream streamDest;
    public static InputStream streamSrc;
    public static File outfile;
    public static byte CurrentFrameNumber;
    public static byte Checksum;
    public static int currentNAKCount;
    public static boolean transmissionErrorOccurred;
    public static int uploadState;
    public static byte[] uploadFramebuffer = new byte[XMODEM_BLOCK_SIZE + 4];
    public static boolean initiallyReadOnly;

    /**
     * Completes the XModem download command. Implemented this way to keep the GUI from
     * freezing waiting for the command to complete by while loop / Thread.sleep.
     * @ref ExportTools.downloadByXModem
     * @ref ExportTools.performXModemSerialDownload
     */
    public static Runnable eotSleepRunnable = new Runnable() {

        public void run() {
            if (XModem.EOT) {
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
                    DownloadManager downloadManager = (DownloadManager) ChameleonDeviceConfig.mainApplicationActivity.getSystemService(DOWNLOAD_SERVICE);
                    downloadManager.addCompletedDownload(outfile.getName(), outfile.getName(), true, "application/octet-stream",
                            outfile.getAbsolutePath(), outfile.length(), true);
                    LibraryLogging.i(TAG, "XModem routine completed writing file to \"" + outfile.getName() + "\"");
                }
                else {
                    outfile.delete();
                    LibraryLogging.e(TAG, "ERROR: Maximum number of NAK errors exceeded. Download of data aborted.");
                }
            }
            else if(ChameleonDeviceConfig.serialUSBState.compareTo(UPLOAD) == 0) {
                LibraryLogging.i(TAG, "Cleaning up after XModem UPLOAD ...");
                try {
                    streamSrc.close();
                } catch (Exception ioe) {
                    LibraryLogging.e(TAG, ioe.getMessage() + "\n" + ioe.getStackTrace());
                } finally {
                    ChameleonDeviceConfig.serialUSBState = IDLE;
                    if(XModem.initiallyReadOnly) {
                        ChameleonDeviceConfig.sendCommandToChameleon(SET_READONLY, 1);
                    }
                    ChameleonDeviceConfig.serialPortLock.release();
                }
                if(XModem.transmissionErrorOccurred) {
                    LibraryLogging.e(TAG, "File transmission errors encountered. Maximum number of NAK errors exceeded. Download of data aborted.");
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
        LibraryLogging.d(TAG, "Received Upload Data (#=" + liveLogData.length + ") ... " + Utils.byteArrayToString(liveLogData));
        LibraryLogging.d(TAG, "    => " + Utils.bytes2Ascii(liveLogData));
        if(XModem.EOT || liveLogData == null || liveLogData.length == 0)
            return;
        byte statusByte = liveLogData[0];
        if(uploadState == 0 || uploadState == 1 && statusByte == BYTE_ACK) {
            if(uploadState == 1)
                CurrentFrameNumber++;
            else
                uploadState = 1;
            uploadFramebuffer[0] = BYTE_SOH;
            uploadFramebuffer[1] = CurrentFrameNumber;
            uploadFramebuffer[2] = (byte) (255 - CurrentFrameNumber);
            byte[] payloadBytes = new byte[XMODEM_BLOCK_SIZE];
            try {
                if(streamSrc.available() == 0) {
                    LibraryLogging.d(TAG, "Upload / Sending EOT to device.");
                    EOT = true;
                    ChameleonDeviceConfig.serialPort.write(new byte[]{BYTE_EOT});
                    return;
                }
                streamSrc.read(payloadBytes, 0, XMODEM_BLOCK_SIZE);
                System.arraycopy(payloadBytes, 0, uploadFramebuffer, 3, XMODEM_BLOCK_SIZE);
            } catch(IOException ioe) {
                EOT = true;
                transmissionErrorOccurred = true;
                ChameleonDeviceConfig.serialPort.write(new byte[]{BYTE_CAN});
                return;
            }
            uploadFramebuffer[XMODEM_BLOCK_SIZE + 3] = CalcChecksum(payloadBytes, XMODEM_BLOCK_SIZE);
            LibraryLogging.d(TAG, "Upload Writing Data: frame=" + CurrentFrameNumber + ": " + Utils.byteArrayToString(uploadFramebuffer));
            ChameleonDeviceConfig.serialPort.write(uploadFramebuffer);
        }
        else if(statusByte == BYTE_NAK && currentNAKCount <= MAX_NAK_COUNT) {
            LibraryLogging.d(TAG, "Upload / Sending Another NAK response (#=" + currentNAKCount + ")");
            currentNAKCount++;
            ChameleonDeviceConfig.serialPort.write(uploadFramebuffer);
        }
        else {
            EOT = true;
            transmissionErrorOccurred = true;
            ChameleonDeviceConfig.serialPort.write(new byte[]{BYTE_CAN});
            return;
        }
    }

    /**
     * Called to initiate the card data upload process.
     * @param cardInputStream
     * @ref LiveLoggerActivity.actionButtonUploadCard
     */
    public static void uploadCardFileByXModem(InputStream cardInputStream) {
        if(ChameleonDeviceConfig.serialPort == null || cardInputStream == null)
            return;
        streamSrc = cardInputStream;
        ChameleonDeviceConfig.serialPortLock.acquireUninterruptibly();
        ChameleonDeviceConfig.serialUSBState = WAITING_FOR_XMODEM_UPLOAD;
        try {
            initiallyReadOnly = (Integer.parseInt(ChameleonDeviceConfig.sendCommandToChameleon(QUERY_READONLY, null).cmdResponseData) == 0);
        } catch(Exception nfe) {
            initiallyReadOnly = false;
        }
        ChameleonDeviceConfig.sendCommandToChameleon(SET_READONLY, 0);
        ChameleonDeviceConfig.sendCommandToChameleon(UPLOAD_XMODEM, null);
        fileSize = 0;
        CurrentFrameNumber = FIRST_FRAME_NUMBER;
        currentNAKCount = -1;
        transmissionErrorOccurred = false;
        uploadState = 0;
        EOT = false;
        while(ChameleonDeviceConfig.serialUSBState.compareTo(WAITING_FOR_XMODEM_UPLOAD) == 0) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {}
        }
        ChameleonDeviceConfig.serialUSBState = UPLOAD;
        ChameleonDeviceConfig.serialPort.write(new byte[]{BYTE_NAK});
        eotSleepHandler.postDelayed(eotSleepRunnable, 50);
    }

}
