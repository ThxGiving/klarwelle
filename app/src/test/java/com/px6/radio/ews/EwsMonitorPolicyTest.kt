package com.px6.radio.ews

import com.px6.radio.model.Band
import com.px6.radio.model.FollowingState
import com.px6.radio.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guard + target-selection tests for the EWS idle-tuner parking decision (§7.2.3). */
class EwsMonitorPolicyTest {

    private fun dab(id: String) = Station(id, id, Band.DAB, "", "XX", 0, 0)
    private fun ip(id: String) = Station(id, id, Band.IP, "", "XX", 0, 0)

    private val ewsSet = setOf(0x100C, 0x10AB)   // two EWS-capable ensembles

    @Test fun parks_whenIdleOnIp_currentEnsembleNotEws() {
        assertTrue(EwsMonitorPolicy.shouldPark(
            asaEnabled = true, band = Band.IP, following = FollowingState.DAB_PRIMARY,
            ewsEnsembleIds = ewsSet, currentDabEnsembleId = 0xD210))   // parked on a non-EWS ensemble
    }

    @Test fun noPark_whenAsaOff() {
        assertFalse(EwsMonitorPolicy.shouldPark(false, Band.FM, FollowingState.DAB_PRIMARY, ewsSet, 0xD210))
    }

    @Test fun noPark_whenAudibleBandIsDab() {
        // DAB is the listened band → the tuner is in use, never repurpose it.
        assertFalse(EwsMonitorPolicy.shouldPark(true, Band.DAB, FollowingState.DAB_PRIMARY, ewsSet, 0xD210))
    }

    @Test fun noPark_whenFollowingUsesTheTuner() {
        assertFalse(EwsMonitorPolicy.shouldPark(true, Band.FM, FollowingState.FM_FALLBACK, ewsSet, 0xD210))
        assertFalse(EwsMonitorPolicy.shouldPark(true, Band.IP, FollowingState.IP_FALLBACK, ewsSet, 0xD210))
    }

    @Test fun noPark_whenNoEwsEnsembleKnown() {
        // The user's rule: if there is no EWS-capable ensemble, nothing happens.
        assertFalse(EwsMonitorPolicy.shouldPark(true, Band.FM, FollowingState.DAB_PRIMARY, emptySet(), 0xD210))
    }

    @Test fun noPark_whenAlreadyOnEwsEnsemble() {
        // The tuner already sits on an EWS ensemble → already monitoring, don't retune.
        assertFalse(EwsMonitorPolicy.shouldPark(true, Band.FM, FollowingState.DAB_PRIMARY, ewsSet, 0x100C))
    }

    @Test fun pickTarget_choosesStationInEwsEnsemble() {
        val stations = listOf(dab("d210.0001"), dab("10ab.0007"), ip("ip.foo"))
        val t = EwsMonitorPolicy.pickParkTarget(stations, ewsSet)
        assertEquals("10ab.0007", t?.id)
    }

    @Test fun pickTarget_nullWhenNoStationInEwsEnsemble() {
        val stations = listOf(dab("d210.0001"), ip("ip.foo"))
        assertNull(EwsMonitorPolicy.pickParkTarget(stations, ewsSet))
    }

    @Test fun parkTargetFor_endToEnd_positive() {
        val stations = listOf(dab("d210.0001"), dab("100c.0002"))
        val t = EwsMonitorPolicy.parkTargetFor(
            asaEnabled = true, band = Band.IP, following = FollowingState.DAB_PRIMARY,
            ewsEnsembleIds = ewsSet, currentDabStationId = "d210.0001", stations = stations)
        assertEquals("100c.0002", t?.id)   // park on the EWS ensemble's station
    }

    @Test fun parkTargetFor_nullWhenGuardsFail() {
        val stations = listOf(dab("100c.0002"))
        // On DAB → no parking even though an EWS station exists.
        assertNull(EwsMonitorPolicy.parkTargetFor(
            true, Band.DAB, FollowingState.DAB_PRIMARY, ewsSet, "d210.0001", stations))
    }

    // ---- cold start: nothing learned yet (the chicken-and-egg escape hatch) ----

    @Test fun noPark_whenNothingKnown_andDiscoveryOff() {
        // The original rule: without a known EWS ensemble, nothing happens.
        assertFalse(EwsMonitorPolicy.shouldPark(
            asaEnabled = true, band = Band.IP, following = FollowingState.DAB_PRIMARY,
            ewsEnsembleIds = emptySet(), currentDabEnsembleId = null))
    }

    @Test fun parks_whenNothingKnown_andDiscoveryOn() {
        // A first-ever run can only LEARN which ensembles carry EWS by decoding one, so with
        // discovery enabled we must be willing to park on an unproven ensemble.
        assertTrue(EwsMonitorPolicy.shouldPark(
            asaEnabled = true, band = Band.IP, following = FollowingState.DAB_PRIMARY,
            ewsEnsembleIds = emptySet(), currentDabEnsembleId = null, allowDiscovery = true))
    }

    @Test fun discovery_neverOverridesTheHardGuards() {
        // Discovery must not punch through "ASA off" or "DAB is the audible band".
        assertFalse(EwsMonitorPolicy.shouldPark(
            false, Band.IP, FollowingState.DAB_PRIMARY, emptySet(), null, allowDiscovery = true))
        assertFalse(EwsMonitorPolicy.shouldPark(
            true, Band.DAB, FollowingState.DAB_PRIMARY, emptySet(), null, allowDiscovery = true))
        assertFalse(EwsMonitorPolicy.shouldPark(
            true, Band.FM, FollowingState.FM_FALLBACK, emptySet(), null, allowDiscovery = true))
    }

    @Test fun discovery_picksAnyDabStation_neverAnIpOne() {
        val stations = listOf(ip("ip.foo"), dab("d210.0001"))
        val t = EwsMonitorPolicy.pickParkTarget(stations, emptySet(), allowDiscovery = true)
        assertEquals("d210.0001", t?.id)
    }

    @Test fun discovery_skipsEnsemblesAlreadyProvenSilent() {
        val stations = listOf(dab("d210.0001"), dab("d211.0002"), dab("d212.0003"))
        // d210 and d211 were parked on and produced no heartbeat → sweep must advance to d212.
        val t = EwsMonitorPolicy.pickParkTarget(
            stations, emptySet(), allowDiscovery = true, excludeEnsembles = setOf(0xD210, 0xD211))
        assertEquals("d212.0003", t?.id)
    }

    @Test fun discovery_nullOnceEveryEnsembleTried() {
        val stations = listOf(dab("d210.0001"), dab("d211.0002"))
        assertNull(EwsMonitorPolicy.pickParkTarget(
            stations, emptySet(), allowDiscovery = true, excludeEnsembles = setOf(0xD210, 0xD211)))
    }

    @Test fun knownEwsEnsembleAlwaysBeatsAnUnprovenOne() {
        // Even mid-discovery, a station in a *known* EWS ensemble must win over an unexplored one.
        val stations = listOf(dab("d210.0001"), dab("100c.0002"))
        val t = EwsMonitorPolicy.pickParkTarget(stations, ewsSet, allowDiscovery = true)
        assertEquals("100c.0002", t?.id)
    }

    @Test fun allEnsembles_listsDistinctDabEnsemblesOnly() {
        val stations = listOf(dab("d210.0001"), dab("d210.0002"), dab("100c.0003"), ip("ip.foo"))
        assertEquals(setOf(0xD210, 0x100C), EwsMonitorPolicy.allEnsembles(stations))
    }
}
