# 2.5.5 reconnect + profiling
Restore Claude's bounded full reconnect loop and Hann-window cache on top of v2.5.4 profiling.
Keep all 14 timing fields, native instrumentation, CSV output, v2.5.3 discovery and v2.5.2 FIFO retry.
Add UI callback while reconnecting: current trace is explicitly labeled as previous measurement.
Callbacks are ignored after stop/new session by the ViewModel generation guard.
Original reconnect semantics preserved: five retries with one second delay, reset counter after a full sweep, cancellation always rethrown.
No simulated source merged. No RF tuning sequence or speed claim added.
Hardware reconnection remains unverified.
