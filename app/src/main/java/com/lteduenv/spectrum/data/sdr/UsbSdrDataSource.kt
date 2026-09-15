package com.lteduenv.spectrum.data.sdr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.lteduenv.spectrum.data.RepeaterDataSource
import com.lteduenv.spectrum.data.SpectrumFrame
import com.lteduenv.spectrum.data.SweepConfig
import com.virginiaprivacy.sdr.sample.SampleRate
import com.virginiaprivacy.sdr.tuner.RTL2832TunerController
import com.virginiaprivacy.sdr.tuner.TunerGain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

private enum class DeviceKind { RTL2832U, HACKRF }

/**
 * Reads a live spectrum from a USB SDR dongle connected through USB OTG - no root, no NDK.
 * Auto-detects whichever supported dongle is plugged in:
 *
 * - **RTL2832U** (RTL-SDR, vendor 0x0bda) - see app/src/main/java/com/virginiaprivacy/sdr/NOTICE.md
 *   for where the vendored low-level tuner protocol code comes from.
 * - **HackRF One** (vendor 0x1d50, product 0x6089) - talks directly to the device via
 *   [HackRfController]; see that file's doc comment for where the protocol was verified.
 *
 * USB permission must already be granted (see [findSupportedDevice], [hasPermission],
 * [requestPermission]) before [spectrum] is collected; call these from the UI layer, not from
 * inside a background collector, since granting permission shows a system dialog.
 */
class UsbSdrDataSource(context: Context) : RepeaterDataSource {

    override val name: String = "USB SDR (RTL2832U / HackRF)"

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private fun classify(device: UsbDevice): DeviceKind? = when {
        device.vendorId == RTL_SDR_VENDOR_ID && device.productId in RTL_SDR_PRODUCT_IDS ->
            DeviceKind.RTL2832U
        device.vendorId == HackRfController.VENDOR_ID && device.productId == HackRfController.PRODUCT_ID ->
            DeviceKind.HACKRF
        else -> null
    }

    /** An attached dongle recognized as either an RTL2832U or a HackRF One. */
    fun findSupportedDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { classify(it) != null }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /** Shows the system USB permission dialog if needed; [onResult] fires once the user answers. */
    fun requestPermission(device: UsbDevice, onResult: (granted: Boolean) -> Unit) {
        val action = "${appContext.packageName}.USB_PERMISSION"
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            appContext, 0, Intent(action).setPackage(appContext.packageName), flags,
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                appContext.unregisterReceiver(this)
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        usbManager.requestPermission(device, permissionIntent)
    }

    @Volatile private var rtlUsbController: RtlSdrUsbController? = null
    @Volatile private var rtlTunerController: RTL2832TunerController? = null
    @Volatile private var hackRfController: HackRfController? = null

    private fun connectedRtlTuner(device: UsbDevice): RTL2832TunerController {
        rtlTunerController?.let { return it }
        synchronized(this) {
            rtlTunerController?.let { return it }
            val controller = RtlSdrUsbController(usbManager, device)
            val tuner = RTL2832TunerController.getTunerController(controller)
            controller.controller = tuner
            rtlUsbController = controller
            rtlTunerController = tuner
            return tuner
        }
    }

    private fun connectedHackRf(device: UsbDevice): HackRfController {
        hackRfController?.let { return it }
        synchronized(this) {
            hackRfController?.let { return it }
            val controller = HackRfController(usbManager, device)
            controller.open()
            hackRfController = controller
            return controller
        }
    }

    override fun spectrum(config: SweepConfig): Flow<SpectrumFrame> = flow {
        val device = findSupportedDevice()
            ?: error("No SDR dongle found. Plug an RTL-SDR or HackRF in via USB OTG and try again.")
        if (!hasPermission(device)) {
            error("USB permission for the SDR dongle hasn't been granted yet.")
        }
        when (classify(device)) {
            DeviceKind.RTL2832U -> streamRtlSdr(device, config)
            DeviceKind.HACKRF -> streamHackRf(device, config)
            null -> error("Unrecognized USB SDR device.")
        }
    }.flowOn(Dispatchers.Default)

    private suspend fun FlowCollector<SpectrumFrame>.streamRtlSdr(device: UsbDevice, config: SweepConfig) {
        val tuner = connectedRtlTuner(device)
        val sampleRate = SampleRate.RATE_2_400MHZ
        tuner.setSampleRate(sampleRate)
        tuner.tunedFrequency = (config.centerMhz * 1_000_000.0).toLong()
        tuner.setGain(TunerGain.AutomaticGain)

        val fftSize = fftSizeForRbw(config.rbwKhz * 1_000.0, sampleRate.rate.toDouble())
        val floatsNeeded = fftSize * 2
        val pending = ArrayDeque<Float>()
        val iqChannel = requireNotNull(rtlUsbController).start()
        try {
            for (chunk in iqChannel) {
                for (f in chunk) pending.addLast(f)
                while (pending.size >= floatsNeeded) {
                    val window = FloatArray(floatsNeeded) { pending.removeFirst() }
                    val levels = Fft.magnitudeSpectrumDb(window, fftSize)
                    val halfSpanMhz = sampleRate.rate / 2_000_000.0
                    emit(
                        SpectrumFrame(
                            startMhz = config.centerMhz - halfSpanMhz,
                            stopMhz = config.centerMhz + halfSpanMhz,
                            levelsDbm = levels,
                            timestampMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        } finally {
            rtlUsbController?.stop()
        }
    }

    private suspend fun FlowCollector<SpectrumFrame>.streamHackRf(device: UsbDevice, config: SweepConfig) {
        val hackRf = connectedHackRf(device)
        hackRf.setSampleRate()
        hackRf.setFrequency((config.centerMhz * 1_000_000.0).toLong())
        hackRf.setLnaGain(24)
        hackRf.setVgaGain(20)
        hackRf.setAmpEnable(config.preampEnabled)

        val fftSize = fftSizeForRbw(config.rbwKhz * 1_000.0, HackRfController.SAMPLE_RATE_HZ.toDouble())
        val bytesNeeded = fftSize * 2 // one byte per I or Q sample (signed 8-bit)
        val pending = ArrayDeque<Byte>()
        val iqChannel = hackRf.startRx()
        try {
            for (chunk in iqChannel) {
                for (b in chunk) pending.addLast(b)
                while (pending.size >= bytesNeeded) {
                    val window = FloatArray(bytesNeeded) { pending.removeFirst().toFloat() / 128f }
                    val levels = Fft.magnitudeSpectrumDb(window, fftSize)
                    val halfSpanMhz = HackRfController.SAMPLE_RATE_HZ / 2_000_000.0
                    emit(
                        SpectrumFrame(
                            startMhz = config.centerMhz - halfSpanMhz,
                            stopMhz = config.centerMhz + halfSpanMhz,
                            levelsDbm = levels,
                            timestampMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        } finally {
            hackRf.stopRx()
        }
    }

    /**
     * Picks the FFT size whose bin width (sampleRateHz / size) is closest to the requested RBW,
     * mirroring a real analyzer's RBW control - a narrower RBW gives finer frequency resolution
     * (bigger FFT) at the cost of a slower update rate, and vice versa. Our FFT is radix-2, so
     * the result is always rounded to a power of two, and clamped to [MIN_FFT_SIZE, MAX_FFT_SIZE]
     * so an extreme RBW value can't demand an unreasonably huge or tiny FFT.
     */
    private fun fftSizeForRbw(rbwHz: Double, sampleRateHz: Double): Int {
        val raw = (sampleRateHz / rbwHz.coerceAtLeast(1.0)).toInt().coerceIn(MIN_FFT_SIZE, MAX_FFT_SIZE)
        val lower = Integer.highestOneBit(raw)
        val upper = if (lower < MAX_FFT_SIZE) lower shl 1 else lower
        return if (raw - lower <= upper - raw) lower else upper
    }

    companion object {
        private const val RTL_SDR_VENDOR_ID = 0x0bda
        private val RTL_SDR_PRODUCT_IDS = setOf(0x2832, 0x2838)

        private const val MIN_FFT_SIZE = 256
        private const val MAX_FFT_SIZE = 8192
    }
}
