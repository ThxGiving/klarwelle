package com.px6.radio.ews

import com.px6.radio.model.Band
import com.px6.radio.model.EwsAlertUi
import com.px6.radio.model.Settings
import com.px6.radio.model.Station
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.omri.tuner.DabEwsAlert

/**
 * The ASA lifecycle without a tuner: FIG frames in, overlay/audio/log decisions out. The virtual
 * clock is the test scheduler's, so the 12 s safety timeout takes no real time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EwsAlertEngineTest {

    /** Fake radio: records what the engine asked for. */
    private class FakeHost : EwsAlertEngine.Host {
        override var settings = Settings(asaEnabled = true, asaTestAlerts = true)
        override var stations = listOf(
            Station("100c.d3c2", "WDR 2", Band.DAB, "", "W2", 0, 0, ensemble = "WDR NRW", bitrateKbps = 96),
            Station("100c.d220", "DokDeb", Band.DAB, "", "DD", 0, 0, ensemble = "WDR NRW", bitrateKbps = 64),
            Station("fm.102300", "Antenne", Band.FM, "", "AN", 0, 0, frequencyKhz = 102_300),
        )
        override var gpsCode: EwsMatcher.Code? = null
        override var alert: EwsAlertUi? = null
        override fun updateAlert(transform: (EwsAlertUi?) -> EwsAlertUi?) { alert = transform(alert) }
        val history = mutableListOf<String>()
        override fun addHistory(line: String, max: Int) { history.add(0, line); while (history.size > max) history.removeLast() }
        override var nowPlayingStationId: String? = "fm.102300"
        /** subCh → station id; the alert channel 3 is DokDeb, 9 is unknown. */
        override fun findServiceBySubChannel(subCh: Int): String? = if (subCh == 3) "100c.d220" else null
        var audible: String? = null
        override fun isAudiblyPlaying(serviceId: String) = audible == serviceId
        val played = mutableListOf<String>()
        override fun playAlertService(serviceId: String) { played += "alert:$serviceId"; audible = serviceId }
        override fun playStation(station: Station) { played += "station:${station.id}"; audible = null }
        var foregrounds = 0
        override fun bringToForeground() { foregrounds++ }
        override fun stageName(stage: Int, isTest: Boolean) = if (isTest) "Test" else "Warnung"
        val log = mutableListOf<String>()
        override fun log(text: String) { log += text }
        override fun clockText() = "12:00"
    }

    private fun alert(
        form: Int = DabEwsAlert.FORM_TRIGGER, subCh: Int = 3, incident: Int = 1,
        stage: Int = DabEwsAlert.STAGE_LEVEL1_START, test: Boolean = false, locations: Array<String> = emptyArray(),
        otherEnsemble: Boolean = false, idValue: Int = subCh,
    ) = DabEwsAlert(form, otherEnsemble, idValue, stage, incident, test, false, "alert $incident", locations, 0x100C)

    private fun TestScope.engine(host: FakeHost, timeout: Long = 12_000L) =
        EwsAlertEngine(this, host, timeoutMs = timeout, now = { testScheduler.currentTime })

    @Test
    fun `trigger presents, hands audio over and remembers where to return`() = runTest {
        val host = FakeHost(); val e = engine(host)
        e.onAlert(alert())
        assertNotNull(host.alert)
        assertEquals("DokDeb", host.alert!!.serviceLabel)
        assertEquals(listOf("alert:100c.d220"), host.played)
        assertEquals(1, host.foregrounds)
        assertEquals("Warnung · DokDeb · Vorfall 1", host.history.first())
        assertTrue(host.log.any { it.startsWith("MATCH") })
    }

    @Test
    fun `repeated trigger only refreshes the timeout`() = runTest {
        val host = FakeHost(); val e = engine(host)
        e.onAlert(alert())
        repeat(30) { e.onAlert(alert()) }
        assertEquals(1, host.foregrounds)
        assertEquals(1, host.played.size)
        assertEquals(1, host.log.count { it.startsWith("MATCH") })
    }

    @Test
    fun `silence for the timeout clears the overlay and restores audio`() = runTest {
        val host = FakeHost(); val e = engine(host)
        e.onAlert(alert())
        advanceTimeBy(11_000); assertNotNull(host.alert)
        advanceTimeBy(2_000)
        assertNull(host.alert)
        assertEquals(listOf("alert:100c.d220", "station:fm.102300"), host.played)
    }

    @Test
    fun `sustain keeps it alive past the timeout`() = runTest {
        val host = FakeHost(); val e = engine(host)
        e.onAlert(alert())
        advanceTimeBy(10_000); e.onAlert(alert(form = DabEwsAlert.FORM_SUSTAIN))
        advanceTimeBy(10_000)
        assertNotNull(host.alert)
    }

    @Test
    fun `end phase restores audio but leaves the message until the user closes it`() = runTest {
        val host = FakeHost(); val e = engine(host)
        e.onAlert(alert())
        e.onAlert(alert(form = DabEwsAlert.FORM_END))
        assertEquals(listOf("alert:100c.d220", "station:fm.102300"), host.played)
        assertTrue(host.alert!!.ended)
        assertEquals(0L, host.alert!!.timeoutMs)
        advanceTimeBy(60_000)
        assertNotNull("no countdown after End", host.alert)
        e.dismiss()
        assertNull(host.alert)
    }

    @Test
    fun `broadcast resuming after End brings the audio back`() = runTest {
        val host = FakeHost(); val e = engine(host)
        e.onAlert(alert())
        e.onAlert(alert(form = DabEwsAlert.FORM_END))
        e.onAlert(alert())                       // same incident, Trigger again
        assertFalse(host.alert!!.ended)
        assertEquals(listOf("alert:100c.d220", "station:fm.102300", "alert:100c.d220"), host.played)
    }

    @Test
    fun `user dismissal holds against the repeat burst but not against an escalation`() = runTest {
        val host = FakeHost(); val e = engine(host)
        e.onAlert(alert())
        e.dismiss()
        assertNull(host.alert)
        assertEquals("station:fm.102300", host.played.last())
        repeat(20) { e.onAlert(alert()) }
        assertNull("dismissed alert must not spring back", host.alert)
        e.onAlert(alert(stage = DabEwsAlert.STAGE_LEVEL1_CRITICAL))
        assertNotNull("escalation breaks through", host.alert)
    }

    @Test
    fun `dismissal is forgotten once the broadcast stops`() = runTest {
        val host = FakeHost(); val e = engine(host)
        e.onAlert(alert()); e.dismiss()
        e.onAlert(alert())                       // still on air: suppressed, but the timeout is armed
        assertNull(host.alert)
        advanceTimeBy(13_000)                    // broadcast stops → timeout runs out → dismissal forgotten
        e.onAlert(alert())
        assertNotNull(host.alert)
    }

    @Test
    fun `already listening to the alert service means no switch and no restore`() = runTest {
        val host = FakeHost(); host.audible = "100c.d220"; host.nowPlayingStationId = "100c.d220"
        val e = engine(host)
        e.onAlert(alert())
        assertTrue(host.played.isEmpty())
        e.onAlert(alert(form = DabEwsAlert.FORM_END))
        assertTrue("nothing to restore", host.played.isEmpty())
    }

    @Test
    fun `unknown sub-channel shows the overlay without touching audio`() = runTest {
        val host = FakeHost(); val e = engine(host)
        e.onAlert(alert(subCh = 9))
        assertNotNull(host.alert)
        assertNull(host.alert!!.serviceLabel)
        assertTrue(host.played.isEmpty())
    }

    @Test
    fun `location mismatch is rejected once and explained in the log`() = runTest {
        val host = FakeHost(); host.settings = host.settings.copy(asaLocationCodes = listOf("1253-3513-3668"))
        val e = engine(host)
        repeat(5) { e.onAlert(alert(locations = arrayOf("10:B736BB"))) }
        assertNull(host.alert)
        assertEquals(1, host.log.count { it.startsWith("kein Treffer") })
    }

    @Test
    fun `test stage needs the test-alerts setting`() = runTest {
        val host = FakeHost(); host.settings = host.settings.copy(asaTestAlerts = false)
        val e = engine(host)
        e.onAlert(alert(stage = EwsMatcher.STAGE_TEST, test = true))
        assertNull(host.alert)
        host.settings = host.settings.copy(asaTestAlerts = true)
        e.onAlert(alert(stage = EwsMatcher.STAGE_TEST, test = true, incident = 2))
        assertEquals("Test", host.alert!!.stageName)
    }

    @Test
    fun `asa switched off drops everything and says so once`() = runTest {
        val host = FakeHost(); host.settings = host.settings.copy(asaEnabled = false)
        val e = engine(host)
        repeat(3) { e.onAlert(alert()) }
        assertNull(host.alert)
        assertEquals(1, host.log.size)
    }

    @Test
    fun `other-ensemble alert is shown only for a known ensemble and never sounded`() = runTest {
        val host = FakeHost(); val e = engine(host)
        e.onAlert(alert(otherEnsemble = true, idValue = 0x1234))
        assertNull("unknown ensemble", host.alert)
        e.onAlert(alert(otherEnsemble = true, idValue = 0x100C))
        assertNotNull(host.alert)
        assertTrue(host.alert!!.otherEnsemble)
        assertTrue(host.played.isEmpty())
    }
}
