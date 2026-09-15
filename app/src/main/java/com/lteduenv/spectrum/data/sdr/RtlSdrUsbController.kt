package com.lteduenv.spectrum.data.sdr

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import com.virginiaprivacy.sdr.adapters.ByteToFloatSampleAdapter
import com.virginiaprivacy.sdr.adapters.ISampleAdapter
import com.virginiaprivacy.sdr.exceptions.DeviceException
import com.virginiaprivacy.sdr.tuner.RTL2832TunerController
import com.virginiaprivacy.sdr.usb.UsbController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.isActive
import java.nio.ByteBuffer

/**
 * [UsbController] implementation for a plain RTL2832U dongle reached through Android's USB host
 * APIs (no root, no NDK/libusb). Written against the vendored [com.virginiaprivacy.sdr] classes
 * in this package tree - see NOTICE.md there for why this isn't the upstream project's own
 * Android glue class.
 *
 * The caller (see [UsbSdrDataSource]) is responsible for obtaining USB permission for [device]
 * before constructing this.
 */
class RtlSdrUsbController(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
) : UsbController() {

    override var deviceOpened: Boolean = false
    override var sampleAdapter: ISampleAdapter<*> = ByteToFloatSampleAdapter()

    private lateinit var connection: UsbDeviceConnection
    private val usbInterface by lazy { device.getInterface(0) }
    private val bulkInEndpoint: UsbEndpoint by lazy {
        (0 until usbInterface.endpointCount)
            .map { usbInterface.getEndpoint(it) }
            .first { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
    }

    @Volatile
    private var streaming = false

    override fun open() {
        connection = usbManager.openDevice(device) ?: throw DeviceException("Could not open USB device.")
        deviceOpened = true
    }

    override fun close() {
        streaming = false
        if (::connection.isInitialized) {
            connection.close()
        }
    }

    override fun write(value: Short, index: Short, buffer: ByteBuffer): Int {
        buffer.rewind()
        val buf = ByteArray(buffer.remaining())
        buffer.get(buf)
        buffer.rewind()
        return connection.controlTransfer(
            RTL2832TunerController.CONTROL_ENDPOINT_OUT.toInt(),
            RTL2832TunerController.REQUEST_ZERO.toInt(),
            value.toInt(),
            index.toInt(),
            buf,
            buf.size,
            250,
        )
    }

    override fun read(address: Short, index: Short, buffer: ByteBuffer): Int {
        val buf = ByteArray(buffer.capacity())
        val transferred = connection.controlTransfer(
            RTL2832TunerController.CONTROL_ENDPOINT_IN.toInt(),
            RTL2832TunerController.REQUEST_ZERO.toInt(),
            address.toInt(),
            index.toInt(),
            buf,
            buf.size,
            250,
        )
        buffer.rewind()
        buffer.put(buf)
        buffer.rewind()
        return transferred
    }

    /** Emits raw byte chunks read from the bulk IN endpoint until [stop] is called. */
    override fun start(): ReceiveChannel<FloatArray> {
        controller.resetUSBBuffer()
        streaming = true
        return CoroutineScope(Dispatchers.IO).produce {
            val chunkSize = 16384
            val raw = ByteArray(chunkSize)
            while (isActive && streaming) {
                val n = connection.bulkTransfer(bulkInEndpoint, raw, chunkSize, 2000)
                if (n > 0) {
                    val floats = FloatArray(n)
                    for (i in 0 until n) {
                        // Matches ByteToFloatSampleAdapter's mapping: unsigned byte -> roughly -1..1.
                        floats[i] = ((raw[i].toInt() and 0xff) - 127).toFloat() / 128.0f
                    }
                    send(floats)
                }
            }
        }
    }

    override fun stop() {
        streaming = false
    }

    override fun claimInterface(interfaceNumber: Int): Int =
        if (connection.claimInterface(device.getInterface(interfaceNumber), true)) 0 else 1

    override fun releaseInterface(interfaceNumber: Int): Int {
        connection.releaseInterface(usbInterface)
        return 0
    }

    override fun release(interfaceNumber: Int): Int {
        connection.releaseInterface(usbInterface)
        return 0
    }

    override fun kernelDriverActive(interfaceNumber: Int): Boolean = false

    override fun detachKernelDriver(interfaceNumber: Int) {
        // Android's claimInterface(..., true) already forces this; nothing to do.
    }

    override fun getErrorMessage(errorCode: Int): String = "USB error (code $errorCode)"

    override fun handleEventsTimeout(): Int = 0

    override fun resetDevice() {
        // Not exposed by the public Android USB API; callers fall back to re-opening the device.
    }
}
