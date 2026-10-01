# 2.5.3 USB discovery
- Poll fresh Android USB enumeration up to 13 times over 3 seconds at startup. Stop/cancellation remains supported. No delay when a supported device is present.
- Distinguish no enumerated USB device from unsupported VID/PID and multiple supported devices.
- Android rtlsdr_open2 no longer calls libusb_reset_device after a failed initial control write. A reset can invalidate Android-owned enumeration/fd; opening now fails cleanly with its error code. This is a potential failure path, not a confirmed cause of the reported missing device.
- Preserve acquisition bandwidth, sample rate, settling, RF tuning, gain, RBW and VBW. This release does not claim faster sweeps.
- Regression tests cover immediate and delayed discovery, bounded absence, unsupported IDs, multiple devices and cancellation.
- Hardware validation remains required. Cannot recover a dongle absent from Android USB enumeration by software alone.
