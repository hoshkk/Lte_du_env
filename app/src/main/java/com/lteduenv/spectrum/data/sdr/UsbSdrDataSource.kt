package com.lteduenv.spectrum.data.sdr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.lteduenv.spectrum.data.CableLossResult
import com.lteduenv.spectrum.data.DtfFrame
import com.lteduenv.spectrum.data.RepeaterDataSource
import com.lteduenv.spectrum.data.SpectrumFrame
import com.lteduenv.spectrum.data.SweepConfig
import com.lteduenv.spectrum.data.VswrFrame
import com.virginiaprivacy.sdr.sample.SampleRate
import com.virginiaprivacy.sdr.tuner.RTL2832TunerController
import com.virginiaprivacy.sdr.tuner.TunerGain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emptyFlow
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
 * Both are receive-only with no directional coupler, so [vswr], [dtf] and [measureCableLoss] are
 * not available through this data source - only [spectrum].
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

        val fftSize = 2048
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
        hackRf.setAmpEnable(false)

        val fftSize = 2048
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

    /** True only if the currently attached dongle is a HackRF - RTL-SDR is receive-only hardware. */
    fun supportsTx(): Boolean = findSupportedDevice()?.let { classify(it) == DeviceKind.HACKRF } ?: false

    /**
     * Transmits an unmodulated CW test carrier via a connected HackRF at [freqMhz] and
     * [txGainDb] (0-47 dB). Throws if no HackRF is connected/permitted - RTL-SDR dongles can
     * never transmit, that's a hardware limitation this can't work around. Caller must not have
     * an RX collection (see [spectrum]) running against the same dongle at the same time.
     */
    fun startTxTestTone(freqMhz: Double, txGainDb: Int) {
        val device = findSupportedDevice() ?: error("No SDR dongle found. Plug a HackRF in via USB OTG and try again.")
        require(classify(device) == DeviceKind.HACKRF) {
            "Only HackRF One can transmit; RTL-SDR dongles are receive-only hardware."
        }
        if (!hasPermission(device)) {
            error("USB permission for the SDR dongle hasn't been granted yet.")
        }
        val hackRf = connectedHackRf(device)
        hackRf.setFrequency((freqMhz * 1_000_000.0).toLong())
        hackRf.setTxVgaGain(txGainDb)
        hackRf.startTx()
    }

    fun stopTxTestTone() {
        hackRfController?.stopTx()
    }

    // Neither dongle has a directional coupler, so none of these can be measured.
    override fun vswr(config: SweepConfig): Flow<VswrFrame> = emptyFlow()

    override fun dtf(config: SweepConfig, maxDistanceM: Double): Flow<DtfFrame> = emptyFlow()

    override suspend fun measureCableLoss(config: SweepConfig, lengthM: Double): CableLossResult {
        throw UnsupportedOperationException(
            "Cable loss measurement needs a VNA / directional coupler - not possible from a receive-only SDR dongle.",
        )
    }

    companion object {
        private const val RTL_SDR_VENDOR_ID = 0x0bda
        private val RTL_SDR_PRODUCT_IDS = setOf(0x2832, 0x2838)
    }
}
