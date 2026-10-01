# SpectrumCheck Fast 2.4.3

- Equipment/interference selection now represents the chosen workflow, and remains selected when Max Hold, Ch Power, gain, frequencies, bandwidths, Ref/Offset or other settings change.
- Loading an explicitly named saved profile selects its mode. Start/stop and marker controls preserve selection.
- Driver launch uses the documented address/port/sample-rate intent. Startup frequency is configured by the existing TCP acquisition path, not duplicated in the launch intent.
- On argument error (driver error code 1 or Wrong arguments text), retry once with address and port only. Other errors/cancellation do not trigger this retry. A failed retry is surfaced to the user.
- The reported recording shows driver argument rejection, before IQ acquisition. The exact difference in the installed driver's parser is unconfirmed; hardware recovery has not been validated.
- Default profile values and sweep speed are unchanged.
