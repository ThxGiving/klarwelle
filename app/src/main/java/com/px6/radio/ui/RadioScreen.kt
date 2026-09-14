package com.px6.radio.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.px6.radio.model.Band
import com.px6.radio.model.FollowingState
import com.px6.radio.model.NowPlaying
import com.px6.radio.model.RadioUiState
import com.px6.radio.model.Screen
import com.px6.radio.model.Station
import androidx.compose.runtime.CompositionLocalProvider
import com.px6.radio.R
import com.px6.radio.logo.LocalLogoStore
import com.px6.radio.logo.LocalLogoVersion
import com.px6.radio.ui.theme.appColors
import com.px6.radio.ui.theme.skin
import com.px6.radio.ui.theme.touchMin
import com.px6.radio.ui.frontend.Frontends
import com.px6.radio.ui.frontend.RadioActions
import com.px6.radio.ui.frontend.RadioFrontend
import com.px6.radio.vm.RadioViewModel

/**
 * Hosts whichever frontend the user picked and routes to the settings screen.
 *
 * The frontends themselves never see the ViewModel — they get [RadioUiState] and [RadioActions],
 * so a new interface can be added without touching the backend.
 */
@Composable
fun RadioScreen(vm: RadioViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val skins by vm.skins.collectAsStateWithLifecycle()

    // Hardware "back": step out of a screen/modal instead of finishing the app to the home screen
    // (the head unit's default). Only when there is something to dismiss — otherwise back leaves.
    BackHandler(enabled = state.screen == Screen.SETTINGS) { vm.closeSettings() }
    BackHandler(enabled = state.screen != Screen.SETTINGS && state.dabOffer != null) { vm.declineDabOffer() }
    // On the main screen, back doesn't silently kill a playing radio — it asks first.
    BackHandler(enabled = state.screen != Screen.SETTINGS && state.dabOffer == null && !state.exitConfirm) {
        vm.requestExit()
    }
    BackHandler(enabled = state.exitConfirm) { vm.cancelExit() }

    // Everything is wrapped in one Box so the ASA/EWS alert can be drawn over WHATEVER is on screen.
    // It used to sit inside the main-screen branch, below an early `return` for the settings screen —
    // so an alert that arrived while the user was in Settings handed the audio over (audibly) but
    // never showed the warning text, stage or instruction. An emergency warning must not be
    // suppressed by which screen happens to be open.
    Box(Modifier.fillMaxSize()) {
    if (state.screen == Screen.SETTINGS) {
        SettingsScreen(
            settings = state.settings,
            dabPresent = state.dabPresent,
            dabScanning = state.dabScanning,
            scanProgress = state.scanProgress,
            fmAvailable = state.fmAvailable,
            fmSeeking = state.fmSeeking,
            headlightOn = state.headlightOn,
            onScanDab = vm::scanDab,
            onScanFm = vm::scanFm,
            skins = skins,
            frontends = Frontends.all(),
            onClearPreset = vm::clearPreset,
            presets = state.presets.mapNotNull { slot ->
                state.station(slot.stationId)?.let { slot.index to it.name }
            },
            logoCount = state.logoCount,
            logoDownloading = state.logoDownloading,
            logoStatus = state.logoStatus,
            onDownloadLogos = vm::downloadLogos,
            onClearLogos = vm::clearLogos,
            onRefreshRadioDns = vm::refreshRadioDns,
            onResetBackends = vm::resetBackends,
            onDeleteDiagnostics = vm::deleteDiagnostics,
            internetStations = state.stations.filter { it.band == Band.IP },
            internetResults = state.internetResults,
            internetSearching = state.internetSearching,
            onSearchInternet = vm::searchInternet,
            onAddInternet = vm::addInternetStation,
            onRemoveInternet = vm::removeInternetStation,
            onAddManualStream = vm::addManualStream,
            placeResults = state.placeResults,
            placeSearching = state.placeSearching,
            onSearchPlace = vm::searchPlaces,
            onBack = vm::closeSettings,
            onChange = vm::updateSettings,
        )
    } else {

    // Logos live outside the state: they are bitmaps, and copying them through every state
    // update would be wasteful. The version counter triggers recomposition when they change.
    val logoVersion by vm.logoStore.version.collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalLogoStore provides vm.logoStore,
        LocalLogoVersion provides logoVersion,
    ) {
        Box(Modifier.fillMaxSize()) {
            Frontends.byId(state.settings.frontendId).Content(state, vm)
            state.dabOffer?.let { offer ->
                DabOfferModal(
                    stationName = offer.name,
                    onYes = vm::acceptDabOffer,
                    onNo = vm::declineDabOffer,
                    onIgnore = vm::ignoreDabOffer,
                )
            }
            if (state.exitConfirm) {
                ExitModal(onYes = vm::confirmExit, onNo = vm::cancelExit)
            }
        }
    }
    }
        // ASA/EWS alert above EVERYTHING — both frontends AND the settings screen.
        state.ewsAlert?.let { com.px6.radio.ews.EwsAlertOverlay(it, onDismiss = vm::dismissEwsAlert) }
    }
}

/** Confirm before the hardware-back closes a running radio (would otherwise finish + leak audio). */
@Composable
private fun ExitModal(onYes: () -> Unit, onNo: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 460.dp).clip(RoundedCornerShape(skin.cornerLarge))
                .background(appColors.panel)
                .border(skin.border, appColors.line, RoundedCornerShape(skin.cornerLarge))
                .padding(horizontal = 28.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.radio_exit_title), color = appColors.text, fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.radio_exit_message),
                color = appColors.muted, fontSize = 15.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModalBtn(stringResource(R.string.radio_exit_confirm), onYes, primary = true)
                ModalBtn(stringResource(R.string.radio_cancel), onNo, primary = false)
            }
        }
    }
}

/** Shared modal: the FM station you're on is also on DAB+ — offer to switch. Same in both frontends. */
@Composable
private fun DabOfferModal(
    stationName: String,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onIgnore: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
            ) { /* swallow taps on the scrim */ },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 480.dp).clip(RoundedCornerShape(skin.cornerLarge))
                .background(appColors.panel)
                .border(skin.border, appColors.line, RoundedCornerShape(skin.cornerLarge))
                .padding(horizontal = 28.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.radio_dab_offer_title),
                color = appColors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "$stationName · DAB+", color = appColors.accent,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModalBtn(stringResource(R.string.radio_yes), onYes, primary = true)
                ModalBtn(stringResource(R.string.radio_no), onNo, primary = false)
                ModalBtn(stringResource(R.string.radio_ignore), onIgnore, primary = false)
            }
        }
    }
}

@Composable
private fun ModalBtn(label: String, onClick: () -> Unit, primary: Boolean) {
    Box(
        Modifier.heightIn(min = touchMin).widthIn(min = 92.dp)
            .clip(RoundedCornerShape(skin.cornerSmall))
            .background(if (primary) appColors.accent else appColors.softBg)
            .then(
                if (primary) Modifier
                else Modifier.border(skin.border, appColors.line, RoundedCornerShape(skin.cornerSmall)),
            )
            .clickable { onClick() }.padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = if (primary) appColors.onAccent else appColors.text,
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Split interface: station list on the left, now-playing on the right, presets along the bottom.
 */
object SplitFrontend : RadioFrontend {

    override val id = "split"
    override val nameRes = R.string.frontend_split_name
    override val descriptionRes = R.string.frontend_split_desc

    @Composable
    override fun Content(state: RadioUiState, actions: RadioActions) {
        Column(Modifier.fillMaxSize().background(appColors.bg)) {
            TopBar(
                clock = state.clock,
                outsideTemp = state.outsideTemp,
                following = state.following,
                fmAvailable = state.fmAvailable,
                nowPlaying = state.nowPlaying,
                demoMode = state.demoMode,
                dabScanning = state.dabScanning,
                scanProgress = state.scanProgress,
                showSignal = state.settings.showSignalStrength && state.selectedBand == Band.DAB,
                signalBars = state.signalBars,
                asaStatus = state.asaStatus,
                onOpenSettings = actions::openSettings,
            )
            if (state.backendErrors.isNotEmpty()) {
                ErrorBanner(
                    state.backendErrors,
                    onReset = actions::resetBackends,
                    onDismiss = actions::dismissErrors,
                )
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    Modifier.width(392.dp).fillMaxHeight().background(appColors.panelAlt),
                ) {
                    SourceTabs(
                        selected = state.selectedBand,
                        bands = state.availableBands,
                        onSelect = actions::selectBand,
                    )
                    if (state.visibleStations.isEmpty()) {
                        EmptyHint(noStationsAtAll = state.stations.isEmpty())
                    } else {
                        val listState = rememberLazyListState()
                        // Open already scrolled to the playing station (e.g. the current stream).
                        val playingId = state.nowPlaying?.station?.id
                        LaunchedEffect(playingId, state.visibleStations) {
                            val idx = state.visibleStations.indexOfFirst { it.id == playingId }
                            if (idx >= 0) listState.scrollToItem(idx)
                        }
                        LazyColumn(
                            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
                            state = listState,
                        ) {
                            items(state.visibleStations, key = { it.id }) { st ->
                                StationRow(
                                    station = st,
                                    selected = st.id == state.nowPlaying?.station?.id,
                                    tuning = st.id == state.tuningStationId,
                                    onClick = { actions.selectStation(st) },
                                )
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxHeight().weight(1f)) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        state.nowPlaying?.let { NowPlayingPane(it, tuning = it.station.id == state.tuningStationId) }
                    }
                    TransportBar(
                        state = state,
                        onTunePreset = actions::tunePreset,
                        onAssignPreset = actions::assignPreset,
                    )
                }
            }
        }
    }
}

/* ---------------------------------------------------------------- Top bar */

@Composable
private fun TopBar(
    clock: String,
    outsideTemp: String?,
    following: FollowingState,
    fmAvailable: Boolean,
    nowPlaying: NowPlaying?,
    demoMode: Boolean,
    dabScanning: Boolean,
    scanProgress: Int,
    showSignal: Boolean,
    signalBars: Int,
    asaStatus: com.px6.radio.model.AsaStatus,
    onOpenSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = touchMin)
            .background(appColors.panel).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(clock, color = appColors.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        // Outside temperature from the vehicle CAN (only when the car profile sends it).
        outsideTemp?.let {
            Spacer(Modifier.width(10.dp))
            Text(
                if (it.endsWith("°C") || it.endsWith("°")) it else "$it °C",
                color = appColors.muted, fontSize = 16.sp, maxLines = 1,
            )
        }
        Spacer(Modifier.width(14.dp))
        val bandLabel = nowPlaying?.station?.let {
            val prefix = when (it.band) {
                Band.DAB -> "DAB+ · "
                Band.IP -> "Internet · "
                else -> "FM · "
            }
            prefix + it.name
        } ?: "—"
        Pill {
            Box(
                Modifier.size(9.dp).clip(CircleShape).background(
                    if (following == FollowingState.FM_FALLBACK) appColors.fm else appColors.dab
                )
            )
            Spacer(Modifier.width(8.dp))
            Text(bandLabel, color = appColors.muted, fontSize = 13.sp, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        StatusChip(demoMode = demoMode, scanning = dabScanning, scanProgress = scanProgress)
        Spacer(Modifier.weight(1f))
        if (fmAvailable) {
            // Reflect the *current* station's data: following can only act when the DAB service
            // actually carries a FIG 0/6+0/21 FM link. Show the linked frequency when it does,
            // dim "kein FM-Link" when it doesn't, and the active fallback when DAB went weak.
            val dabStation = nowPlaying?.station?.takeIf { it.band == Band.DAB }
            val linkFm = dabStation?.linkedFmFrequencyKhz
            when {
                following == FollowingState.IP_FALLBACK -> Pill {
                    Text(
                        stringResource(R.string.radio_dab_weak_internet),
                        color = appColors.fm, fontSize = 13.sp, maxLines = 1,
                    )
                }
                following == FollowingState.FM_FALLBACK -> Pill {
                    Text(
                        stringResource(R.string.radio_dab_weak_fm) + (linkFm?.let { " %.1f".format(it / 1000.0) } ?: ""),
                        color = appColors.fm, fontSize = 13.sp, maxLines = 1,
                    )
                }
                linkFm != null -> Pill(accent = true) {
                    Text(
                        stringResource(R.string.radio_following_ready, linkFm / 1000.0),
                        color = appColors.okText, fontSize = 13.sp, maxLines = 1,
                    )
                }
                dabStation != null -> Pill {
                    Text(stringResource(R.string.radio_no_fm_link), color = appColors.muted, fontSize = 13.sp, maxLines = 1)
                }
                else -> Unit   // on a manual FM/AM station there is nothing to follow
            }
            if (following == FollowingState.FM_FALLBACK || dabStation != null) Spacer(Modifier.width(10.dp))
        }
        // A signal meter only on request (Settings) and only for DAB, where it is continuous.
        if (showSignal) {
            Pill { SignalBars(signalBars) }
            Spacer(Modifier.width(10.dp))
        }
        // ASA / DAB emergency-warning status. Mandatory to tell the user when EWS is inoperable
        // (ETSI TS 104 089 §5.3/§7.2.1): ACTIVE = monitoring an EWS ensemble; INACTIVE = FM/AM/
        // internet or a DAB ensemble without EWS. Hidden entirely when the feature is switched off.
        if (asaStatus != com.px6.radio.model.AsaStatus.OFF) {
            // Colour says it all: green = heartbeat alive, amber = heartbeat briefly lapsed (reception
            // dip), red = not receiving EWS. No extra wording.
            val c = when (asaStatus) {
                com.px6.radio.model.AsaStatus.ACTIVE -> appColors.okText
                com.px6.radio.model.AsaStatus.DEGRADED -> appColors.fm
                else -> appColors.dangerText
            }
            Pill(accent = asaStatus == com.px6.radio.model.AsaStatus.ACTIVE) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.status_asa), color = c, fontSize = 13.sp, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
        }
        Box(
            Modifier.size(touchMin).clip(RoundedCornerShape(skin.cornerLarge)).background(appColors.softBg)
                .border(skin.border, appColors.softBorder, RoundedCornerShape(skin.cornerLarge))
                .clickable { onOpenSettings() },
            contentAlignment = Alignment.Center,
        ) { Text("⚙", color = appColors.muted, fontSize = 24.sp) }
    }
}

@Composable
private fun ErrorBanner(errors: List<String>, onReset: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(appColors.dangerSoft)
            .border(skin.border, appColors.dangerBorder).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.radio_backend_problems), color = appColors.dangerText,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            BannerBtn(stringResource(R.string.radio_reset), onReset)
            Spacer(Modifier.width(10.dp))
            BannerBtn("✕", onDismiss)
        }
        Spacer(Modifier.height(4.dp))
        errors.forEach { Text("• $it", color = appColors.dangerText, fontSize = 12.sp) }
    }
}

@Composable
private fun BannerBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = touchMin).widthIn(min = touchMin)
            .clip(RoundedCornerShape(skin.cornerSmall)).background(appColors.panel)
            .border(skin.border, appColors.dangerBorder, RoundedCornerShape(skin.cornerSmall))
            .clickable { onClick() }.padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = appColors.dangerText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun StatusChip(demoMode: Boolean, scanning: Boolean, scanProgress: Int) {
    val label: String
    val fg: Color
    val bg: Color
    val border: Color
    when {
        scanning -> {
            label = stringResource(R.string.radio_scanning, scanProgress); fg = appColors.accent
            bg = appColors.accentSoft; border = appColors.accentSoftBorder
        }
        demoMode -> {
            label = "DEMO"; fg = appColors.fm
            bg = appColors.softBg; border = appColors.softBorder
        }
        else -> {
            label = "LIVE"; fg = appColors.dab
            bg = appColors.okSoft; border = appColors.okBorder
        }
    }
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(bg)
            .border(skin.border, border, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) { Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Pill(accent: Boolean = false, content: @Composable () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(if (accent) appColors.okSoft else appColors.softBg)
            .border(skin.border, if (accent) appColors.okBorder else appColors.softBorder, RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/* ------------------------------------------------------------- Left panel */

@Composable
private fun SourceTabs(selected: Band, bands: List<Band>, onSelect: (Band) -> Unit) {
    // Only the bands that exist on this box (see RadioUiState.availableBands).
    val tabs = bands.map { b ->
        when (b) {
            Band.DAB -> Triple(Band.DAB, "DAB+", "Ensemble")
            Band.FM -> Triple(Band.FM, "FM", "87.5–108")
            Band.AM -> Triple(Band.AM, "AM", "MW")
            Band.IP -> Triple(Band.IP, "Internet", "Stream")
        }
    }
    Row(Modifier.fillMaxWidth().padding(10.dp, 10.dp, 10.dp, 4.dp)) {
        tabs.forEach { (band, label, sub) ->
            val on = band == selected
            Column(
                Modifier.weight(1f).padding(horizontal = 3.dp)
                    .heightIn(min = touchMin)
                    .clip(RoundedCornerShape(skin.cornerLarge))
                    .background(if (on) appColors.accent else appColors.softBg)
                    .border(skin.border, if (on) appColors.accentBorder else appColors.line, RoundedCornerShape(skin.cornerLarge))
                    .clickable { onSelect(band) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(label, color = if (on) appColors.onAccent else appColors.muted,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(sub, color = if (on) appColors.onAccent else appColors.muted2,
                    fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun EmptyHint(noStationsAtAll: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (noStationsAtAll) {
            Text(stringResource(R.string.radio_no_stations), color = appColors.muted, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.radio_no_stations_hint),
                color = appColors.muted2, fontSize = 14.sp)
        } else {
            Text(stringResource(R.string.radio_no_stations_band), color = appColors.muted, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.radio_no_stations_band_hint),
                color = appColors.muted2, fontSize = 14.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StationRow(
    station: Station,
    selected: Boolean,
    tuning: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .heightIn(min = touchMin)
            .clip(RoundedCornerShape(skin.cornerLarge))
            .then(
                if (selected) Modifier.background(appColors.rowSelected)
                    .border(skin.border, appColors.rowSelectedBorder, RoundedCornerShape(skin.cornerLarge))
                else Modifier
            )
            .clickable { onClick() }
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (skin.showLogos) {
            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                StationLogo(station, Modifier.fillMaxSize())
                if (tuning) TuningOverlay(Modifier.fillMaxSize(), ring = 22.dp)
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                station.name, color = appColors.text, fontSize = skin.font(skin.bodySize),
                fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
            )
            Text(
                station.subtitle +
                    if (station.secondaryLabels.isNotEmpty()) "  ＋${station.secondaryLabels.size}" else "",
                color = appColors.muted, fontSize = skin.font(skin.labelSize),
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        if (skin.showBandBadges) Badge(station.band)
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun Badge(band: Band) {
    val bg: Color
    val fg: Color
    val label: String
    when (band) {
        Band.DAB -> { bg = appColors.dab; fg = appColors.onDab; label = "DAB+" }
        Band.FM -> { bg = appColors.fm; fg = appColors.onFm; label = "FM" }
        Band.AM -> { bg = appColors.muted; fg = appColors.panel; label = "AM" }
        Band.IP -> { bg = appColors.dab; fg = appColors.onDab; label = "NET" }
    }
    Box(
        Modifier.clip(RoundedCornerShape(7.dp)).background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/* ------------------------------------------------------------ Now playing */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPlayingPane(np: NowPlaying, tuning: Boolean = false) {
    Column(Modifier.fillMaxSize().padding(24.dp, 20.dp, 24.dp, 0.dp)) {
        Text(
            if (tuning) stringResource(R.string.radio_tuning_line, np.bandLine) else np.bandLine,
            color = appColors.muted, fontSize = 14.sp,
            maxLines = 1, softWrap = false,
            modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(np, tuning)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                // Never wrap the station name — one line, ellipsised. Typography from the skin, so
                // the instrument look (light, wide-tracked, capitals) reaches this frontend too.
                Text(
                    np.station.name.let { if (skin.titleUppercase) it.uppercase() else it },
                    color = appColors.text,
                    fontSize = skin.font(minOf(skin.titleSize.value, 30f).sp),
                    fontWeight = skin.titleWeight,
                    letterSpacing = skin.titleTracking.em,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                )
                Spacer(Modifier.height(4.dp))
                val line = if (np.dlArtist != null && np.dlTitle != null) {
                    "${np.dlArtist} — ${np.dlTitle}"
                } else np.dlsText
                // Caption under the name only when we actually have track data — otherwise nothing
                // at all (no placeholder pretending there is info).
                if (!line.isNullOrBlank()) {
                    Text(
                        "Now Playing", color = appColors.muted, fontSize = 14.sp, maxLines = 1,
                        softWrap = false, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (!line.isNullOrBlank()) {
                    Text(
                        line, color = appColors.bodyText, fontSize = 18.sp,
                        maxLines = 1, softWrap = false,
                        modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    np.dlTitle?.let { Chip("♪ $it"); Spacer(Modifier.width(8.dp)) }
                    np.dlArtist?.let { Chip(it) }
                }
            }
        }
    }
}

@Composable
private fun Cover(np: NowPlaying, tuning: Boolean = false) {
    val shape = RoundedCornerShape(18.dp)
    val bmp = remember(np.slideshowImage) {
        np.slideshowImage?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    if (bmp == null) {
        // No slideshow: a plain gradient placeholder tile — with the tuning loader over it if switching.
        Box(
            Modifier.size(140.dp).clip(shape).background(
                Brush.linearGradient(listOf(appColors.coverA, appColors.coverB, appColors.coverC))
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (tuning) TuningOverlay(Modifier.fillMaxSize(), ring = 34.dp)
        }
        return
    }
    // Take the slideshow's own aspect ratio for the frame so Fit fills it edge-to-edge (randlos),
    // bounded so a wide/tall image still fits the pane.
    val aspect = (bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)).coerceIn(0.5f, 2.2f)
    Box(
        Modifier.heightIn(max = 140.dp).widthIn(max = 210.dp).aspectRatio(aspect).clip(shape),
    ) {
        Image(
            bitmap = bmp, contentDescription = "Slideshow",
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
        )
        np.slideshowCaption?.let {
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(appColors.scrim).padding(10.dp)
            ) {
                Text(it, color = Color(0xFFEFF5F9), fontSize = 11.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (tuning) TuningOverlay(Modifier.fillMaxSize(), ring = 34.dp)
    }
}

@Composable
private fun Chip(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(appColors.accentSoft)
            .border(skin.border, appColors.accentSoftBorder, RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = appColors.accentText, fontSize = 13.sp,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/* -------------------------------------------------------------- Transport */

@Composable
private fun TransportBar(
    state: RadioUiState,
    onTunePreset: (Int) -> Unit,
    onAssignPreset: (Int) -> Unit,
) {
    // No skip buttons — stations are chosen from the list, the presets or the wheel; a prev/next
    // pair next to the presets was just redundant. Presets get the whole bar. All 18 slots live in a
    // finger-scrollable row (same set the "Kacheln" frontend pages through), each a logo tile.
    Row(
        Modifier.fillMaxWidth().heightIn(min = touchMin + 30.dp)
            .background(appColors.transportBg)
            // Bottom edge = Android's system-gesture zone; without this a horizontal swipe here is
            // eaten as an app-switch/back instead of scrolling presets. Claim it for our own scroll.
            .systemGestureExclusion()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.presets.forEach { slot ->
            val st = state.station(slot.stationId)
            PresetTile(
                index = slot.index,
                station = st,
                name = st?.let { state.displayName(it) },
                active = st != null && st.id == state.nowPlaying?.station?.id,
                tuning = st != null && st.id == state.tuningStationId,
                onTap = { onTunePreset(slot.index) },
                onLongPress = { onAssignPreset(slot.index) },
            )
        }
    }
}

/** One preset in the split view's bottom bar: station logo + name for an assigned slot, or just the
 *  slot number for an empty one (to long-press-assign). Mirrors the tile frontend's 18 presets. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetTile(
    index: Int,
    station: Station?,
    name: String?,
    active: Boolean,
    tuning: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        Modifier.width(80.dp)
            .clip(RoundedCornerShape(skin.cornerLarge))
            .background(if (active) appColors.accentSoft else appColors.softBg)
            .border(
                if (active) 2.dp else 1.dp,
                if (active) appColors.accent else appColors.softBorder,
                RoundedCornerShape(skin.cornerLarge),
            )
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            if (station != null) {
                StationLogo(station, Modifier.fillMaxSize().clip(RoundedCornerShape(skin.cornerSmall)))
            } else {
                // Empty slot: only here do we show the preset number.
                Text("$index", color = appColors.muted2, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            if (tuning) TuningOverlay(Modifier.fillMaxSize(), ring = 20.dp)
        }
        // Station name stays for assigned presets; empty for an open slot (keeps tile heights equal).
        Text(
            if (station != null) (name ?: "") else "",
            color = if (active) appColors.accent else appColors.muted,
            fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
    }
}
