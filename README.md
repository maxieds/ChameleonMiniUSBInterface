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

## Loading the library into an external Android project (Gradle and Manifest files)


## Other features in the library

### Setting up real-time views of verbose logging features 


### Configuring other library behaviors and features


## Other suggestions for uses of the library on Android 


