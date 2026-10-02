# 2.6.3 USB permission handling

- Register USB_DEVICE_ATTACHED for RTL2832/RTL2838 (VID 0x0bda, PID 0x2832/0x2838).
- Allow Android to offer this application as the USB handler. Default selection, if available, is made by the user/system; the application does not grant itself permission.
- singleTask + onNewIntent preserve the current activity/session during attach delivery.
- Recheck hasPermission immediately before requestPermission.
- A declined USB grant terminates this measurement attempt instead of repeating the permission dialog through the USB fault retry loop. A new manual start can ask again.
- Existing authorized sessions skip the request as before. Actual detach/re-enumeration may require another system grant.
- Native acquisition, PLL optimization, settling, DSP and field settings unchanged from 2.6.2.

Validation: Gradle unit tests/build and packaged manifest/resource verification. Physical Fold8 attach/default-handler behavior still requires device validation.
