# SpectrumCheck Fast 2.5.0 — USB direct acquisition

## What changed

The default acquisition path now opens the authorized Android USB file descriptor directly through a bundled native RTL-SDR driver. The previous external SDR Driver / localhost TCP backend remains selectable for comparison.

- Native driver based on signalwareltd/rtl_tcp_andro-, commit bf421f0d6983d665158fbc4729831ec88c244fac, including RTL-SDR Blog V4 support.
- One worker owns tuning, FIFO reset, synchronous USB reads, and close. A mutex prevents a new direct session opening before the old session has closed.
- Retune: check tuner result / PLL lock, wait the native guard, reset device FIFO, discard 4096 bytes, acquire IQ. No asynchronous USB queue, external TCP workpool or socket buffer exists in this path.
- Native guard defaults to 10 ms, first tune at least 50 ms; configurable 5–1000 ms. Physical settling and frequency correctness require hardware validation. Legacy guard remains independently configurable.
- Transfer requests are 512-byte aligned, at most 16384 bytes. Read errors, odd IQ lengths and timeouts stop acquisition rather than draw partial/wrong data.
- USB read timeout is 250 ms. Stop cancels pending permission and signals the worker; the worker closes native resources itself, avoiding close/read races.
- R82xx PLL unlock now returns an error instead of success. A second lock read has a 1 ms pause.
- USB file descriptor is duplicated for native ownership, leaving the Android connection's descriptor independent.
- EEPROM auto-bias activation is disabled in this build. RF-input bias power is kept off.
- Frequency range, FFT/RBW calculations, span segmentation, gain controls, markers and mode-selection behavior are retained. Saved format 1/2 profiles migrate with native guard 10 ms.

## Installation and first measurement

1. Update the existing SpectrumCheck Fast app.
2. Stop the external SDR Driver stream and other SDR apps. Disconnect/reconnect the dongle if another app retains USB ownership.
3. Select USB 직접, then the band and desired workflow.
4. Press 실측 시작 and allow this app to access the USB device.
5. Compare the full-sweep time at the same center/span/RBW/gain settings. Do not treat partial screen refreshes as full-sweep updates.
6. If direct USB fails, stop and select SDR Driver to return to the earlier backend. Save the actual error text for diagnosis.

## Validation limits

Native ARM64/ARMv7 compilation, automated math/data/selection tests and APK verification are performed locally. There is no connected RTL-SDR hardware in this workspace. No claim is made of a measured improvement in full-sweep time, calibrated dBm, signal-location accuracy, PIM diagnosis, or SK-equivalent acquisition rate.

The latest user video provides the baseline: center 909.3 MHz, RBW target 10 kHz, VBW off, gain 4/10; 15 MHz span about 982 ms and 25 MHz about 1528 ms. These are baseline observations, not results of this build.
