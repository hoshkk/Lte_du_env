package com.lteduenv.rxcheck.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.lteduenv.rxcheck.core.usb.UsbIo
import com.lteduenv.rxcheck.core.usb.UsbIoException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No RTL2832U on the bus (unplugged, or the OTG contact dropped). */
class DongleMissing(message: String) : java.io.IOException(message)

/** The user refused (or did not answer) the USB permission prompt; never retried. */
class UsbPermissionDenied(message: String) : java.io.IOException(message)

/** UsbIo over Android's USB host API (synchronous vendor control + bulk IN). */
class AndroidUsbIo(private val device: UsbDevice, private val conn: UsbDeviceConnection) : UsbIo, AutoCloseable {
    private val iface: UsbInterface = device.getInterface(0)
    private val bulkIn: UsbEndpoint = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
        .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
        ?: throw UsbIoException("수신 엔드포인트를 찾지 못했습니다")

    init {
        if (!conn.claimInterface(iface, true)) {
            conn.close()
            throw UsbIoException("USB 인터페이스를 점유하지 못했습니다. 다른 SDR 앱을 종료하세요")
        }
    }

    override fun controlOut(value: Int, index: Int, data: ByteArray, length: Int): Int =
        conn.controlTransfer(VENDOR_OUT, 0, value, index, data, length, TIMEOUT_MS)

    override fun controlIn(value: Int, index: Int, buffer: ByteArray, length: Int): Int =
        conn.controlTransfer(VENDOR_IN, 0, value, index, buffer, length, TIMEOUT_MS)

    override fun bulkIn(buffer: ByteArray, length: Int, timeoutMs: Int): Int =
        conn.bulkTransfer(bulkIn, buffer, length, timeoutMs)

    override val manufacturer: String? = runCatching { device.manufacturerName }.getOrNull()
    override val product: String? = runCatching { device.productName }.getOrNull()

    override fun close() {
        runCatching { conn.releaseInterface(iface) }
        conn.close()
    }

    companion object {
        private const val VENDOR_OUT = 0x40
        private const val VENDOR_IN = 0xc0
        private const val TIMEOUT_MS = 1000
    }
}

object UsbAccess {
    private const val VID = 0x0bda
    private val PIDS = setOf(0x2832, 0x2838)

    fun isAttached(context: Context): Boolean =
        findDongle(context.getSystemService(Context.USB_SERVICE) as UsbManager) != null

    fun findDongle(manager: UsbManager): UsbDevice? =
        manager.deviceList.values.firstOrNull { it.vendorId == VID && it.productId in PIDS }

    /** Opens the first RTL2832U dongle, asking for permission if needed. */
    suspend fun open(context: Context): AndroidUsbIo {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = findDongle(manager)
            ?: throw DongleMissing("RTL-SDR 동글이 없습니다. OTG 연결을 확인하세요")
        if (!manager.hasPermission(device)) requestPermission(context, manager, device)
        val conn = manager.openDevice(device) ?: throw UsbIoException("USB 장치를 열지 못했습니다")
        return AndroidUsbIo(device, conn)
    }

    private suspend fun requestPermission(context: Context, manager: UsbManager, device: UsbDevice) {
        val action = context.packageName + ".USB_PERMISSION"
        try { withTimeout(60_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        if (intent.action != action) return
                        runCatching { context.unregisterReceiver(this) }
                        if (!cont.isActive) return
                        if (manager.hasPermission(device)) cont.resume(Unit)
                        else cont.resumeWithException(UsbPermissionDenied("USB 사용 권한이 거부되었습니다. 측정 시작을 눌러 다시 허용하세요"))
                    }
                }
                ContextCompat.registerReceiver(context, receiver, IntentFilter(action),
                    ContextCompat.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(context, 0,
                    Intent(action).setPackage(context.packageName), flags)
                manager.requestPermission(device, pi)
            }
        } } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw UsbPermissionDenied("USB 권한 응답이 없습니다. 측정 시작을 다시 누르세요")
        }
    }
}
