package com.px6.radio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.px6.radio.R
import com.px6.radio.model.AsaStatus
import com.px6.radio.model.Band
import com.px6.radio.model.RadioUiState
import com.px6.radio.ui.theme.appColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Which status-bar pill an info panel was opened from. The pills are deliberately terse (they sit
 * next to the clock on a car screen), so this is where the abbreviations get spelled out and the
 * underlying technical state is shown — "what does ASA even mean" answered in one tap rather than by
 * making the pill itself long.
 */
enum class PillTopic { ASA, DAB, FM, AM, INTERNET }

/**
 * Shared detail panel behind every status-bar pill. One layout, one dismiss control; only the
 * content differs per [PillTopic], so a new pill needs a content block and nothing else.
 */
@Composable
fun PillInfoPanel(topic: PillTopic, state: RadioUiState, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        // Without this Compose caps the dialog at a platform width and every size we set is ignored.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) { PillInfoCard(topic, state, onDismiss) }
}

/**
 * The panel body without its dialog window. Split out so the JVM screenshot harness can render it:
 * Roborazzi captures the composition root, and a [Dialog]'s content lives in a separate window that
 * the capture never sees.
 */
/** Screen-relative dialog bounds — see the note in SettingsScreen.dialogSize. */
@Composable
private fun dialogSize(maxWidth: androidx.compose.ui.unit.Dp): Modifier {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val w = (cfg.screenWidthDp * 0.92f).dp
    return Modifier
        .widthIn(max = if (w < maxWidth) w else maxWidth)
        .heightIn(max = (cfg.screenHeightDp * 0.94f).dp)
}

@Composable
fun PillInfoCard(topic: PillTopic, state: RadioUiState, onDismiss: () -> Unit) {
        Column(
            // Sized from the actual screen, not fixed dp: the head unit's display is far fewer dp
            // than the emulator's at the same pixel count, so a fixed minimum either overflowed it
            // or (with Compose's platform width cap, now off) was ignored entirely.
            dialogSize(maxWidth = 900.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(appColors.bg)
                .border(1.dp, appColors.line, RoundedCornerShape(18.dp))
                .padding(22.dp),
        ) {
            val c = content(topic, state)
            Text(
                c.title,
                color = appColors.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
            )
            c.explanation?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = appColors.muted, fontSize = 14.sp, lineHeight = 20.sp)
            }
            Spacer(Modifier.height(14.dp))
            // Scroll rather than clip: some rows (a stream URL, a list of ensembles) can be long, and
            // the close button has to stay reachable — the same lesson as the EWS overlay.
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            ) {
                c.rows.forEach { (label, value) -> InfoRow(label, value) }
                if (topic == PillTopic.INTERNET) PublicIpRow()
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .background(appColors.softBg)
                        .border(1.dp, appColors.softBorder, RoundedCornerShape(10.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 22.dp, vertical = 11.dp),
                ) {
                    Text(stringResource(R.string.action_close), color = appColors.text, fontSize = 16.sp)
                }
            }
        }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Fixed, not min-width: a long label (German "Bekannte ASA-Ensembles") would otherwise push
        // its own value out of the column and break the alignment of the whole table.
        Text(label, color = appColors.muted, fontSize = 14.sp, modifier = Modifier.width(LABEL_COL))
        Spacer(Modifier.width(12.dp))
        // weight, not natural width: a long value wraps onto a second line inside the panel instead
        // of being clipped at the edge.
        Text(value, color = appColors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

/**
 * The public IP is the one thing here that is not local knowledge — it takes a request to an outside
 * service to learn it. So it is NOT fetched when the panel opens: the row offers it, and only an
 * explicit tap reaches out. Useful mainly to explain geo-blocked streams (BBC and friends).
 */
@Composable
private fun PublicIpRow() {
    var value by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val label = stringResource(R.string.pill_public_ip)
    if (value != null) {
        InfoRow(label, value!!)
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Fixed, not min-width: a long label (German "Bekannte ASA-Ensembles") would otherwise push
        // its own value out of the column and break the alignment of the whole table.
        Text(label, color = appColors.muted, fontSize = 14.sp, modifier = Modifier.width(LABEL_COL))
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(if (loading) R.string.pill_loading else R.string.pill_fetch_public_ip),
            color = appColors.accent, fontSize = 14.sp,
            modifier = Modifier.clickable(enabled = !loading) { loading = true },
        )
    }
    if (loading) {
        LaunchedEffect(Unit) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    java.net.URL("https://api.ipify.org").openConnection().apply {
                        connectTimeout = 5_000; readTimeout = 5_000
                    }.getInputStream().bufferedReader().use { it.readText() }.trim()
                }.getOrNull()?.takeIf { it.isNotBlank() }
            } ?: "—"
            loading = false
        }
    }
}

/** Label column width — wide enough for the longest German label so values stay aligned. */
private val LABEL_COL = 190.dp

private data class PanelContent(
    val title: String,
    val explanation: String?,
    val rows: List<Pair<String, String>>,
)

@Composable
private fun content(topic: PillTopic, s: RadioUiState): PanelContent {
    val ctx = LocalContext.current
    val dash = "—"
    /** "10bc · Deutschlandradio" for an ensemble id, using whatever the station list knows. */
    fun ensembleLabel(eid: Int?): String {
        if (eid == null) return dash
        val hex = "%04x".format(eid)
        val name = s.stations.firstOrNull { it.band == Band.DAB && it.id.startsWith("$hex.") }?.ensemble
        return if (name.isNullOrBlank()) hex else "$hex · $name"
    }
    return when (topic) {
        PillTopic.ASA -> PanelContent(
            title = stringResource(R.string.pill_asa_title),
            explanation = stringResource(R.string.pill_asa_explain),
            rows = listOfNotNull(
                stringResource(R.string.pill_status) to stringResource(
                    when (s.asaStatus) {
                        AsaStatus.ACTIVE -> R.string.pill_asa_active
                        AsaStatus.DEGRADED -> R.string.pill_asa_degraded
                        AsaStatus.INACTIVE -> R.string.pill_asa_inactive
                        AsaStatus.OFF -> R.string.pill_asa_off
                    }
                ),
                stringResource(R.string.pill_asa_ensemble) to ensembleLabel(s.currentTunerEnsembleId),
                stringResource(R.string.pill_asa_known) to
                    (s.ewsEnsembleIds.takeIf { it.isNotEmpty() }
                        ?.joinToString(", ") { ensembleLabel(it) } ?: dash),
                stringResource(R.string.pill_asa_location) to
                    (listOfNotNull(
                        s.asaGpsCode?.takeIf { s.settings.asaFollowGps }
                            ?.let { "GPS " + com.px6.radio.ews.EwsMatcher.grouped(it) },
                    ).plus(s.settings.asaLocationCodes.map { com.px6.radio.ews.EwsMatcher.grouped(it) })
                        .plus(
                            if (s.settings.asaTestAlerts)
                                listOf("TEST " + com.px6.radio.ews.EwsMatcher.grouped(com.px6.radio.ews.EwsMatcher.TEST_LOCATION_CODE))
                            else emptyList()
                        )
                        .takeIf { it.isNotEmpty() }?.joinToString(", ")
                        ?: stringResource(R.string.pill_asa_no_location)),
                stringResource(R.string.pill_asa_tests) to stringResource(
                    if (s.settings.asaTestAlerts) R.string.settings_on else R.string.settings_off
                ),
            ) + s.asaHistory.mapIndexed { i, (time, what) ->
                // Recent alerts, newest first. Only the first row carries the heading, so a run of
                // them reads as one block instead of repeating the same label five times.
                (if (i == 0) stringResource(R.string.pill_asa_recent) else "") to "$time  $what"
            },
        )

        PillTopic.DAB -> {
            val stick = remember { com.px6.radio.dab.DabUsb.describe(ctx) }
            PanelContent(
                title = stringResource(R.string.pill_dab_title),
                explanation = stringResource(R.string.pill_dab_explain),
                rows = listOfNotNull(
                    stringResource(R.string.pill_dab_stick) to stringResource(
                        if (s.dabPresent) R.string.pill_dab_detected else R.string.pill_dab_missing
                    ),
                    stringResource(R.string.pill_dab_usb_id) to
                        (stick?.usbId ?: com.px6.radio.dab.DabUsb.expectedUsbId()),
                    stick?.product?.let { stringResource(R.string.pill_dab_product) to it },
                    stick?.manufacturer?.let { stringResource(R.string.pill_dab_vendor) to it },
                    stringResource(R.string.pill_dab_stations) to
                        s.stations.count { it.band == Band.DAB }.toString(),
                    stringResource(R.string.pill_asa_ensemble) to ensembleLabel(s.currentTunerEnsembleId),
                    stringResource(R.string.pill_dab_signal) to "${s.signalBars}/4",
                ),
            )
        }

        PillTopic.FM -> {
            val st = s.nowPlaying?.station?.takeIf { it.band == Band.FM }
            PanelContent(
                title = stringResource(R.string.pill_fm_title),
                explanation = stringResource(R.string.pill_fm_explain),
                rows = listOfNotNull(
                    stringResource(R.string.pill_station) to (st?.name ?: dash),
                    stringResource(R.string.pill_fm_frequency) to
                        (st?.frequencyKhz?.let { "%.1f MHz".format(it / 1000.0) } ?: dash),
                    stringResource(R.string.pill_fm_pi) to
                        (st?.piCode?.let { "%04X".format(it) } ?: dash),
                    stringResource(R.string.pill_fm_region) to s.settings.fmRegion,
                ),
            )
        }

        PillTopic.AM -> PanelContent(
            title = stringResource(R.string.pill_am_title),
            explanation = null,
            rows = listOf(
                stringResource(R.string.pill_station) to
                    (s.nowPlaying?.station?.takeIf { it.band == Band.AM }?.name ?: dash),
            ),
        )

        PillTopic.INTERNET -> {
            val st = s.nowPlaying?.station?.takeIf { it.band == Band.IP }
            val host = st?.streamUrl?.let { runCatching { java.net.URL(it).host }.getOrNull() }
            PanelContent(
                title = stringResource(R.string.pill_ip_title),
                explanation = stringResource(R.string.pill_ip_explain),
                rows = listOfNotNull(
                    stringResource(R.string.pill_station) to (st?.name ?: dash),
                    stringResource(R.string.pill_ip_host) to (host ?: dash),
                    stringResource(R.string.pill_ip_stations) to
                        s.stations.count { it.band == Band.IP }.toString(),
                ),
            )
        }
    }
}
