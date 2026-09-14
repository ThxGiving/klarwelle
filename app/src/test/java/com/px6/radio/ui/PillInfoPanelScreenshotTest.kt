package com.px6.radio.ui

import com.github.takahirom.roborazzi.captureRoboImage
import com.px6.radio.model.AsaStatus
import com.px6.radio.model.Band
import com.px6.radio.model.RadioUiState
import com.px6.radio.model.Settings
import com.px6.radio.model.Station
import com.px6.radio.ui.theme.ModernDarkSkin
import com.px6.radio.ui.theme.Px6RadioTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the shared status-pill info panel at the real head-unit size. The ASA case cannot be
 * reached on an emulator — without a DAB stick `computeAsaStatus` returns OFF and the pill is not
 * even drawn — so this harness is the only way to review that content off-device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w853dp-h480dp-land-hdpi")
class PillInfoPanelScreenshotTest {

    private fun dab(id: String, name: String, ensemble: String) =
        Station(id, name, Band.DAB, "", "XX", 0, 0, ensemble = ensemble)

    private val stations = listOf(
        dab("10bc.d240", "DRadio DokDeb", "Deutschlandradio"),
        dab("10bc.d220", "Deutschlandfunk", "Deutschlandradio"),
        dab("10ec.db94", "WDR 4", "WDR"),
    )

    private fun state(
        asa: AsaStatus = AsaStatus.ACTIVE,
        tunerEid: Int? = 0x10BC,
    ) = RadioUiState(
        demoMode = false,
        selectedBand = Band.IP,
        stations = stations,
        dabPresent = true,
        signalBars = 3,
        asaStatus = asa,
        ewsEnsembleIds = setOf(0x10BC),
        currentTunerEnsembleId = tunerEid,
        settings = Settings(
            asaEnabled = true,
            asaTestAlerts = true,
            asaLocationCodes = listOf("1253-3513-3668"),
        ),
    )

    private fun shoot(name: String, topic: PillTopic, s: RadioUiState) =
        captureRoboImage(filePath = "build/outputs/roborazzi/pill_$name.png") {
            Px6RadioTheme(ModernDarkSkin) { PillInfoCard(topic, s) {} }
        }

    /** The whole point of the panel: "ASA" spelled out and explained, plus the live technical state. */
    @Test fun asaActive() = shoot("asa_active", PillTopic.ASA, state())

    /** Red state, and no location code — the two things a user would be checking after a red pill. */
    @Test fun asaInactive() = shoot(
        "asa_inactive", PillTopic.ASA,
        state(asa = AsaStatus.INACTIVE, tunerEid = null).let {
            it.copy(settings = it.settings.copy(asaLocationCodes = emptyList(), asaTestAlerts = false))
        },
    )

    /** Stick hardware. In Robolectric no USB device exists, so this shows the "not found" path. */
    @Test fun dabStick() = shoot("dab", PillTopic.DAB, state())
}
