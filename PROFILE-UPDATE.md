# SpectrumCheck Fast 2.4.1

- Removed DEMO UI, simulation source, and simulation execution path.
- Equipment profile: fixed gain 1/10, RBW target 100 kHz, VBW off, Max Hold off, channel power on.
- Antenna interference profile: fixed gain 4/10 (19.7 dB tuner command), RBW target 10 kHz, VBW off, Max Hold on, channel power off.
- Both profiles: Ref -20, Offset 0 dB, 10 dB/div, AGC off, DC removal off, tune guard 80 ms.
- Mode buttons preserve frequency, span, and valid integration bandwidth. Applied in main screen and settings dialog. Applying stops acquisition and clears traces; use live start to resume.
- Mode buttons always apply baseline profiles. Saved custom profiles remain accessible through explicit load actions in profile management.
- Profiles are starting configurations, not measured RF optimization or absolute dBm calibration. RX MON attenuation is unknown and is not guessed. Low gain is not overload protection.
- Acquisition backend is unchanged from 2.4.0. Native USB integration and sweep latency improvements are not included in this update.
- Hardware validation pending; no claim of SK-equivalent refresh rate.
