# 2.6.1
- Tune-only USB control transfers use synchronous usbfs CONTROL ioctl on the already authorized native duplicate fd. Request type, request, value, index, data pointer, length and timeout are passed unchanged. No PLL writes/lock reads or settling are omitted.
- ENOTTY/ENOSYS fall back to the existing libusb path. Other errors propagate as libusb codes, avoiding duplicate uncertain transfers.
- Init and FIFO/bulk paths unchanged. Claude reconnect and FFT cache retained.
- Hardware speed/accuracy comparison pending: no measured improvement claimed.
- Auto REF targets representative floor 1.5 divisions above bottom of an 8-division plot, with peak headroom. Offset preserved. Existing downward-only clipping gain policy retained.
- Explicit auto-fit button and status near settings.
- Native mock tests validate request fields, error propagation and unsupported fallback. These do not replace RF tests.
