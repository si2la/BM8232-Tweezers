# BM8232-Tweesers

The open project "BM8232 Tweezers" was created to support a portable RLC-meter, authored by Russian engineer A. Byvshikh.
The Android application allows you to receive data via USB-host (OTG) from the RLC meter and change its operating modes.
Based on the code from Kai Morich project [SimpleUSBTerminal](https://github.com/kai-morich/SimpleUsbTerminal).


## SimpleUsbTerminal

As the SimpleUsbTerminal this Android app provides a job with BM8232-Tweesers devices connected thru serial / UART interface.

It supports USB to serial converters based on
- Qinheng CH340, CH341

### Features from SimpleUsbTerminal

- permission handling on device connection
- foreground service to buffer receive data while the app is rotating, in background, ...

## Credits

The app uses the [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) library and code from
[Simple USB Terminal](https://github.com/kai-morich/SimpleUsbTerminal) Kai Morich app.
