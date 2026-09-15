package com.px6.radio.ews

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.px6.radio.model.EwsAlertUi

/**
 * Full-screen overlay for a matched DAB Emergency Warning System (ASA) alert (ETSI TS 104 089 §7.6).
 * M2 shows the alert visually; audio handover to the alert sub-channel is a later milestone. A real
 * alert is red; the ASA home-test is amber and clearly marked as a test.
 *
 * The whole surface swallows touches so nothing behind it is operable while an alert is up; a large
 * "Schließen" area lets the user dismiss it (§7.6.4 user termination).
 */
@Composable
fun EwsAlertOverlay(alert: EwsAlertUi, onDismiss: () -> Unit) {
    val bg = if (alert.isTest) Color(0xFFE55100) else Color(0xFFB00020)      // vivid orange / emergency red
    val panel = if (alert.isTest) Color(0xFFB23F00) else Color(0xFF7F0016)   // the dismiss button
    // Absorb all touches (no ripple, no click-through) — nothing behind the alert is operable.
    val noRipple = remember { MutableInteractionSource() }
    // A warning has to be readable from the driver's seat whatever the screen: the text grows with
    // the panel width (reference: the 853 dp head unit), so a wide 12" display does not show a huge
    // orange box with small type in the middle.
    val cfg = LocalConfiguration.current
    val widthDp = cfg.screenWidthDp
    val portrait = cfg.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    val f = (widthDp / 853f).coerceIn(1f, 1.8f)
    fun fs(v: Int) = (v * f).sp
    Box(
        Modifier.fillMaxSize().background(Color(0xCC0B0E12))   // dark scrim around the ~90 % panel
            .clickable(interactionSource = noRipple, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            // Upright there is far more height than the text needs; 60 % keeps the panel a panel.
            Modifier.fillMaxWidth(0.9f).fillMaxHeight(if (portrait) 0.6f else 0.9f)
                .clip(RoundedCornerShape(28.dp)).background(bg)
                .padding(horizontal = 32.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        // The message area gets whatever is left AFTER the pinned footer and scrolls if it does not
        // fit. Before, everything was one fixed-height centred column, so a long message (or a
        // SlideShow) pushed the close button past the clipped bottom edge — on the car screen it was
        // only half visible and unreachable. The footer is now laid out first in the leftover space,
        // so the dismiss control is ALWAYS fully on screen (§7.6.4 user termination).
        Box(
            Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("⚠", color = Color.White, fontSize = fs(44))
            Text(
                if (alert.isTest) stringResource(com.px6.radio.R.string.ews_test_title)
                else stringResource(com.px6.radio.R.string.ews_alert_title),
                color = Color.White, fontSize = fs(30), fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
            Text(
                alert.stageName +
                    if (alert.otherEnsemble) " · " + stringResource(com.px6.radio.R.string.ews_other_ensemble) else "",
                color = Color(0xFFFFE0E0), fontSize = fs(20), textAlign = TextAlign.Center,
            )
            // §7.6.2: the alert service label, when known.
            alert.serviceLabel?.takeIf { it.isNotBlank() }?.let { label ->
                Text(label, color = Color.White, fontSize = fs(22), fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center)
            }
            // SlideShow image from the alert sub-channel (MOT), if the broadcast carries one.
            alert.slideshow?.let { bytes ->
                val img = remember(bytes) {
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
                }
                img?.let {
                    Image(it, contentDescription = null,
                        modifier = Modifier.heightIn(max = 200.dp).clip(RoundedCornerShape(12.dp)))
                }
            }
            // The actual broadcast message (dynamic label). Prominent when present; otherwise a generic
            // instruction. A test alert always shows the test notice.
            val message = alert.messageText?.takeIf { it.isNotBlank() }
            Text(
                when {
                    alert.isTest -> stringResource(com.px6.radio.R.string.ews_test_notice)
                    message != null -> message
                    else -> stringResource(com.px6.radio.R.string.ews_generic_warning)
                },
                color = Color.White, fontSize = if (message != null) 20.sp else 16.sp,
                fontWeight = if (message != null) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            )
            // When a real message is shown, keep the generic call-to-action as a secondary line.
            if (message != null && !alert.isTest) {
                Text(stringResource(com.px6.radio.R.string.ews_follow_authorities),
                    color = Color(0xFFFFE0E0), fontSize = fs(15), textAlign = TextAlign.Center)
            }
            // The decoder's raw one-liner ("FIG0/15 EWS TRIGGER SubChId=13 stage=L1-Start …") is
            // diagnostics, not a message. It stays in klarwelle-ews.txt and the ASA info panel; on
            // an emergency alert it only competes with the text that matters.
            if (com.px6.radio.BuildConfig.DEBUG) {
                Text(
                    alert.description,
                    color = Color(0xFFFFCDCD), fontSize = fs(12), textAlign = TextAlign.Center,
                )
            }
        }
        }
            // ---- pinned footer: how long the window still stands, then the dismiss control ----
            Spacer(Modifier.height(12.dp))
            TimeoutBar(alert)
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.clip(RoundedCornerShape(14.dp))
                    .background(panel)
                    .clickable { onDismiss() }
                    .padding(horizontal = 40.dp, vertical = 14.dp),
            ) {
                Text(stringResource(com.px6.radio.R.string.action_close),
                    color = Color.White, fontSize = fs(20), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * The deadline is pushed back roughly once a second for as long as the alert is still being
 * broadcast, so a gap longer than this means the transmission has actually stopped and the window is
 * genuinely counting down. Comfortably above that ~1 s re-arm cadence, so a brief reception dip does
 * not flash the bar on and off.
 */
private const val COUNTDOWN_VISIBLE_AFTER_MS = 3_000L

/**
 * Progress bar for the presentation timeout (§7.6.4): the alert closes itself when no further
 * Trigger / Sustain arrives within [EwsAlertUi.timeoutMs].
 *
 * It stays HIDDEN while the alert is still on air — there the deadline is re-armed about once a
 * second, so a bar would just sit at one end and mean nothing. It appears only once the re-arms
 * stop, i.e. when the window really is about to close. Its presence is therefore the message: no bar
 * = still being broadcast, bar = closing.
 *
 * It FILLS from empty to full as the window runs out, so it reads as "progress towards closing"
 * rather than as a draining reserve. The fill is measured from the moment the bar appears (the end
 * of the grace window), not from when the deadline was armed — otherwise it would pop into view
 * already a quarter full.
 */
@Composable
private fun TimeoutBar(alert: EwsAlertUi) {
    val running = alert.timeoutMs > 0L && alert.timeoutArmedAtMs > 0L
    // -1 = hidden (deadline still being pushed back). Recomputed on a cheap ticker rather than an
    // animation: the deadline moves, so there is no single target an animateFloat could run towards.
    var fraction by remember { mutableFloatStateOf(-1f) }
    // Keyed on the arm timestamp: every re-arm restarts this, which immediately hides the bar again.
    LaunchedEffect(alert.timeoutArmedAtMs, alert.timeoutMs, running) {
        if (!running) {
            fraction = -1f
            return@LaunchedEffect
        }
        // After the End phase the deadline is fixed and cannot be pushed back, so there is nothing
        // to wait out: show the bar from the first moment. The grace only makes sense while repeated
        // Trigger/Sustain frames could still re-arm the window.
        val grace = if (alert.ended) 0L else COUNTDOWN_VISIBLE_AFTER_MS
        // The visible span: from when the bar appears until the window actually closes.
        val span = (alert.timeoutMs - grace).coerceAtLeast(1L)
        while (true) {
            val since = SystemClock.elapsedRealtime() - alert.timeoutArmedAtMs
            fraction = if (since < grace) -1f
            else ((since - grace).toFloat() / span).coerceIn(0f, 1f)
            if (since >= alert.timeoutMs) break
            delay(100)
        }
    }
    // Reserve the height even while hidden, so the close button never shifts out from under a finger
    // at the moment the bar appears.
    Box(Modifier.fillMaxWidth().height(6.dp)) {
        if (fraction >= 0f) {
            Box(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0x33FFFFFF)),
            )
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xCCFFFFFF)),
            )
        }
    }
}
