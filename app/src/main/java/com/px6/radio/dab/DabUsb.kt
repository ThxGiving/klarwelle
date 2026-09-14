package com.px6.radio.dab

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App-level USB permission gate for the DAB stick — run BEFORE omri touches the device.
 *
 * Why: if omri initialises the tuner without USB permission and the user denies (or dismisses) the
 * dialog, the native RaonTuner code goes on to open/scan a device that was never opened → SIGSEGV,
 * which BootGuard then reads as a crash and **permanently disables DAB**. By securing (or being
 * denied) permission here first, a denial becomes a clean Java-level skip: omri is never started, so
 * there is no native crash and no safe-mode disable — DAB simply stays unavailable for this session
 * and is retried next boot.
 */
object DabUsb {

    private const val TAG = "DabUsb"
    // The DAB stick class (16C0:05DC) omri supports — decimal 5824/1500 in device_filter.xml.
    private const val VENDOR_ID = 0x16C0
    private const val PRODUCT_ID = 0x05DC
    private const val ACTION_PERMISSION = "com.px6.radio.USB_PERMISSION"

    private fun stick(ctx: Context) =
        (ctx.getSystemService(Context.USB_SERVICE) as? UsbManager)?.deviceList?.values
            ?.firstOrNull { it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID }

    /** The USB id we look for, "16C0:05DC" — shown in the DAB info panel. */
    fun expectedUsbId(): String = "%04X:%04X".format(VENDOR_ID, PRODUCT_ID)

    /**
     * Hardware description of the attached DAB stick for the info panel, or null if none is present.
     * Product/manufacturer strings need API 21+/23 and are often empty on these cheap sticks, so
     * everything is optional and the USB id is the reliable part.
     */
    fun describe(ctx: Context): StickInfo? {
        val d = stick(ctx) ?: return null
        return StickInfo(
            usbId = "%04X:%04X".format(d.vendorId, d.productId),
            product = runCatching { d.productName }.getOrNull()?.takeIf { it.isNotBlank() },
            manufacturer = runCatching { d.manufacturerName }.getOrNull()?.takeIf { it.isNotBlank() },
            deviceName = d.deviceName,
            hasPermission = (ctx.getSystemService(Context.USB_SERVICE) as? UsbManager)
                ?.hasPermission(d) == true,
        )
    }

    data class StickInfo(
        val usbId: String,
        val product: String?,
        val manufacturer: String?,
        val deviceName: String,
        val hasPermission: Boolean,
    )

    /**
     * True when it is safe to start omri:
     *  - no stick present → true (nothing to gate; omri handles "no device" itself),
     *  - stick present and permission already held → true,
     *  - stick present, permission requested and GRANTED by the user → true,
     *  - denied or timed out → false (caller skips DAB init cleanly).
     * Never throws.
     */
    suspend fun ensurePermission(ctx: Context, awaitMs: Long = 30_000L): Boolean {
        val um = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return true
        val dev = stick(ctx) ?: return true
        if (um.hasPermission(dev)) return true
        Log.i(TAG, "DAB stick present without USB permission — requesting before omri init")
        val granted = withTimeoutOrNull(awaitMs) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        if (i.action != ACTION_PERMISSION) return
                        runCatching { c.unregisterReceiver(this) }
                        if (cont.isActive) cont.resumeWith(Result.success(um.hasPermission(dev)))
                    }
                }
                val filter = IntentFilter(ACTION_PERMISSION)
                if (Build.VERSION.SDK_INT >= 33) {
                    ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag") ctx.registerReceiver(receiver, filter)
                }
                cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(receiver) } }
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(
                    ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName), flags,
                )
                runCatching { um.requestPermission(dev, pi) }.onFailure {
                    if (cont.isActive) cont.resumeWith(Result.success(false))
                }
            }
        } ?: false
        Log.i(TAG, "DAB USB permission result: $granted")
        return granted
    }
}
