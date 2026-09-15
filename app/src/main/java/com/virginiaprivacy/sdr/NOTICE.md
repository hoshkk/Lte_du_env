# Vendored code notice

The Kotlin sources under this `com.virginiaprivacy.sdr` package tree are vendored,
with modifications, from the Virginia Privacy Coalition's `sdr` project
(RTL2832U / R820T tuner control library for the JVM/Android):

https://github.com/virginiaprivacycoalition/sdr

Licensed under the GNU General Public License v2.0 (see `LICENSE.txt` in this
directory). Because this code is compiled into the app, the app as a whole is
subject to GPL-2.0 obligations if it is ever distributed outside this
organization — see the project README for a plain-language summary.

## Modifications made when vendoring

- Removed support for the Elonics E4000 tuner (`TunerType.ELONICS_E4000`,
  `TunerTypeCheck.E4K`, and the corresponding branches in
  `RTL2832TunerController`) to keep the vendored surface small; only the
  Rafael Micro R820T/R828D tuner family (used by essentially all current
  RTL-SDR dongles) is supported. The original project's `E4KTunerController`
  and its `tuner/e4k/*` support classes were not vendored.
- Did not vendor the original project's `androidusb` USB-glue module
  (`AndroidUsbController`), `Receiver.kt`, or `TunerClass.kt` — they were
  either unused by the pieces we vendor or (in `AndroidUsbController`'s case)
  did not compile against this snapshot of `sdr` (its `sampleAdapter`
  property did not override the base class's abstract member, and its call
  to `ByteToFloatSampleAdapter.convert()` did not match that method's
  signature). The Android-side USB glue in this app
  (`com.lteduenv.spectrum.data.sdr.RtlSdrUsbController`) is written from
  scratch against the `UsbController` abstract class here instead.
