package com.px6.radio.ui

import android.os.SystemClock
import com.github.takahirom.roborazzi.captureRoboImage
import com.px6.radio.ews.EwsAlertOverlay
import com.px6.radio.model.EwsAlertUi
import com.px6.radio.ui.theme.ModernDarkSkin
import com.px6.radio.ui.theme.Px6RadioTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Desk harness for the ASA/EWS alert overlay at the REAL head-unit resolution (the M.I.C. AV8V6 panel is
 * 1280x720 pixels at hdpi, i.e. only 853x480 dp). That difference is the whole point: the
 * overlay used to be one fixed-height centred column, so on the shorter car screen a normal alert
 * pushed the "Schließen" button past the clipped bottom edge — it was only half visible and could
 * not be pressed. These renders pin that down off-device.
 *
 * Record:
 *   ./gradlew :app:testDebugUnitTest --tests '*EwsOverlayScreenshotTest' -Proborazzi.test.record=true
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w853dp-h480dp-land-hdpi")
class EwsOverlayScreenshotTest {

    /**
     * Robolectric's elapsedRealtime() starts at 0, so "armed 6 s ago" would be a negative timestamp
     * (which the overlay correctly reads as "no timeout set"). Move the clock off zero first so the
     * drain arithmetic is exercised the way it runs on a real, long-booted head unit.
     */
    @org.junit.Before fun advanceClockOffZero() =
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(60))

    /** The alert exactly as photographed on the car screen (DokDeb ASA test transmission). */
    private fun realAlert() = EwsAlertUi(
        stageName = "Warnung",
        isTest = false,
        incidentId = 0,
        subChId = 13,
        otherEnsemble = false,
        serviceLabel = "DRadio DokDeb",
        description = "FIG0/15 EWS TRIGGER SubChId=13 stage=L1-Start incident=0 last loc=1",
        messageText = "Informationen am Abend mit Dirk Müller",
        timeoutArmedAtMs = SystemClock.elapsedRealtime(),
        timeoutMs = 12_000L,
    )

    private fun shoot(name: String, alert: EwsAlertUi) =
        captureRoboImage(filePath = "build/outputs/roborazzi/ews_$name.png") {
            Px6RadioTheme(ModernDarkSkin) { EwsAlertOverlay(alert) {} }
        }

    /**
     * The reported case, alert still on air: the close button must be fully visible, and there must
     * be NO timeout bar — the deadline is still being pushed back once a second, so a bar would say
     * nothing. Its absence is the "still being broadcast" signal.
     */
    @Test fun realAlertFitsOnCarScreen() = shoot("car_real", realAlert())

    /**
     * Transmission has stopped and the window is genuinely counting down: the bar appears and has
     * partly FILLED. Verifies both the visibility rule and the fill arithmetic — at 6 s since the
     * last re-arm, with a 3 s grace and a 12 s timeout, the visible span is 9 s and 3 s of it have
     * run, so the bar stands at one third.
     */
    @Test fun timeoutHalfElapsed() = shoot(
        "car_timeout_half",
        realAlert().copy(timeoutArmedAtMs = SystemClock.elapsedRealtime() - 6_000L),
    )

    /** Just inside the grace window (2 s since the last re-arm) — still counts as on air, no bar. */
    @Test fun barStillHiddenInsideGraceWindow() = shoot(
        "car_grace",
        realAlert().copy(timeoutArmedAtMs = SystemClock.elapsedRealtime() - 2_000L),
    )

    /**
     * Worst case for the layout: a long multi-line broadcast message. The message area must scroll
     * and the footer (bar + close button) must still be fully on screen.
     */
    @Test fun longMessageStillShowsCloseButton() = shoot(
        "car_long",
        realAlert().copy(
            messageText = "Schwere Unwetterlage mit Orkanböen und Starkregen im gesamten " +
                "Regierungsbezirk. Meiden Sie den Aufenthalt im Freien und suchen Sie feste " +
                "Gebäude auf. Rechnen Sie mit umstürzenden Bäumen, überfluteten Unterführungen " +
                "und erheblichen Behinderungen im Straßenverkehr bis in die frühen Morgenstunden.",
        ),
    )

    /**
     * The End phase, which is how a real alert actually finishes — verified against the device log:
     * of 62 presentations, 18 ended on an END frame, 3 on the user, and NONE on the silent timeout,
     * because the transmission re-arms the window with a SUSTAIN every second. The bar was therefore
     * never once shown in the field, while the window still closed itself mid-sentence.
     *
     * Now the End phase stops the audio (TS 104 089 §7.6.4) and leaves the message standing with no
     * deadline at all: no bar, and it goes away only when the user presses Close.
     */
    @Test fun endedStaysWithoutBar() = shoot(
        "car_ended",
        realAlert().copy(ended = true, timeoutArmedAtMs = 0L, timeoutMs = 0L),
    )

    /** The ASA home test — amber, with the test notice instead of a message. */
    @Test fun testAlert() = shoot(
        "car_test",
        realAlert().copy(isTest = true, stageName = "Test", messageText = null),
    )
}
