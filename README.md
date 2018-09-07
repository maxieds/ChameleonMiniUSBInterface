<center>
<img src="https://github.com/maxieds/ChameleonMiniUSBInterface/blob/master/wiki-images/toast_delivery_logo_usb.png" />
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
* boolean chameleonUSBInterfaceInitialize([ChameleonLibraryLoggingReceiver](https://github.com/maxieds/ChameleonMiniUSBInterface/blob/master/chameleonminiusb/src/main/java/com/maxieds/chameleonminiusb/ChameleonLibraryLoggingReceiver.java) mainActivityHandler)
* boolean chameleonUSBInterfaceInitialize(ChameleonLibraryLoggingReceiver, [LocalLoggingLevel](https://github.com/maxieds/ChameleonMiniUSBInterface/blob/master/chameleonminiusb/src/main/java/com/maxieds/chameleonminiusb/LibraryLogging.java#L24))
* void onNewIntent(Intent)
* boolean chameleonUSBInterfaceShutdown()
* boolean chameleonPresent()
* boolean chameleonPresent([ChameleonBoardType_t](https://github.com/maxieds/ChameleonMiniUSBInterface/blob/master/chameleonminiusb/src/main/java/com/maxieds/chameleonminiusb/ChameleonDeviceConfig.java#L80))
* boolean prepareChameleonEmulationSlot(@IntRange(from=1,to=8) int slotNumber, boolean clearSlot)
* boolean prepareChameleonEmulationSlot(int, boolean, [ChameleonEmulatedConfigType_t](https://github.com/maxieds/ChameleonMiniUSBInterface/blob/master/chameleonminiusb/src/main/java/com/maxieds/chameleonminiusb/ChameleonDeviceConfig.java#L400))
* boolean chameleonUpload(InputStream)
* boolean chameleonUpload(byte[])

## Loading the library into an external Android project (Gradle and Manifest files)

### Application build.gradle snippet

We can automatically include the library into an existing Android Studio project in the usual way
using the [JitPack/IO](https://jitpack.io/#maxieds/ChameleonMiniUSBInterface/BreadCoStable-2018-09-06) mechanisms. In particular, you can include the
following snippets in your *application* (i.e., *NOT* project) **build.gradle** file:
```
dependencies {
    // ...
    //implementation 'com.github.maxieds:ChameleonMiniUSBInterface:master-SNAPSHOT'
    implementation 'com.github.maxieds:ChameleonMiniUSBInterface:BreadCoStable-2018-09-06'
}

apply plugin: 'maven'
repositories {
    // ...
    maven { url "https://maven.google.com" }
    maven { url "https://jitpack.io" }
}
```
In your *project* **build.gradle** file you will also need to include the following:
```
allprojects {
    repositories {
        // ...
        maven { url 'https://jitpack.io' }
    }
}
```

### Application Manifest.xml snippet

There are a couple of other matters to address in the external application via the project's
**Manifest.xml** file. In particular, it is best that the client application ensure the
requisite permissions needed by the library **AND** also we need to filter for the specific
Chameleon Mini USB vendor/product IDs so that the application can be invoked by the running
Android device when the Chameleon is attached over USB.
First, define the following XML resource file located at **res/xml/chameleon_usb_device_filter.xml**:
```
<?xml version="1.0" encoding="utf-8"?>

<resources>

    <!-- ChameleonMini Rev. G Board Firmware Device Specs -->
    <usb-device vendor-id="5840" product-id="1202" />

    <!-- ChameleonMini Rev. E (Rebooted) Board Firmware Device Specs -->
    <usb-device vendor-id="1003" product-id="8260" />

</resources>
```
Then the following snippets should be added to the skeleton **Manifest.xml** file for the
client project:
```
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
          package="com.YourUserName.YourPackageName">

    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:required="false"/>
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:required="false"/>
    <uses-permission android:name="android.permission.INTERNET" android:required="false"/>
    <uses-permission android:name="com.android.example.USB_PERMISSION" android:required="true"/>
    <uses-feature android:name="android.hardware.usb.host" android:required="true"/>

    <application
            android:allowBackup="true"
            android:icon="@drawable/MyIconSource"
            android:label="@string/MyAppName"
            android:roundIcon="@drawable/MyRoundIconSource"
            android:supportsRtl="true"
            android:theme="@style/MyAppTheme">

            <activity android:name=".MyMainActivity">

                <intent-filter>
                    <action android:name="android.intent.action.MAIN" />
                    <category android:name="android.intent.category.LAUNCHER" />
                </intent-filter>

                <intent-filter android:priority="1000">
                    <action android:name="android.intent.action.MAIN" />
                    <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
                    <action android:name="android.hardware.usb.action.USB_DEVICE_DETACHED" />
                    <category android:name="android.intent.category.DEFAULT" />
                </intent-filter>

                <meta-data android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                    android:resource="@xml/chameleon_usb_device_filter"/>
                <meta-data android:name="android.hardware.usb.action.USB_DEVICE_DETACHED"
                    android:resource="@xml/chameleon_usb_device_filter"/>

            </activity>

        </application>

</manifest>
```

### Taks in the local main Activity onCreate(...) implementation

There are also a few conditions and quirks that need to be addressed in the implementation of your
main external client's main Activity class. First, the definition of the activity should resemble the
following:
```
public class DemoActivity extends AppCompatActivity implements ChameleonLibraryLoggingReceiver {

    private static final String TAG = DemoActivity.class.getSimpleName();

     public void onReceiveNewLoggingData(Intent intentLog) {
         // ...
     }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        if(!isTaskRoot()) {
            final Intent intent = getIntent();
            final String intentAction = intent.getAction();
            if (intentAction != null && (intentAction.equals(UsbManager.ACTION_USB_DEVICE_DETACHED) || intentAction.equals(UsbManager.ACTION_USB_DEVICE_ATTACHED))) {
                finish();
                return;
            }
            setContentView(R.layout.activity_demo_layout);
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_NOSENSOR);

        // ...

        String[] permissions = {
             "android.permission.READ_EXTERNAL_STORAGE",
             "android.permission.WRITE_EXTERNAL_STORAGE",
             "android.permission.INTERNET",
             "com.android.example.USB_PERMISSION",
        };
        ActivityCompat.requestPermissions(this, permissions, 0);

        if(!ChameleonDeviceConfig.usbReceiversRegistered) {
            BroadcastReceiver usbActionReceiver = new BroadcastReceiver() {
                @RequiresPermission("com.android.example.USB_PERMISSION")
                public void onReceive(Context context, Intent intent) {
                    if (intent.getAction() != null && (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED) || intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_DETACHED))) {
                        onNewIntent(intent);
                    }
                 }
            };
            IntentFilter usbActionFilter = new IntentFilter();
            usbActionFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            usbActionFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            registerReceiver(usbActionReceiver, usbActionFilter);
        }

        (new ChameleonDeviceConfig()).chameleonUSBInterfaceInitialize(this, LibraryLogging.LocalLoggingLevel.LOG_ADB_VERBOSE);
         if(ChameleonDeviceConfig.THE_CHAMELEON_DEVICE.chameleonPresent()) {
             LibraryLogging.i(TAG, "The chameleon device is connected! :)");
             LibraryLogging.i(TAG, String.join("\n", getChameleonMiniUSBDeviceParams()));
          }
          else {
             LibraryLogging.i(TAG, "Unable to connect to chameleon device :(");
          }

          // ...
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String intentAction = intent.getAction();
        if(intentAction == null) {
            return;
        }
        if(intentAction.equals("ACTION_USB_DEVICE_ATTACHED") || intentAction.equals("ACTION_USB_DEVICE_DETACHED")) {
                ChameleonDeviceConfig.THE_CHAMELEON_DEVICE.onNewIntent(intent);
        }
        // ...
    }

    // ...
}
```

## Sample demo application

A working demo program which shows how to use and integrate this library into an existing Android application
is provided in [this repository (BreadCoSampleApp)](https://github.com/maxieds/BreadCoSampleApp). Any other
questions about the usage of the library or requests for future functionality can be directed at the
developer over email at [maxieds@gmail.com](mailto:maxieds@gmail.com).


