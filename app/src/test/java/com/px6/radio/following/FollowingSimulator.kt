package com.px6.radio.following

import com.px6.radio.audio.AudioRouter
import com.px6.radio.dab.DabState
import com.px6.radio.dab.FmLink
import com.px6.radio.fm.FmState
import com.px6.radio.model.Station
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.File
import org.junit.Test

/**
 * Desk simulator for DAB->FM->IP service following. Drives the **real** [ServiceFollowingEngine]
 * and [FollowingDecider] with fake DAB/FM sources and a logging router, so the whole handover
 * behaviour can be observed on a PC with no omri, no CarManager and no car.
 *
 * It lives in the **test** source set, so it is never part of the shipped APK.
 *
 * Drive it with a scenario file of one command per line (see [DEFAULT_SCENARIO] for the syntax);
 * change the file and re-run to change values "from outside" live:
 *
 *   ./gradlew :app:testReleaseUnitTest --tests '*FollowingSimulator*' -Dpx6.scenario=/path/to/scn.txt
 *
 * With no `-Dpx6.scenario` it runs the built-in demo. Output goes to stdout and to
 * `app/build/following-sim-out.txt` (reliable regardless of Gradle's log capture).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowingSimulator {

    // ---- fake sources ------------------------------------------------------

    private class FakeDab : DabFollowSource {
        val flow = MutableStateFlow(DabState())
        override val state: StateFlow<DabState> = flow
        private val reports = MutableSharedFlow<Int>(extraBufferCapacity = 64)
        override val signalReports: SharedFlow<Int> = reports
        var fmLink: FmLink? = null
        var dabAlts: List<String> = emptyList()
        val tuned = mutableListOf<String>()
        override fun getLinkedDab() = dabAlts
        override fun getLinkedFm() = fmLink
        override fun tune(stationId: String) { tuned += stationId }
        private var nonce = 0
        /** Push a reading. Bumps signalSeq so equal-bars readings still count as separate ticks (StateFlow
         *  conflates equal values, but following needs to see each debounce tick). */
        fun push(bars: Int, nowId: String?) {
            nonce++
            flow.value = DabState(signalBars = bars, nowPlayingId = nowId)
            reports.tryEmit(bars)
        }
    }

    private class FakeFm(override val available: Boolean) : FmFollowSource {
        val flow = MutableStateFlow(FmState(available = available))
        override val state: StateFlow<FmState> = flow
        val tuned = mutableListOf<Int>()
        override fun tune(khz: Int) { tuned += khz }
        fun setPi(pi: Int?) { flow.value = flow.value.copy(pi = pi) }
    }

    @Test
    fun simulate() = runTest(UnconfinedTestDispatcher()) {
        val out = StringBuilder()
        fun emit(line: String) { println(line); out.appendLine(line) }

        // Scenario source, in order: -Dpx6.scenario=<path>, then a plain file you can edit and
        // re-run (the "change values from outside" loop), then the built-in demo. Candidate files
        // are resolved relative to the test working dir (the module or repo root).
        val candidates = listOfNotNull(
            System.getProperty("px6.scenario"),
            "following-sim.txt", "app/following-sim.txt", "../following-sim.txt",
        )
        val scenarioFile = candidates.map { File(it) }.firstOrNull { it.exists() }
        val scenario = scenarioFile?.readText() ?: DEFAULT_SCENARIO
        emit("=== px6 following simulator ===  source=${scenarioFile?.absolutePath ?: "built-in demo"}")

        var fmAvailable = true
        // Parse config up front so the fake FM's availability is fixed at construction.
        scenario.lineSequence().firstOrNull { it.trim().startsWith("config") }?.let { cfg ->
            kv(cfg)["fm"]?.let { fmAvailable = it == "on" || it == "true" }
        }

        val dab = FakeDab()
        val fm = FakeFm(fmAvailable)
        val streams = HashMap<String, String>()
        val actions = mutableListOf<String>()
        val router = AudioRouter(
            setDabVolume = { v -> if (v == 0f) actions += "DAB.mute" else if (v == 1f) actions += "DAB.full" },
            enterAnalog = { actions += "FM.enter" },
            exitAnalog = { actions += "FM.exit" },
            startIp = { url -> actions += "IP.start($url)" },
            stopIp = { actions += "IP.stop" },
        )
        val engine = ServiceFollowingEngine(dab, fm, router, backgroundScope)
        engine.setStreamProvider { id -> streams[id] }
        // Optional per-reading trace of the flags that drove each decision — enable with a
        // "trace on" line in the scenario.
        if (scenario.lineSequence().any { it.trim() == "trace on" }) {
            engine.debugTrace = { emit("    · $it") }
        }
        backgroundScope.launch {
            try { engine.run() } catch (t: Throwable) { emit("!! engine.run threw: ${t.stackTraceToString().lineSequence().take(6).joinToString(" | ")}") }
        }

        var nowId: String? = null
        var tick = 0

        scenario.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(Regex("\\s+"))
            when (parts[0]) {
                "config" -> {
                    val m = kv(line)
                    engine.configure(
                        enabled = m["fm"].on() || m["ip"].on() || m["dabdab"].on() || (m["following"]?.let { it.on() } ?: true),
                        threshold = m["threshold"]?.toIntOrNull() ?: 2,
                        softFade = m["softfade"]?.on() ?: true,
                        dabDab = m["dabdab"]?.on() ?: false,
                        ipEnabled = m["ip"]?.on() ?: false,
                    )
                    emit("config: $m")
                }
                "play" -> { nowId = parts.getOrNull(1); dab.push(4, nowId); advanceUntilIdle(); actions.clear()
                    emit("play $nowId (strong)") }
                "linkfm" -> {
                    dab.fmLink = if (parts.getOrNull(1) == "none" || parts.size < 3) null
                        else FmLink(parts[1].toInt(), parts[2].toInt(16))
                    emit("linkfm -> ${dab.fmLink}")
                }
                "linkdab" -> {
                    dab.dabAlts = if (parts.getOrNull(1) == "none" || parts.size < 2) emptyList()
                        else parts[1].split(",")
                    emit("linkdab -> ${dab.dabAlts}")
                }
                "ip" -> {
                    val id = nowId
                    if (parts.getOrNull(1) == "none" || parts.size < 2) { if (id != null) streams.remove(id) }
                    else if (id != null) streams[id] = parts[1]
                    emit("ip -> ${id?.let { streams[it] } ?: "none"}")
                }
                "fmpi" -> {
                    val p = parts.getOrNull(1)
                    fm.setPi(if (p == null || p == "none") null else p.toInt(16))
                    emit("fmpi -> ${p ?: "none"}")
                }
                "trace" -> {}   // handled at setup
                "bars" -> {
                    val n = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    actions.clear()
                    dab.push(n, nowId)
                    advanceUntilIdle()
                    val st = engine.state.value
                    val act = if (actions.isEmpty()) "-" else actions.joinToString(", ")
                    emit("tick#${++tick} bars=$n -> following=${st.following} muted=${st.muted} noCov=${st.noDabCoverage} | router: $act")
                }
                else -> emit("? unknown: $line")
            }
        }
        emit("=== fm tuned: ${fm.tuned} | dab tuned: ${dab.tuned} ===")

        runCatching { File("build/following-sim-out.txt").apply { parentFile?.mkdirs() }.writeText(out.toString()) }
    }

    private fun String?.on() = this == "on" || this == "true"
    private fun kv(line: String): Map<String, String> =
        line.split(Regex("\\s+")).drop(1).mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()

    companion object {
        /**
         * Built-in demo: full DAB->FM->IP->mute cascade with recovery at each tier. `softfade=off`
         * because the crossfade's real-time delays don't advance under the test scheduler and would
         * stall the reading loop — fade timing is cosmetic, the decisions/routing are what we verify.
         * Add `trace on` to see the per-reading decision flags.
         */
        private val DEFAULT_SCENARIO = """
            config threshold=2 fm=on ip=on softfade=off
            play 10bc.d210
            linkfm 98800 D210
            fmpi D210
            # --- weak DAB, FM link present -> hand over to FM (after debounce) ---
            bars 3
            bars 1
            bars 1
            # --- DAB recovers -> back to DAB ---
            bars 4
            bars 4
            # --- no FM link now, but IP available -> hand over to IP ---
            linkfm none
            ip https://st01.sslstream.dlf.de/dlf/01/mid/aac/stream.aac
            bars 1
            bars 1
            bars 4
            bars 4
            # --- neither FM nor IP -> mute on dead signal, unmute on recovery ---
            ip none
            bars 0
            bars 0
            bars 4
            # --- REFINEMENT: FM link exists but FM is weak (never locks the PI) + IP available:
            #     hand to FM, then escalate FM->IP once it fails to settle ---
            play 10bc.d210
            linkfm 98800 D210
            ip https://st01.sslstream.dlf.de/dlf/01/mid/aac/stream.aac
            fmpi none
            bars 1
            bars 1
            bars 1
            bars 1
            bars 1
            bars 4
            bars 4
            # --- contrast: FM link that DOES lock its PI stays on FM (no needless IP/data) ---
            play 10bc.d220
            linkfm 95500 D220
            ip https://st02.sslstream.dlf.de/dlf/02/mid/aac/stream.aac
            fmpi D220
            bars 1
            bars 1
            bars 1
            bars 1
        """.trimIndent()
    }
}
