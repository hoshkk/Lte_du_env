# SpectrumCheck Fast 2.4.2

- Mode selection persists in ViewModel state during start/stop and configuration changes. Filled primary background, thicker border, check mark, and accessibility selected semantics identify selected controls.
- Equipment/interference mode selection is set only after successfully applying a default profile. Editing acquisition/display configuration, changing Max Hold or channel-power state clears mode selection. Loading a custom profile leaves default modes unselected.
- Added persistent selection to Max Hold, stopped state, settings tabs, band presets, tune-settle presets, RBW/VBW presets and integration bandwidth presets. Channel Power uses the same selection control.
- Band/preset choices compare actual draft values so direct numeric edits update their selected state.
- Running start button displays a measurement-in-progress label; driver opening is labelled separately.
- Momentary peak reset, marker clear, peak search, mode application and profile save/reset actions display short completion feedback.
- No acquisition/DSP or default profile value changes in this release.
- Build/unit checks are automated; visual device validation is pending.
