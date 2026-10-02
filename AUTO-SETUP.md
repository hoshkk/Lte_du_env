# 2.6.0 accuracy-first initial setup
Main mode buttons apply the existing mode preset and start measurement. Two complete sweeps are examined per pass.
Only demonstrated ADC clipping (>0.1% sampled IQ components) permits lowering gain, one step per pass, up to three changes. Weak-looking traces do not raise gain. No guarantee against analog front-end compression.
After adjustment REF and dB/div fit observed peak and 10th-percentile floor; gain and display remain fixed. Persistent clipping at minimum gain or retry bound is explicitly flagged.
USB direct supports automatic gain reductions through bounded restart. External driver gets display fitting only and clipping warning (no restart of external server).
OFFSET is preserved across mode preset application. RF center/span and preset RBW/VBW remain fixed during observation. No absolute dBm calibration or fault diagnosis claimed.
Claude reconnection/FFT cache and full tune profiling retained.
Planned PLL write-skipping was REVERTED due to accuracy-first requirement; this release makes no speed improvement claim.
Tests exercise no gain increase, bounded clipping reduction, offset preservation and display scale.
