# Bundled source and notices

This distribution includes GPL-licensed RTL-SDR code and LGPL-licensed libusb code from:
https://github.com/signalwareltd/rtl_tcp_andro-
Commit: bf421f0d6983d665158fbc4729831ec88c244fac

The complete bundled native source, original copyright headers, and license texts are under app/src/main/cpp/librtlsdr/ (COPYING and libusb/COPYING). The source archive accompanying this APK includes the app and native modifications and build configuration. NDK 27.2.12479018, CMake 3.22.1, Android SDK 34, Gradle 8.7, Java 17 are used.

Native modifications in this version: bounded synchronous read timeout; checked endpoint reset; native USB fd duplication; open/EEPROM error checks; disabled EEPROM automatic bias activation; failed PLL lock returns error, with a brief retry pause. spectrum_usb.c and NativeUsbSource.kt provide the app bridge. No signing key is included.

The combined application source in this distribution is supplied under GPL-2.0-or-later, with applicable third-party licenses retained. See app/src/main/cpp/librtlsdr/COPYING for the GPL text. This software is provided without warranty.
