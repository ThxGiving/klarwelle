package com.px6.radio.ews

import com.px6.radio.model.Band
import com.px6.radio.model.FollowingState
import com.px6.radio.model.Station

/**
 * Pure decision logic for the EWS idle-tuner monitoring (ETSI TS 104 089 §7.2.3): whether to park the
 * free DAB tuner on an EWS ensemble while FM/Internet is the audible source, and which station to
 * park on. Kept side-effect-free so the guard rules — the part most likely to hold a subtle bug that
 * would disturb normal playback — are JVM-unit-tested without any Android or hardware.
 */
object EwsMonitorPolicy {

    /** Parse the ensemble EId (hex) from a DAB station id "eid.sid"; null for non-DAB or malformed. */
    fun ensembleOf(station: Station): Int? =
        if (station.band == Band.DAB) station.id.substringBefore('.').toIntOrNull(16) else null

    private fun ensembleOf(stationId: String?): Int? =
        stationId?.substringBefore('.')?.toIntOrNull(16)

    /**
     * Should the idle DAB tuner be parked on an EWS ensemble now? True only when ASA is on, DAB is NOT
     * the audible band, service following is not already using the DAB tuner, and the ensemble the
     * tuner currently sits on is not already a known EWS one.
     *
     * [allowDiscovery] governs the case where NO EWS ensemble is known yet. Without it this returns
     * false — nothing happens. That is a chicken-and-egg trap on a first run: an ensemble is only ever
     * learned to be EWS-capable *while the tuner is decoding its FIC*, so a receiver that never parks
     * because it knows nothing can never come to know anything. With discovery on we park on any DAB
     * ensemble instead, which starts FIC decoding and lets a FIG 0/15 heartbeat prove (or disprove)
     * that ensemble. [excludeEnsembles] are the ones already tried and found silent.
     */
    fun shouldPark(
        asaEnabled: Boolean,
        band: Band,
        following: FollowingState,
        ewsEnsembleIds: Set<Int>,
        currentDabEnsembleId: Int?,
        allowDiscovery: Boolean = false,
    ): Boolean {
        if (!asaEnabled) return false
        if (band == Band.DAB) return false
        if (following == FollowingState.FM_FALLBACK || following == FollowingState.IP_FALLBACK) return false
        if (ewsEnsembleIds.isEmpty()) return allowDiscovery
        if (currentDabEnsembleId != null && currentDabEnsembleId in ewsEnsembleIds) return false
        return true
    }

    /**
     * The DAB station to park on: the first one in a known EWS ensemble. Falling back, when
     * [allowDiscovery] is set, to the first DAB station in any ensemble not in [excludeEnsembles] —
     * so an unexplored ensemble gets a chance to reveal itself as EWS-capable. Null if nothing fits.
     */
    fun pickParkTarget(
        stations: List<Station>,
        ewsEnsembleIds: Set<Int>,
        allowDiscovery: Boolean = false,
        excludeEnsembles: Set<Int> = emptySet(),
    ): Station? =
        stations.firstOrNull { ensembleOf(it) in ewsEnsembleIds }
            ?: if (allowDiscovery) {
                stations.firstOrNull { it.band == Band.DAB && ensembleOf(it) !in excludeEnsembles }
            } else null

    /**
     * Convenience: the park target given the full context, or null when parking should not happen
     * (guards fail) or no suitable station exists. [currentDabStationId] is the tuner's current
     * logical station ("eid.sid").
     */
    fun parkTargetFor(
        asaEnabled: Boolean,
        band: Band,
        following: FollowingState,
        ewsEnsembleIds: Set<Int>,
        currentDabStationId: String?,
        stations: List<Station>,
        allowDiscovery: Boolean = false,
        excludeEnsembles: Set<Int> = emptySet(),
    ): Station? {
        if (!shouldPark(asaEnabled, band, following, ewsEnsembleIds,
                ensembleOf(currentDabStationId), allowDiscovery)) return null
        return pickParkTarget(stations, ewsEnsembleIds, allowDiscovery, excludeEnsembles)
    }

    /** All distinct ensembles present in the tuning list — the search space for discovery. */
    fun allEnsembles(stations: List<Station>): Set<Int> =
        stations.mapNotNull { ensembleOf(it) }.toSet()
}
