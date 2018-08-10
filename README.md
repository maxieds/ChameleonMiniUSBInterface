<center>
<img src="https://github.com/maxieds/ChameleonMiniUSBInterface/blob/master/wiki-images/toast_delivery_logo_usb.png" />
<img src="https://github.com/maxieds/ChameleonMiniUSBInterface/blob/master/wiki-images/chameleonusb64.png" />
</center>

# ChameleonMiniUSBInterface library

## Introduction and core interface

Much like the developer's code for the [Chameleon Mini Live Debugger](https://github.com/maxieds/ChameleonMiniLiveDebugger) 
Android application which provides a GUI-based mechanism for controlling and displaying live logs from the 
[RevG](https://rawgit.com/emsec/ChameleonMini/master/Doc/Doxygen/html/_page__command_line.html) and 
[RevE (Rebooted)](https://github.com/iceman1001/ChameleonMini-rebooted/wiki/Terminal-Commands) Chameleon Mini devices, 
this library also offers now external mechanisims for controlling these boards over the Android USB stack. 
The core functionality of the library is intended to upload binary images to the Chameleon devices via XModem 
which represent injested dumps of real-world 
[supported NFC tags](https://github.com/iceman1001/ChameleonMini-rebooted/wiki/Configurations) (see also a complete 
list of NFC tag configurations which can be built-in to the firmware of either revision 
[in this source file](https://github.com/iceman1001/ChameleonMini-rebooted/blob/master/Firmware/Chameleon-Mini/Configuration.h#L20). 

### The core interface

The core of this library is focused on the Java interface for performing the tasks described above outlined 
[in this local source file](https://github.com/maxieds/ChameleonMiniUSBInterface/blob/master/chameleonminiusb/src/main/java/com/maxieds/chameleonminiusb/ChameleonInterfaceLibrary.java). The JavaDoc comments in the linked source file are 
probably the most descriptive sources of information for how the interface operates. However, we also list the 
core functions below:
* boolean chameleonUSBInterfaceInitialize(Activity)
* boolean chameleonUSBInterfaceInitialize(Activity, [LocalLoggingLevel](https://github.com/maxieds/ChameleonMiniUSBInterface/blob/master/chameleonminiusb/src/main/java/com/maxieds/chameleonminiusb/LibraryLogging.java#L24))
* boolean chameleonUSBInterfaceShutdown()
* boolean chameleonPresent()
* boolean chameleonPresent([ChameleonBoardType_t](https://github.com/maxieds/ChameleonMiniUSBInterface/blob/master/chameleonminiusb/src/main/java/com/maxieds/chameleonminiusb/ChameleonDeviceConfig.java#L80))
* boolean prepareChameleonEmulationSlot(@IntRange(from=1,to=8) int slotNumber, boolean clearSlot)
* boolean prepareChameleonEmulationSlot(int, boolean, [ChameleonEmulatedConfigType_t](https://github.com/maxieds/ChameleonMiniUSBInterface/blob/master/chameleonminiusb/src/main/java/com/maxieds/chameleonminiusb/ChameleonDeviceConfig.java#L400))
* boolean chameleonUpload(byte[])
* boolean chameleonUpload(InputStream)

### Other core examples of usage of the library

The workhorse of the package is undoubtedly the file [ChameleonDeviceConfig.java](https://github.com/maxieds/ChameleonMiniUSBInterface/blob/master/chameleonminiusb/src/main/java/com/maxieds/chameleonminiusb/ChameleonDeviceConfig.java), 
which in addition to implementing the interface defined in [ChameleonInterfaceLibrary.java](https://github.com/maxieds/ChameleonMiniUSBInterface/blob/master/chameleonminiusb/src/main/java/com/maxieds/chameleonminiusb/ChameleonInterfaceLibrary.java) from above, also contains much other functionality with respect to 
interacting with and communicating with the Chameleon Mini RevE and RevG devices. The following is a sketch of the topical layout of the source code in this file:
* **Determining the Revision of the Chameleon Device Firmware:** Starting [here](https://github.com/maxieds/ChameleonMiniUSBInterface/blob/master/chameleonminiusb/src/main/java/com/maxieds/chameleonminiusb/ChameleonDeviceConfig.java#L89). Once the library is configured, the user can call either of 
*boolean isRevisionEDevice()* or *boolean isRevisionGDevice()* to determine which firmware variant you are working with. The function 
*String[] getChameleonMiniUSBDeviceParams()* effectively translates all of the properties (core at least to the lass detailed RevE Rebooted firmware) of the device:
```
chameleonUSBInterfaceInitialize(mainApplicationActivityRef, LibraryLogging.LocalLoggingLevel.LOG_ADB_VERBOSE);

// ... plug in the device, wait to be notified it is recognized (see below for a complete example) ...
while(!ChameleonDeviceConfig.isRevisionEDevice() && !ChameleonDeviceConfig.isRevisionGDevice()) {
try {
	Thread.sleep(50);
} catch (InterruptedException ie) {}
LibraryLogging.i(TAG, String.join("\n", getChameleonMiniUSBDeviceParams()); 

// Now set the second slot to a MF_CLASSIC_4K, set a semi-random UID for it, and then make the slot immutable:
prepareChameleonEmulationSlot(2, true, ChameleonEmulatedConfigType_t.MF_CLASSIC_4K);
changeChameleonUID(ChameleonUIDTypeSpec_t.PREFIXED_RANDOMIZED, ChameleonDeviceConfig.chameleonUIDPrefixBytes);
ChameleonCommandResult setROResult = sendCommandToChameleon(SET_READONLY, 1);
LibraryLogging.d(TAG, setROResult.toString());
```
* **Technical handling of the serial USB communications:** Starts [here](https://github.com/maxieds/ChameleonMiniUSBInterface/blob/master/chameleonminiusb/src/main/java/com/maxieds/chameleonminiusb/ChameleonDeviceConfig.java#L133). The *UsbReaderCallback* function which actually handles all of the RX data received back from the 
Chameleon device happens [here](https://github.com/maxieds/ChameleonMiniUSBInterface/blob/master/chameleonminiusb/src/main/java/com/maxieds/chameleonminiusb/ChameleonDeviceConfig.java#L235) for user perusal.
* **Sending arbitrary commands to the Chameleon:** The core functions start [here](https://github.com/maxieds/ChameleonMiniUSBInterface/blob/master/chameleonminiusb/src/main/java/com/maxieds/chameleonminiusb/ChameleonDeviceConfig.java#L313) and the translations of the supported commands are defined in [ChameleonCommands.java](https://github.com/maxieds/ChameleonMiniUSBInterface/blob/master/chameleonminiusb/src/main/java/com/maxieds/chameleonminiusb/ChameleonCommands.java):
```
// query the current UID (or UID sizes by GET_UID_SIZE) for both revisions of the device firmware:
String[] uidSizeCmds = getCommandFormatString(QUERY_UID);
if(isRevisionEDevice()) {
    Log.i(TAG, sendRawStringToChameleon(uidSizeCmds[0]).toString());
}
else {
	Log.i(TAG, sendRawStringToChameleon(uidSizeCmds[1]).cmdResponseData);
}

// set the configuration to something semi-exotic on a RevG device:
String setConfigCmdFormat = getCommandFormatString(SET_CONFIG)[1];
LibraryLogging.v(TAG, sendFormattedCommandToChameleon(setConfigCmdFormat, ChameleonEmulatedConfigType_t.MF_ULTRALIGHT_EV1_164B.name()));
```
* **Chameleon USB Interface implementation:** Starts [here](https://github.com/maxieds/ChameleonMiniUSBInterface/blob/master/chameleonminiusb/src/main/java/com/maxieds/chameleonminiusb/ChameleonDeviceConfig.java#L439). 

## Loading the library into an external Android project (Gradle and Manifest files) [WITH WORKING SOURCE CODE EXAMPLES]


## Other features in the library

### Setting up real-time views of verbose logging features 


### Configuring other library behaviors and features


## Other suggestions for uses of the library on Android 


