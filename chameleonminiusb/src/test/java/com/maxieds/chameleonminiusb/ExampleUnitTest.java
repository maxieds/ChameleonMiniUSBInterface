package com.maxieds.chameleonminiusb;

import org.junit.Test;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChameleonLibraryUnitTests {

    private boolean chameleonDeviceIsConfigured = false;
    private com.maxieds.chameleonminiusb.ChameleonDeviceConfig cdevConfig;

    @BeforeClass
    public static void setupChameleonUSBConnection() {

        //com.maxieds.chameleonminiusb.ChameleonMiniUSBActivity.startActivityForResult()

    }

    public boolean configureChameleonDevice() {
        if(chameleonDeviceIsConfigured) {
            return true;
        }
        // TODO
        chameleonDeviceIsConfigured = true;
        return true;
    }

    @Before
    public void checkChameleonConfigIsValid() {
        if(!chameleonDeviceIsConfigured) {
            assertTrue(configureChameleonDevice());
        }
        assertTrue(cdevConfig.isValid());
    }

    @Test
    public void testSetChameleonUIDs() {
        assertTrue(true);
        // Test explicit UID
        // Test random UID
        // Test increment by one UID
    }

    private static byte[] MifareExampleDump1 = {(byte) 0x00};
    private static byte[] MifareExampleDump2 = {(byte) 0x00};
    private static byte[] MifareExampleDump3 = {(byte) 0x00};

    @Test
    public void testLoadMifareDump1() {
        assertTrue(true);
    }

    @Test
    public void testLoadMifareDump2() {
        assertTrue(true);
    }

    @Test
    public void testLoadMifareDump3() {
        assertTrue(true);
    }




}