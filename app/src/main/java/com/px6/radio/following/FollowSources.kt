package com.px6.radio.following

import com.px6.radio.dab.DabState
import com.px6.radio.dab.FmLink
import com.px6.radio.fm.FmState
import kotlinx.coroutines.flow.StateFlow

/**
 * The narrow slices of the DAB and FM controllers that [ServiceFollowingEngine] actually depends
 * on. Splitting them out lets the engine be driven by a fake source in a plain JVM simulation
 * (see the following simulator in the test sources) instead of the real omri/CarManager stack —
 * so the whole DAB->FM->IP behaviour can be observed and stepped on a PC, with no hardware.
 *
 * [com.px6.radio.dab.DabController] and [com.px6.radio.fm.FmController] implement these directly
 * (the members already match); nothing about production wiring changes.
 */
interface DabFollowSource {
    val state: StateFlow<DabState>
    /** One event per reception report (see DabController.signalReports) — the debounce counts these,
     *  not state emissions, and it must see repeats of the same bar count. */
    val signalReports: kotlinx.coroutines.flow.SharedFlow<Int>
    fun getLinkedDab(): List<String>
    fun getLinkedFm(): FmLink?
    fun tune(stationId: String)
}

interface FmFollowSource {
    val available: Boolean
    val state: StateFlow<FmState>
    fun tune(khz: Int)
}
