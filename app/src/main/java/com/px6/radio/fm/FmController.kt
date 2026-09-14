package com.px6.radio.fm

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.util.Log
import com.px6.radio.diag.Diag
import com.px6.radio.diag.HiddenApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Steering-wheel / panel key actions delivered by the box via the CarManager "KeyDown" channel. */
enum class SwcKey { NEXT, PREV, SEEK_UP, SEEK_DOWN, OPEN_LIST }

/** Live FM state (RDS + tuning), mirrored from the Microntek CarManager radio events. */
data class FmState(
    val available: Boolean = false,
    val freqKhz: Int = 0,
    val ps: String? = null,   // RDS Program Service name
    val rt: String? = null,   // RDS RadioText
    val pi: Int? = null,      // RDS Program Identification (for DAB<->FM PI matching)
    val pty: Int = 0,
    val ta: Boolean = false,
    val stereo: Boolean = false,
    val seeking: Boolean = false,
    /** Field strength as the box reports it (raw scale, -1 = unknown). Not sent continuously. */
    val signal: Int = -1,
)

/**
 * FM/AM via Microntek `android.microntek.CarManager` — bound **reflectively** so the app never
 * fails to load where that ROM class is absent (emulator, non-Microntek head units). If binding
 * fails, [available] is false and the app is simply a pure DAB+ radio.
 *
 * Control  → `CarManager.setParameters("ctl_radio_*=…")`
 * Feedback → `CarManager.attach(Handler, "Radio,KeyDown")`; radio events arrive as [Message]s
 *            with `obj == "Radio"` and a [Bundle] payload (value under key "query").
 *
 * Reverse-engineered from the ROM app HCT4Radio (com.microntek.radio.RadioService);
 * see docs and golf5-cluster-nav decompiled sources.
 */
class FmController(
    context: Context,
    private val onSwc: (SwcKey) -> Unit = {},
    /** A station the MCU auto-seek locked onto (frequency in kHz). */
    private val onScanHit: (Int) -> Unit = {},
    /** The MCU auto-seek finished sweeping the band. */
    private val onScanEnd: () -> Unit = {},
    /**
     * Fallback transport for `setParameters` when the ROM blocks hidden-API reflection:
     * CanBusServer.setCanParameters() delegates straight to CarManager.setParameters().
     */
    private val paramSink: ((String) -> Boolean)? = null,
) : com.px6.radio.following.FmFollowSource {

    private val appContext = context.applicationContext

    private var car: Any? = null
    private var mSetParameters: java.lang.reflect.Method? = null
    private var mGetParameters: java.lang.reflect.Method? = null
    private var mGetStringState: java.lang.reflect.Method? = null
    private var mGetBooleanState: java.lang.reflect.Method? = null
    private var mDetach: java.lang.reflect.Method? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val _state = MutableStateFlow(FmState())
    override val state: StateFlow<FmState> = _state.asStateFlow()

    /** True while a `ctl_radio_seek=auto` sweep is in progress (drives seek-event interpretation). */
    @Volatile private var autoSeeking = false

    /** Whether a real `freq` event has arrived during the current sweep — guards against inventing one. */
    @Volatile private var sawFreqSinceScan = false

    /** Drops a stale RDS name after a silence, so the display falls back to the frequency (as the ROM). */
    private val rdsTimeout = Runnable { _state.update { it.copy(ps = null, rt = null, pty = 0) } }

    // ---- on-device event trace (no adb): dumps every Radio-bundle event + scan hit to klarwelle-fm.txt ----
    private val fmTrace = StringBuilder(4096)
    @Volatile private var traceLines = 0
    // APPEND each batch and clear the buffer — the old "overwrite the whole file every flush" pattern
    // rewrote klarwelle-fm.txt many times per second (event + 3s snapshot), so pulling the stick mid-write
    // left a torn/binary file (why FM logs kept coming back corrupt). Append is torn-write-safe.
    private val flushTrace = Runnable {
        val text = synchronized(fmTrace) {
            val s = fmTrace.toString(); fmTrace.setLength(0); traceLines = 0; s
        }
        if (text.isNotEmpty()) runCatching { Diag.write(appContext, "klarwelle-fm.txt", text, append = true) }
    }

    /** Append one bounded trace line (timestamped) and schedule a debounced flush to the stick. */
    private fun trace(msg: String) {
        synchronized(fmTrace) {
            if (traceLines >= TRACE_MAX_LINES) return
            fmTrace.append(clock()).append(' ').append(msg).append('\n')
            traceLines++
        }
        handler?.let { it.removeCallbacks(flushTrace); it.postDelayed(flushTrace, 800) }
    }

    private fun clock(): String {
        val s = System.currentTimeMillis() / 1000
        return "%02d:%02d:%02d".format((s / 3600) % 24, (s / 60) % 60, s % 60)
    }

    /** Periodic FM/RDS ground-truth snapshot — logs whether FM is actually the active source and what
     *  RDS has delivered, so a session where "no names arrive" shows definitively if psn/pi ever came
     *  or if FM was never even the active channel. Only logs while FM is active (no spam on DAB/IP). */
    private val snapshot = object : Runnable {
        override fun run() {
            runCatching {
                if (isFmActive()) {
                    val st = _state.value
                    trace("snapshot av_channel=${runCatching { stringState("av_channel") }.getOrNull()} " +
                        "freq=${st.freqKhz} ps=${st.ps?.let { "\"$it\"" }} " +
                        "pi=${st.pi?.let { "0x%04X".format(it) }} stereo=${st.stereo} signal=${st.signal} " +
                        "cfg_rds=${runCatching { getParameters("cfg_rds=") }.getOrNull()}")
                }
            }
            handler?.postDelayed(this, SNAPSHOT_MS)
        }
    }

    /** Compact dump of a bundle's "value" payload — the box carries RDS text there as a byte[], ints else. */
    private fun dumpBundle(b: Bundle): String = buildString {
        append("keys=").append(b.keySet().joinToString("|"))
        val ba = runCatching { b.getByteArray("value") }.getOrNull()
        val str = runCatching { b.getString("value") }.getOrNull()
        when {
            ba != null -> {
                append(" bytes=").append(ba.joinToString("") { "%02X".format(it) })
                append(" ascii=\"").append(String(ba, Charsets.ISO_8859_1).filter { it.code in 0x20..0x7E }).append('"')
            }
            str != null -> append(" str=\"").append(str).append('"')
            else -> append(" int=").append(runCatching { b.getInt("value") }.getOrNull())
        }
        runCatching { b.getInt("strength", -1) }.getOrNull()?.let { if (it >= 0) append(" strength=").append(it) }
    }

    private fun bumpRds() {
        handler?.removeCallbacks(rdsTimeout)
        handler?.postDelayed(rdsTimeout, RDS_TIMEOUT_MS)
    }

    /** If binding failed, the exact reason (step + exception) — surfaced for on-device diagnosis. */
    var bindError: String? = null
        private set

    /** True whether the CarManager class exists at all (i.e. this is a Microntek head unit). */
    var carManagerPresent: Boolean = false
        private set

    /** The CarManager constructor signatures actually present on this ROM (for diagnostics). */
    var carManagerCtors: String? = null
        private set

    /** Which transport drives FM: direct CarManager, the CanBus binder, or none. */
    var mode: String = "keiner"
        private set

    /** FM usable — either via CarManager reflection or via the CanBusServer passthrough.
     *
     * The CanBus fallback is only trusted on an actual Microntek head unit, i.e. one where the
     * `android.microntek.CarManager` class exists ([carManagerPresent]). Otherwise a bare CanBus
     * binder that happens to bind (e.g. on an emulator, where setCanParameters just returns false and
     * CarManager is ClassNotFound) would falsely advertise FM/AM and offer a dead band + scan. No
     * CarManager class ⇒ this is not a head unit with an FM tuner ⇒ FM/AM stay hidden. */
    override val available: Boolean = bind().also { bound ->
        val canbusFallback = !bound && paramSink != null && carManagerPresent
        mode = when {
            bound -> "CarManager (Reflection)"
            canbusFallback -> "CanBus-Binder (setCanParameters)"
            else -> "keiner"
        }
        if (canbusFallback) {
            // Control works; RDS feedback needs CarManager.attach and stays unavailable for now.
            _state.update { it.copy(available = true) }
            Log.i(TAG, "FM via CanBus-Binder-Fallback (ohne RDS-Rückmeldung)")
        }
    } || (paramSink != null && carManagerPresent)

    /** ROMs differ: try no-arg, then (Context), then any constructor we can satisfy. */
    private fun newCarManager(cls: Class<*>): Any {
        // Hidden-API-safe lookups first (Android 9+ hides vendor framework members from reflection).
        val noArg = HiddenApi.constructor(cls)
        if (noArg != null) {
            try {
                return noArg.newInstance()
            } catch (_: Throwable) {
            }
        }
        val ctxCtor = HiddenApi.constructor(cls, Context::class.java)
        if (ctxCtor != null) {
            try {
                return ctxCtor.newInstance(appContext)
            } catch (_: Throwable) {
            }
        }
        runCatching { return cls.getConstructor().newInstance() }
        runCatching { return cls.getConstructor(Context::class.java).newInstance(appContext) }
        for (c in cls.declaredConstructors) {
            runCatching { c.isAccessible = true }
            val types = c.parameterTypes
            var usable = true
            val args = arrayOfNulls<Any?>(types.size)
            for (i in types.indices) {
                val t = types[i]
                args[i] = when {
                    Context::class.java.isAssignableFrom(t) -> appContext
                    t == Boolean::class.javaPrimitiveType -> false
                    t == Int::class.javaPrimitiveType -> 0
                    t == Long::class.javaPrimitiveType -> 0L
                    !t.isPrimitive -> null
                    else -> { usable = false; null }
                }
            }
            if (usable) runCatching { return c.newInstance(*args) }
        }
        throw NoSuchMethodException("kein nutzbarer CarManager-Konstruktor; vorhanden: ${ctorSignatures(cls)}")
    }

    private fun ctorSignatures(cls: Class<*>): String = runCatching {
        cls.declaredConstructors.joinToString("; ") { c ->
            "(" + c.parameterTypes.joinToString(",") { it.simpleName } + ")"
        }.ifEmpty { "keine sichtbar" }
    }.getOrElse { "Lookup blockiert: ${it.javaClass.simpleName}" }

    private fun bind(): Boolean {
        var step = "Class.forName"
        return try {
            val cls = Class.forName("android.microntek.CarManager")
            carManagerPresent = true
            carManagerCtors = ctorSignatures(cls)
            step = "newInstance"
            car = newCarManager(cls)
            step = "getMethods"
            mSetParameters = HiddenApi.method(cls, "setParameters", String::class.java)
                ?: cls.getMethod("setParameters", String::class.java)
            // Read-back of MCU config strings (e.g. cfg_rds) — how HCT4Radio learns RDS capability.
            mGetParameters = HiddenApi.method(cls, "getParameters", String::class.java)
                ?: runCatching { cls.getMethod("getParameters", String::class.java) }.getOrNull()
            mGetStringState = HiddenApi.method(cls, "getStringState", String::class.java)
                ?: cls.getMethod("getStringState", String::class.java)
            mGetBooleanState = HiddenApi.method(cls, "getBooleanState", String::class.java)
                ?: cls.getMethod("getBooleanState", String::class.java)
            mDetach = HiddenApi.method(cls, "detach") ?: cls.getMethod("detach")

            val ht = HandlerThread("fm-carmanager").also { it.start() }
            thread = ht
            val h = object : Handler(ht.looper) {
                override fun handleMessage(msg: Message) {
                    when (msg.obj) {
                        "Radio" -> onRadioEvent(msg.data)
                        "KeyDown" -> onKeyEvent(msg.data)
                    }
                }
            }
            handler = h
            step = "attach(Handler,String)"
            val attach = HiddenApi.method(cls, "attach", Handler::class.java, String::class.java)
                ?: cls.getMethod("attach", Handler::class.java, String::class.java)
            attach.invoke(car, h, "Radio,KeyDown")
            _state.update { it.copy(available = true) }
            // Turn RDS on right away (as the ROM's init does), so PS/RT/PI start flowing.
            runCatching { mSetParameters?.invoke(car, "ctl_radio_rds=1") }
            // APPEND a session header (don't truncate) — a reboot must not wipe the previous session's
            // FM/RDS trace ("FM had RDS text but it's not in the log" = an earlier session's data lost
            // to the per-boot truncate). Append is torn-write-safe; the user can clear the file.
            runCatching { Diag.write(appContext, "klarwelle-fm.txt", "\n=== FM session start ${clock()} ===\n", append = true) }
            trace("bound: attach(Radio,KeyDown) ok, ctl_radio_rds=1")
            handler?.postDelayed(snapshot, SNAPSHOT_MS)   // periodic FM/RDS ground-truth snapshot
            Log.i(TAG, "CarManager bound — FM available")
            true
        } catch (t: Throwable) {
            val real = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            bindError = "$step → ${real.javaClass.simpleName}: ${real.message}"
            Log.i(TAG, "CarManager bind failed: $bindError")
            false
        }
    }

    /** Read a CarManager boolean state (e.g. "headlight") — null if unavailable. */
    fun booleanState(key: String): Boolean? =
        if (mGetBooleanState == null || car == null) null else runCatching { boolState(key) }.getOrNull()

    /** Boot gate: the MCU tuner must be ready before tuning. */
    fun isBootComplete(): Boolean = boolState("boot_complete")

    /** Route audio to FM (call after boot_complete). */
    fun enterFm() {
        trace("enterFm: av_focus_gain=fm, av_channel_enter=fm, applyRdsConfig")
        set("av_focus_gain=fm")
        set("av_channel_enter=fm")
        applyRdsConfig()
    }

    /**
     * Reproduce HCT4Radio's `init()` RDS bring-up faithfully — the ROM app on this box gets RDS
     * (PS/RT/PI) reliably, and the difference is the **full sequence ending in a re-tune**, not a
     * lone `ctl_radio_rds=1`:
     *   cfg_rds (capability) → ctl_radio_rds → af/ta/pty → loc/st → **ctl_radio_frequency**.
     * The closing frequency write makes the MCU (re)start RDS for the current station; enabling RDS
     * without re-tuning (what we did before) leaves the MCU never re-evaluating it — no name arrives.
     */
    private fun applyRdsConfig() {
        val cfg = getParameters("cfg_rds=")
        val rdsMcu = (cfg?.trim()?.toIntOrNull() ?: 1) != 0   // default to capable if unreadable
        trace("applyRdsConfig cfg_rds=$cfg -> rdsMcu=$rdsMcu")
        set("ctl_radio_rds=${if (rdsMcu) 1 else 0}")
        if (rdsMcu) {
            set("ctl_radio_af=0")
            set("ctl_radio_ta=0")
            set("ctl_radio_pty=0")
        }
        set("ctl_radio_loc=0")   // HCT4Radio's init sends this; we used to omit it
        set("ctl_radio_st=1")
        // Closing re-tune — HCT4Radio ALWAYS ends init with a ctl_radio_frequency write, and that is
        // what makes the MCU (re)start RDS acquisition for the current station. We used to only do it
        // when freqKhz>0, but at the first enterFm that is 0, so it was skipped and RDS never armed.
        // Use the known/last-tuned frequency so it is never skipped.
        val f = _state.value.freqKhz.takeIf { it > 0 } ?: lastTunedKhz
        if (f > 0) set("ctl_radio_frequency=$f")
    }

    fun exitFm() {
        set("av_focus_loss=fm")
        set("av_channel_exit=fm")
    }

    fun isFmActive(): Boolean = "fm" == stringState("av_channel")

    /** Tune directly. [khz] e.g. 100400 for 100.4 MHz (CarManager expects kHz).
     *
     * Set our frequency optimistically BEFORE the write (like HCT4Radio's sToFreq, which sets mFreq
     * first): the box's `freq` echo can lag or — with the resident factory RadioService holding the
     * session — not arrive at all, and without this the frequency readout never moves and an incoming
     * RDS `psn` would key onto the stale/`fm.0` list entry instead of the station we just tuned to.
     * Drop the old station's RDS so a stale name can't stick to the new frequency. */
    override fun tune(khz: Int) {
        lastTunedKhz = khz
        _state.update { it.copy(freqKhz = khz, ps = null, rt = null, pty = 0, pi = null) }
        set("ctl_radio_frequency=$khz")
    }

    @Volatile private var lastTunedKhz = 0

    fun tuneUp() = set("ctl_radio_tune=up")
    fun tuneDown() = set("ctl_radio_tune=down")
    fun seekUp() = set("ctl_radio_seek=up")
    fun seekDown() = set("ctl_radio_seek=down")

    /**
     * Autostore sweep — the MCU searches the whole band in hardware and pushes each station it finds
     * (with real field strength), exactly as the factory radio does. Far more reliable than an
     * app-driven step loop, which depends on per-step seek events that some boxes never emit.
     */
    fun autoScan() {
        autoSeeking = true
        sawFreqSinceScan = false
        // Keep the trace across scans (a mid-session capture must still show the RDS events that came
        // before the scan) — just mark the boundary instead of resetting.
        trace("=== autoScan start (ctl_radio_seek=auto) ===")
        set("ctl_radio_seek=auto")
    }

    /** Stop treating incoming seek events as an auto-sweep (e.g. the caller cancelled the scan). */
    fun cancelAutoScan() {
        autoSeeking = false
    }
    /**
     * Ask the MCU to (keep) streaming vehicle-status frames via the `com.microntek.sync` broadcast —
     * the exact request the ROM's "Fahrzeug" app sends: a 0x2E serial command `[2E, 81, 01, 01, CRC]`
     * (`SerialBroadcast.SendCmdData(0x81,{1},1)`), delivered as `setParameters("canbus_rsp=…")`.
     * Best-effort: a no-op on non-Microntek units. Without it the sync stream may be silent while our
     * app is foreground and the vehicle app isn't.
     */
    fun requestVehicleData() = set("canbus_rsp=46,129,1,1,124")

    fun setRds(on: Boolean) = set("ctl_radio_rds=${if (on) 1 else 0}")
    fun setTa(on: Boolean) = set("ctl_radio_ta=${if (on) 1 else 0}")
    fun setAf(on: Boolean) = set("ctl_radio_af=${if (on) 1 else 0}")

    fun release() {
        handler?.removeCallbacks(rdsTimeout)
        try {
            mDetach?.invoke(car)
        } catch (_: Throwable) {
        }
        thread?.quitSafely()
        thread = null
        handler = null
        car = null
    }

    // ---- radio event demux (mirrors HCT4Radio RadioTask) ----
    private fun onRadioEvent(b: Bundle?) = try {
        demux(b)
    } catch (t: Throwable) {
        Log.w(TAG, "onRadioEvent failed: ${t.message}")
    }

    /** Steering-wheel / panel keys (CarManager "KeyDown", keycodes as in HCT4Radio DoCarKeyDown). */
    private fun onKeyEvent(b: Bundle?) {
        try {
            if (b == null || b.getString("type") != "key") return
            val code = b.getInt("query")
            // Log to the stick (not just logcat) so an unknown panel button — e.g. the "≡" list key —
            // can be identified without adb. Press the button, then read klarwelle-fm.txt for "SWC keycode".
            trace("SWC keycode: $code")
            val key = when (code) {
                268, 300 -> SwcKey.NEXT
                260, 299 -> SwcKey.PREV
                278 -> SwcKey.SEEK_UP
                276 -> SwcKey.SEEK_DOWN
                // TODO map the "3-lines"/list panel key to SwcKey.OPEN_LIST once its code is known.
                else -> null
            }
            key?.let(onSwc)
        } catch (t: Throwable) {
            Log.w(TAG, "onKeyEvent failed: ${t.message}")
        }
    }

    private fun demux(b: Bundle?) {
        b ?: return
        // Device-verified (klarwelle-fm.txt): the box carries every payload under the key "value", not
        // "query". HCT4Radio reads LoggingEvents.VoiceSearch.EXTRA_QUERY_UPDATED_VALUE, whose actual
        // string IS "value" — we had hardcoded "query", so freq/psn/rt/pi/pty all read null/0 and no
        // RDS name ever appeared. This single key is the whole RDS + scan-frequency fix.
        val v = "value"
        // Trace every event so RDS/scan behaviour can be read off klarwelle-fm.txt without adb/logcat.
        trace("evt type=${b.getString("type")} seek=$autoSeeking ${dumpBundle(b)}")
        // The box attaches a field strength to some events (HCT4Radio reads bundle.getInt(
        // "strength") on seek_found). It is not sent continuously like DAB reception, so we take
        // it whenever it appears and keep the last value.
        val strength = b.getInt("strength", -1)
        if (strength >= 0) _state.update { it.copy(signal = strength) }
        when (b.getString("type")) {
            "freq" -> onFreq(b.getInt(v) / 1000)
            "psn" -> { _state.update { it.copy(ps = decode(b.getByteArray(v))) }; bumpRds() }
            "rt" -> { _state.update { it.copy(rt = decode(b.getByteArray(v))) }; bumpRds() }
            "pi" -> { _state.update { it.copy(pi = b.getInt(v)) }; bumpRds() }
            "pty" -> { _state.update { it.copy(pty = b.getInt(v)) }; bumpRds() }
            "ta" -> _state.update { it.copy(ta = b.getInt(v) == 1) }
            "stereo" -> _state.update { it.copy(stereo = b.getInt(v) == 1) }
            "signal", "strength" -> _state.update { it.copy(signal = b.getInt(v)) }
            "seek_start", "seek_start_auto" -> _state.update { it.copy(seeking = true) }
            "seek_found", "seek_found_auto" -> onSeekFound(b.getInt(v))
            "seek_end", "seek_end_auto" -> {
                val wasAuto = autoSeeking
                autoSeeking = false
                _state.update { it.copy(seeking = false) }
                if (wasAuto) onScanEnd()
            }
        }
    }

    /** Tuner moved to a new frequency. A new frequency is a new station, so the old RDS is dropped. */
    private fun onFreq(khz: Int) {
        if (autoSeeking) sawFreqSinceScan = true
        val changed = khz != _state.value.freqKhz
        _state.update {
            if (changed) it.copy(freqKhz = khz, ps = null, rt = null, pty = 0, pi = null)
            else it.copy(freqKhz = khz)
        }
        if (changed) handler?.removeCallbacks(rdsTimeout)   // fresh RDS will stream in for the new one
    }

    /** A `seek_found[_auto]` event — one auto-sweep hit, or a normal single seek locking on. */
    private fun onSeekFound(rawHz: Int) {
        if (!autoSeeking) {
            // Manual seek locked onto a station — capture the found frequency so the readout follows
            // (the box carries it in the event; without this freqKhz stayed on the pre-seek value and
            // the display never moved), and drop the old RDS so a stale name can't stick.
            val khz = rawHz.takeIf { it > 0 }?.div(1000)
            _state.update {
                if (khz != null && khz != it.freqKhz)
                    it.copy(freqKhz = khz, seeking = false, ps = null, rt = null, pty = 0, pi = null)
                else it.copy(seeking = false)
            }
            trace("  -> manual seek locked khz=${khz ?: _state.value.freqKhz} (rawHz=$rawHz)")
            return
        }
        // Only report a hit when we actually have this station's frequency — from the event itself,
        // or from a `freq` event during the sweep. Never fall back to the pre-scan frequency, or a
        // box that reports neither would file a bogus station at the band edge.
        val khz = rawHz.takeIf { it > 0 }?.div(1000)
            ?: _state.value.freqKhz.takeIf { sawFreqSinceScan && it > 0 }
            ?: return
        _state.update { it.copy(freqKhz = khz, seeking = true) }
        trace("  -> scanHit khz=$khz (rawHz=$rawHz)")
        onScanHit(khz)
    }

    // RDS PS/RT come as bytes in the RDS/EBU-Latin character set; for Europe ISO-8859-1 matches it
    // closely (the ROM uses iso8859-1 outside China). Drop control/padding bytes that render as junk.
    private fun decode(bytes: ByteArray?): String? =
        bytes?.let { String(it, Charsets.ISO_8859_1) }
            ?.filter { it.code >= 0x20 }
            ?.trim()
            ?.ifEmpty { null }

    private fun set(param: String) {
        // Trace outgoing tuner commands so the log shows the full request↔response: e.g. we sent
        // ctl_radio_frequency=100400 / ctl_radio_seek=up and can see whether a freq/seek_found event
        // ever comes back (the "frequency display never moves" symptom).
        if (param.startsWith("ctl_radio_") || param.startsWith("av_")) trace(">> set $param")
        // Direct CarManager if reflection worked, otherwise through the CanBusServer passthrough.
        val m = mSetParameters
        val c = car
        if (m != null && c != null) {
            try {
                m.invoke(c, param)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "setParameters('$param') failed: ${t.message}")
                trace(">> set FAILED $param: ${t.message}")
            }
        }
        val sink = paramSink
        if (sink != null) {
            val ok = try {
                sink(param)
            } catch (t: Throwable) {
                Log.w(TAG, "canbus setParameters('$param') failed: ${t.message}"); false
            }
            if (!ok) Log.w(TAG, "canbus setParameters('$param') returned false")
        }
    }

    private fun stringState(key: String): String? = try {
        mGetStringState?.invoke(car, key) as? String
    } catch (t: Throwable) {
        null
    }

    /** Read an MCU config string (CarManager.getParameters), e.g. "cfg_rds=" — null if unavailable. */
    private fun getParameters(key: String): String? = try {
        mGetParameters?.invoke(car, key) as? String
    } catch (t: Throwable) {
        null
    }

    private fun boolState(key: String): Boolean = try {
        (mGetBooleanState?.invoke(car, key) as? Boolean) ?: false
    } catch (t: Throwable) {
        false
    }

    private companion object {
        const val TAG = "FmController"
        const val RDS_TIMEOUT_MS = 10_000L   // no RDS for this long → drop the stale name (as the ROM)
        const val SNAPSHOT_MS = 3_000L       // periodic FM/RDS ground-truth snapshot interval
        const val TRACE_MAX_LINES = 4000      // bounded on-device event capture (klarwelle-fm.txt)
    }
}
