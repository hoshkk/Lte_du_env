# 2.5.2 USB recovery
- FIFO reset retries once after 5 ms only for libusb IO (-1), TIMEOUT (-7), INTERRUPTED (-10). Both reset writes are repeated before IQ reading.
- NO_DEVICE and PIPE errors do not retry. Persistent failure stops acquisition with USB code and attempt count. Recovery attempts are logged to Android logcat (SpectrumUSB).
- Both FIFO writes require exactly two bytes transferred; short positive transfers no longer count as success.
- V4 notch setting skips an unchanged successfully cached value. PLL writes and settling remain unchanged. No measured speed gain claimed for this release.
- Native host tests exercise success, transient failure, second-stage failure, bounded retries, disconnect, pipe error and short writes. Existing mux and JVM tests retained.
- Actual phone/RTL-SDR recovery and RF accuracy remain unverified. No fake trace or automatic device re-opening is used.
