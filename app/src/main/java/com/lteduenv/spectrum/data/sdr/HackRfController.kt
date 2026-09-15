package com.lteduenv.spectrum.data.sdr

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.isActive
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Talks directly to a HackRF One over Android's USB host APIs (no root, no NDK, no libusb).
 *
 * The protocol here (vendor request numbers, control-transfer payload layouts, the RX bulk
 * endpoint, and the signed-8-bit interleaved I/Q sample format) was read out of the real
 * open-source host driver - github.com/greatscottgadgets/hackrf,
 * host/libhackrf/src/{hackrf.c,hackrf.h} - rather than guessed, since HackRF's own protocol is
 * simple enough (a handful of USB control transfers) that it doesn't need a vendored dependency
 * the way the RTL2832U tuner chip's register-level protocol did.
 */
class HackRfController(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
) {
    private lateinit var connection: UsbDeviceConnection
    private val usbInterface by lazy { device.getInterface(0) }
    private val rxEndpoint: UsbEndpoint by lazy {
        (0 until usbInterface.endpointCount)
            .map { usbInterface.getEndpoint(it) }
            .first { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
    }

    @Volatile
    private var streaming = false

    fun open() {
        connection = usbManager.openDevice(device)
            ?: error("Could not open HackRF USB device.")
        connection.claimInterface(usbInterface, true)
    }

    fun close() {
        streaming = false
        if (::connection.isInitialized) {
            runCatching { setTransceiverMode(TRANSCEIVER_MODE_OFF) }
            connection.releaseInterface(usbInterface)
            connection.close()
        }
    }

    /** freqHz must be within the HackRF's ~1 MHz - 6 GHz tuning range. */
    fun setFrequency(freqHz: Long) {
        val freqMhz = (freqHz / 1_000_000L).toInt()
        val remainderHz = (freqHz - freqMhz.toLong() * 1_000_000L).toInt()
        val payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(freqMhz)
            .putInt(remainderHz)
            .array()
        controlOut(VENDOR_REQUEST_SET_FREQ, 0, 0, payload)
    }

    /** Fixed at [SAMPLE_RATE_HZ] (8 Msps) with a matched baseband filter, like libhackrf's default. */
    fun setSampleRate() {
        val payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(SAMPLE_RATE_HZ)
            .putInt(1) // divider
            .array()
        controlOut(VENDOR_REQUEST_SAMPLE_RATE_SET, 0, 0, payload)
        controlOut(
            VENDOR_REQUEST_BASEBAND_FILTER_BANDWIDTH_SET,
            BASEBAND_FILTER_BW_HZ and 0xffff,
            BASEBAND_FILTER_BW_HZ ushr 16,
        )
    }

    /** 0-40 dB in 8 dB steps. */
    fun setLnaGain(gainDb: Int) {
        val value = gainDb.coerceIn(0, 40) and 0x07.inv()
        controlIn(VENDOR_REQUEST_SET_LNA_GAIN, 0, value, 1)
    }

    /** 0-62 dB in 2 dB steps. */
    fun setVgaGain(gainDb: Int) {
        val value = gainDb.coerceIn(0, 62) and 0x01.inv()
        controlIn(VENDOR_REQUEST_SET_VGA_GAIN, 0, value, 1)
    }

    fun setAmpEnable(enabled: Boolean) {
        controlOut(VENDOR_REQUEST_AMP_ENABLE, if (enabled) 1 else 0, 0)
    }

    private fun setTransceiverMode(mode: Int) {
        controlOut(VENDOR_REQUEST_SET_TRANSCEIVER_MODE, mode, 0)
    }

    /** Emits raw signed-8-bit interleaved I/Q byte chunks from the RX bulk endpoint. */
    fun startRx(): ReceiveChannel<ByteArray> {
        setTransceiverMode(TRANSCEIVER_MODE_RECEIVE)
        streaming = true
        return CoroutineScope(Dispatchers.IO).produce {
            val chunkSize = 262144 // matches libhackrf's TRANSFER_BUFFER_SIZE
            val raw = ByteArray(chunkSize)
            while (isActive && streaming) {
                val n = connection.bulkTransfer(rxEndpoint, raw, chunkSize, 2000)
                if (n > 0) {
                    send(raw.copyOf(n))
                }
            }
        }
    }

    fun stopRx() {
        streaming = false
        runCatching { setTransceiverMode(TRANSCEIVER_MODE_OFF) }
    }

    private fun controlOut(request: Int, value: Int, index: Int, data: ByteArray? = null) {
        val length = data?.size ?: 0
        val transferred = connection.controlTransfer(
            USB_DIR_OUT_VENDOR_DEVICE,
            request,
            value,
            index,
            data,
            length,
            TIMEOUT_MS,
        )
        if (transferred < 0) error("HackRF control OUT failed (request=$request)")
    }

    private fun controlIn(request: Int, value: Int, index: Int, length: Int): ByteArray {
        val buffer = ByteArray(length)
        val transferred = connection.controlTransfer(
            USB_DIR_IN_VENDOR_DEVICE,
            request,
            value,
            index,
            buffer,
            length,
            TIMEOUT_MS,
        )
        if (transferred < 0) error("HackRF control IN failed (request=$request)")
        return buffer
    }

    companion object {
        const val VENDOR_ID = 0x1d50
        const val PRODUCT_ID = 0x6089

        const val SAMPLE_RATE_HZ = 8_000_000
        private const val BASEBAND_FILTER_BW_HZ = 6_000_000

        private const val TIMEOUT_MS = 250
        private const val USB_DIR_OUT_VENDOR_DEVICE = 0x40
        private const val USB_DIR_IN_VENDOR_DEVICE = 0xC0

        // libhackrf hackrf_vendor_request values (host/libhackrf/src/hackrf.c)
        private const val VENDOR_REQUEST_SET_TRANSCEIVER_MODE = 1
        private const val VENDOR_REQUEST_SAMPLE_RATE_SET = 6
        private const val VENDOR_REQUEST_BASEBAND_FILTER_BANDWIDTH_SET = 7
        private const val VENDOR_REQUEST_SET_FREQ = 16
        private const val VENDOR_REQUEST_AMP_ENABLE = 17
        private const val VENDOR_REQUEST_SET_LNA_GAIN = 19
        private const val VENDOR_REQUEST_SET_VGA_GAIN = 20

        // libhackrf hackrf_transceiver_mode values
        private const val TRANSCEIVER_MODE_OFF = 0
        private const val TRANSCEIVER_MODE_RECEIVE = 1
    }
}
