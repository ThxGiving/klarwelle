package com.px6.radio.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Outside temperature (and other vehicle data) from the Microntek canbus service.
 *
 * How the ROM's "Fahrzeug" app (`com.microntek.controlinfo`) actually does it — reverse-engineered
 * from the Vivid firmware (`Canbus01CarInfo.ProcessData` + `SerialBroadcast`): the MCU broadcasts
 * **`com.microntek.sync`** with a byte[] extra **`syncdata`** — the raw CAN status frame, pushed
 * event-driven (which is why washer-fluid etc. update live). It is NOT `com.canbus.temperature`
 * (that action doesn't exist on this ROM — we received zero of them on the device).
 *
 * For the common profile the frame is `syncdata[0]==0x41`, sub-type `syncdata[2]==2`, and:
 *   speed = [3..4], battery = int16[7..8]·0.01 V, **temp = int16[9..10]·0.1 °C** (signed), km = [11..13].
 *
 * Byte offsets can differ per canbus profile, so every distinct frame is also logged to the stick
 * (`klarwelle-sync.txt`) — a drive confirms the exact layout for this car. The legacy
 * `com.canbus.temperature` string path is kept too, harmless where it never fires.
 */
class CarInfo(context: Context) {

    private val appContext = context.applicationContext

    private val _temperature = MutableStateFlow<String?>(null)
    val temperature: StateFlow<String?> = _temperature.asStateFlow()

    private var tempCount = 0
    private val loggedCmds = HashSet<Int>()   // probe: log one raw frame per distinct command byte

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                when (intent?.action) {
                    ACTION_SYNC -> onSync(intent.getByteArrayExtra(EXTRA_SYNC))
                    ACTION_TEMP -> {           // legacy path — absent on this ROM, kept for others
                        val raw = intent.getStringExtra(EXTRA_TEMP)
                        normalize(raw)?.let { _temperature.value = it }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "onReceive failed: ${t.message}")
            }
        }
    }

    /** Parse a `com.microntek.sync` CAN frame: pull the temperature and (probe) log new frames. */
    private fun onSync(d: ByteArray?) {
        if (d == null || d.isEmpty()) return
        val cmd = d[0].toInt() and 0xFF
        // Probe: log the first frame seen for each command byte, so the real layout is verifiable.
        if (loggedCmds.add(cmd) && loggedCmds.size <= 24) {
            com.px6.radio.diag.Diag.write(
                appContext, "klarwelle-sync.txt",
                "cmd=0x%02X len=%d %s\n".format(cmd, d.size, d.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }),
                append = true,
            )
        }
        // Common vehicle-status frame (as Canbus01CarInfo): temp = signed int16[9..10] * 0.1 °C.
        if (cmd == CMD_STATUS && d.size > 10 && (d[2].toInt() and 0xFF) == 2) {
            var raw = ((d[9].toInt() and 0xFF) shl 8) or (d[10].toInt() and 0xFF)
            if (raw >= 0x8000) raw -= 0x10000
            val celsius = raw * 0.1
            if (celsius > -60 && celsius < 90) {          // sanity gate against garbage frames
                tempCount++
                _temperature.value = "${celsius.roundToInt()} °C"
            }
        }
    }

    val registered: Boolean = register()

    private fun register(): Boolean = try {
        val filter = IntentFilter().apply { addAction(ACTION_SYNC); addAction(ACTION_TEMP) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        true
    } catch (t: Throwable) {
        Log.w(TAG, "register failed: ${t.message}")
        false
    }

    fun release() {
        try {
            appContext.unregisterReceiver(receiver)
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val TAG = "CarInfo"
        const val ACTION_SYNC = "com.microntek.sync"
        const val EXTRA_SYNC = "syncdata"
        const val CMD_STATUS = 0x41            // 65 — vehicle status frame in Canbus01CarInfo
        const val ACTION_TEMP = "com.canbus.temperature"
        const val EXTRA_TEMP = "temperature"

        /** Legacy string form `" 22℃"`/`" OUT"` → `"22 °C"` (kept for ROMs that use it). */
        fun normalize(raw: String?): String? {
            val t = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (t.equals("OUT", ignoreCase = true)) return null
            val number = Regex("-?\\d+([.,]\\d+)?").find(t)?.value ?: return null
            return "$number °C"
        }
    }
}
