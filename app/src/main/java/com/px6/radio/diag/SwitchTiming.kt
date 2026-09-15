package com.px6.radio.diag

import android.content.Context
import android.os.SystemClock

/**
 * Millisecond timeline of a source switch — mainly the (slow) IP→DAB handover — so we can SEE where
 * the "press the tile, nothing happens for a while" time actually goes and optimise it, instead of
 * guessing. Every component on the path ([com.px6.radio.vm.RadioViewModel] audio worker,
 * [com.px6.radio.dab.DabController.tune] = omri startRadioService, [com.px6.radio.audio.DabAudioSink]
 * first PCM frame, the IP player stop) calls [mark] with the same shared t0, and the lines land in
 * `klarwelle-timing.txt` as `+<Δms> <label>`.
 *
 * Monotonic clock (elapsedRealtime), single active switch at a time (a new [start] resets it).
 */
object SwitchTiming {

    @Volatile private var t0 = 0L
    @Volatile private var what = ""

    /** Begin timing a new switch. All later [mark]s are relative to this instant. */
    fun start(ctx: Context, from: String?, to: String) {
        t0 = SystemClock.elapsedRealtime()
        what = "${from ?: "?"} -> $to"
        write(ctx, "\n=== switch $what @${clock()} ===")
    }

    /** Log one timeline event (Δ since [start]). No-op if no switch is being timed. */
    fun mark(ctx: Context, event: String) {
        val base = t0
        if (base == 0L) return
        write(ctx, "+${SystemClock.elapsedRealtime() - base}ms  $event")
    }

    /** End the current switch (Δ total), then stop timing until the next [start]. */
    fun done(ctx: Context, event: String = "audible") {
        val base = t0
        if (base == 0L) return
        write(ctx, "+${SystemClock.elapsedRealtime() - base}ms  DONE ($event) — total for $what")
        t0 = 0L
    }

    private fun write(ctx: Context, line: String) =
        runCatching { com.px6.radio.diag.Diag.write(ctx, DiagFile.TIMING, line + "\n", append = true) }

    private fun clock(): String {
        val ms = System.currentTimeMillis()
        val s = ms / 1000
        return "%02d:%02d:%02d".format((s / 3600) % 24, (s / 60) % 60, s % 60)
    }
}
