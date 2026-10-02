package com.lteduenv.rxcheck.core.usb

import java.io.IOException

/**
 * Minimal USB access the RTL2832U driver needs. The Android app implements it
 * with UsbDeviceConnection; tests implement it with a register-level fake.
 *
 * All control transfers are vendor requests (bRequest 0) to the device.
 * Return values follow UsbDeviceConnection: bytes transferred, or < 0 on error.
 */
interface UsbIo {
    fun controlOut(value: Int, index: Int, data: ByteArray, length: Int = data.size): Int
    fun controlIn(value: Int, index: Int, buffer: ByteArray, length: Int): Int
    fun bulkIn(buffer: ByteArray, length: Int, timeoutMs: Int): Int
    val manufacturer: String?
    val product: String?
}

class UsbIoException(message: String) : IOException(message)
