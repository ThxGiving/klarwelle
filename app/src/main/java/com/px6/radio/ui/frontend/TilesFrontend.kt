package com.px6.radio.ui.frontend

import androidx.compose.foundation.ExperimentalFoundationApi
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.isActive
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.px6.radio.R
import com.px6.radio.logo.LocalLogoStore
import com.px6.radio.logo.LocalLogoVersion
import com.px6.radio.model.Band
import com.px6.radio.model.FollowingState
import com.px6.radio.model.RadioUiState
import com.px6.radio.model.SortMode
import com.px6.radio.model.Station
import com.px6.radio.model.TuningProfile
import com.px6.radio.model.ViewMode
import com.px6.radio.ui.theme.TileLabel
import com.px6.radio.ui.theme.appColors
import com.px6.radio.ui.theme.skin
import com.px6.radio.ui.theme.touchMin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Station-button interface: one big station name with stepping arrows, a row of six station
 * buttons and a function bar.
 *
 * The interaction model follows a classic automotive head unit (see `docs/ui-frontends.md`):
 * 18 station buttons in three groups of six, swipe or tap to change group, long-press a button to
 * store the current station, a station list behind "Sender" and manual tuning behind "Manuell".
 */
object TilesFrontend : RadioFrontend {

    override val id = "tiles"
    override val nameRes = R.string.frontend_tiles_name
    override val descriptionRes = R.string.frontend_tiles_desc

    private const val GROUPS = 3
    private const val PER_GROUP = 6

    /** Tick marks on the frequency scale. */
    private const val TICKS = 41

    /** The station list closes itself after this long without input, as the original does. */
    private const val LIST_IDLE_MILLIS = 20_000L

    /** The frequency band hides itself again after this long without input. */
    private const val MANUAL_IDLE_MILLIS = 15_000L

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun Content(state: RadioUiState, actions: RadioActions) {
        var page by remember { mutableStateOf(Page.PRESETS) }
        // The hardware "list" key (RadioViewModel.openStationList) bumps openListRequest → TOGGLE the
        // station list: open it, or close it again (back to presets) if it's already showing.
        //
        // Remember the counter we last ACTED on, seeded with its current value. The counter only ever
        // grows and is never consumed, so a bare `if (openListRequest > 0)` fired again every time
        // this frontend re-entered composition — after leaving Settings, after a language change
        // (which recreates the Activity while the ViewModel survives), after a frontend switch — and
        // the list sprang open on its own. Seeding means a re-entry starts level and only a genuine
        // new key press moves it.
        var actedOn by remember { mutableIntStateOf(state.openListRequest) }
        LaunchedEffect(state.openListRequest) {
            if (state.openListRequest != actedOn) {
                actedOn = state.openListRequest
                page = if (page == Page.LIST) Page.PRESETS else Page.LIST
            }
        }
        // A pager gives the swipe real physics: it carries on after the finger lifts and settles
        // onto a group instead of jumping. Counting drag pixels by hand never feels like this.
        val pager = rememberPagerState(pageCount = { GROUPS })
        val scope = rememberCoroutineScope()
        // Set by long-pressing a station in the list: the next station button tapped stores it.
        var pendingStore by remember { mutableStateOf<Station?>(null) }
        var bandPicker by remember { mutableStateOf(false) }
        var viewPicker by remember { mutableStateOf(false) }

        Column(Modifier.fillMaxSize().background(appColors.bg)) {
            StatusBar(state)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (page) {
                    Page.PRESETS -> PresetPage(
                        state = state,
                        actions = actions,
                        pager = pager,
                        onGroup = { scope.launch { pager.animateScrollToPage(it.coerceIn(0, GROUPS - 1)) } },
                        pendingStore = pendingStore,
                        onStored = { pendingStore = null },
                    )
                    Page.LIST -> StationListPage(
                        state = state,
                        actions = actions,
                        pendingStore = pendingStore,
                        onPending = { pendingStore = it },
                        onClose = { page = Page.PRESETS },
                    )
                    Page.MANUAL -> ManualPage(state, actions) { page = Page.PRESETS }
                }
            }
            FunctionBar(
                state = state,
                page = page,
                onPage = { page = it },
                onBand = { bandPicker = true },
                onView = { viewPicker = true },
                onSettings = actions::openSettings,
            )
        }

        if (viewPicker) {
            val modes = ViewMode.entries
            val ctx = androidx.compose.ui.platform.LocalContext.current
            OptionWindow(
                title = stringResource(R.string.tiles_view),
                options = modes.map { ctx.getString(viewModeNameRes(it)) },
                selectedIndex = modes.indexOf(state.viewMode).coerceAtLeast(0),
                onPick = { i -> actions.setViewMode(modes[i]); viewPicker = false },
                onDismiss = { viewPicker = false },
            )
        }

        if (bandPicker) {
            val bands = availableBands(state)
            OptionWindow(
                title = stringResource(R.string.tiles_band_range),
                options = bands.map { bandName(it) },
                selectedIndex = bands.indexOf(state.selectedBand).coerceAtLeast(0),
                onPick = { i -> actions.selectBand(bands[i]); bandPicker = false },
                onDismiss = { bandPicker = false },
            )
        }
    }

    /** Bands offered as tabs — only those that actually exist on this box (see
     *  [RadioUiState.availableBands]). Internet is always there; DAB/FM/AM only with the hardware. */
    private fun availableBands(state: RadioUiState): List<Band> = state.availableBands

    @androidx.annotation.StringRes
    private fun viewModeNameRes(mode: ViewMode) = when (mode) {
        ViewMode.PRESETS -> R.string.view_mode_presets
        ViewMode.STATION_INFO -> R.string.view_mode_station_info
        ViewMode.RADIO_TEXT -> R.string.view_mode_radiotext
        ViewMode.SLIDESHOW -> R.string.view_mode_slideshow
    }

    private fun bandName(band: Band) = when (band) {
        Band.DAB -> "DAB+"
        Band.FM -> "FM"
        Band.AM -> "AM"
        Band.IP -> "Internet"
    }

    /**
     * Option window in the style of the original: a title bar with a close button and one row per
     * choice, the active one marked. Picking closes it immediately.
     */
    /** Width for a short option list: comfortable, but never the full width of the screen. */
    @Composable
    private fun optionWindowWidth(): Modifier {
        val cfg = androidx.compose.ui.platform.LocalConfiguration.current
        val w = (cfg.screenWidthDp * 0.6f).dp
        return Modifier.widthIn(min = 340.dp, max = if (w < 480.dp) w else 480.dp)
    }

    @Composable
    private fun OptionWindow(
        title: String,
        options: List<String>,
        selectedIndex: Int,
        onPick: (Int) -> Unit,
        onDismiss: () -> Unit,
    ) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                // Capped. Turning off Compose's platform width cap (needed so the wide info panels
                // are not squeezed) also freed THIS one to stretch across the whole display — absurd
                // for four short entries like DAB+/FM/AM/Internet. Screen-relative so it still fits
                // a narrow display.
                optionWindowWidth().clip(RoundedCornerShape(skin.cornerLarge))
                    .background(appColors.panel)
                    .border(skin.border, appColors.line, RoundedCornerShape(skin.cornerLarge)),
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = touchMin)
                        .padding(horizontal = skin.pad(18)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title, color = appColors.text, fontSize = skin.font(18.sp),
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier.size(touchMin).clickable { onDismiss() },
                        contentAlignment = Alignment.Center,
                    ) { Text("✕", color = appColors.muted, fontSize = skin.font(22.sp)) }
                }
                Hairline()
                options.forEachIndexed { i, label ->
                    val on = i == selectedIndex
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = touchMin)
                            .clickable { onPick(i) }.padding(horizontal = skin.pad(18)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label, modifier = Modifier.weight(1f),
                            color = if (on) appColors.accent else appColors.text,
                            fontSize = skin.font(17.sp),
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (on) Text("✓", color = appColors.accent, fontSize = skin.font(20.sp))
                    }
                    if (i < options.lastIndex) Hairline()
                }
            }
        }
    }

    private enum class Page { PRESETS, LIST, MANUAL }

    /* ------------------------------------------------------------ status bar */

    @Composable
    private fun StatusBar(state: RadioUiState) {
        // Which pill's detail panel is open. The pills have to stay terse next to the clock, so the
        // spelled-out name and the technical detail live one tap away instead of in the label.
        var pillInfo by remember { mutableStateOf<com.px6.radio.ui.PillTopic?>(null) }
        pillInfo?.let {
            com.px6.radio.ui.PillInfoPanel(it, state, onDismiss = { pillInfo = null })
        }
        Row(
            Modifier.fillMaxWidth().background(appColors.panel)
                // A little taller than before so the line reads at a glance while driving.
                .padding(horizontal = skin.pad(16), vertical = skin.pad(9)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: what is playing and how. Centre: the clock. Right, outermost: the outside
            // temperature — the same reading order as the original.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                // Primary source pill — GREEN when audio is really playing, orange when muted / no
                // reception, neutral otherwise. During a following fallback it stays neutral: the green
                // "aktiv" belongs to the fallback pill that is actually carrying the sound.
                val onFallback = state.following == FollowingState.FM_FALLBACK ||
                    state.following == FollowingState.IP_FALLBACK
                // The internet band gets the same treatment DAB and FM already had: a reception
                // signal, not just the play/pause intent. ExoPlayer says whether audio is really
                // coming out — without that, a stream that had dropped and was retrying stayed
                // green while nothing could be heard.
                val ipSilent = state.selectedBand == Band.IP && state.isPlaying && !state.ipStreamLive
                val warn = state.mutedNoReception || state.fmFallbackWeak || ipSilent ||
                    (state.selectedBand == Band.DAB && state.noDabCoverage)
                val working = state.isPlaying && !warn && !onFallback
                val note = when {
                    state.mutedNoReception -> " · " + stringResource(R.string.tiles_muted)
                    state.fmFallbackWeak -> " · " + stringResource(R.string.tiles_no_reception)
                    state.selectedBand == Band.DAB && state.noDabCoverage -> " · " + stringResource(R.string.tiles_no_reception)
                    // Nothing to add for a silent stream: orange already says it, and "kein Empfang"
                    // reads oddly for something that has no reception to begin with.
                    else -> ""
                }
                StatusPill(
                    bandOnly(state) + note, working = working, warn = warn,
                    onClick = {
                        pillInfo = when (state.selectedBand) {
                            Band.DAB -> com.px6.radio.ui.PillTopic.DAB
                            Band.FM -> com.px6.radio.ui.PillTopic.FM
                            Band.AM -> com.px6.radio.ui.PillTopic.AM
                            Band.IP -> com.px6.radio.ui.PillTopic.INTERNET
                        }
                    },
                )
                // On DAB, show the fallback tiers that could take over (FollowingPills decides which
                // of FM / Internet actually apply for the current station).
                if (state.selectedBand == Band.DAB) {
                    Spacer(Modifier.width(skin.pad(8)))
                    FollowingPills(state) { pillInfo = it }
                }
            }
            Text(
                state.clock, color = appColors.text,
                fontSize = skin.font(skin.labelSize.value.plus(5).sp),
                fontWeight = FontWeight.SemiBold,
            )
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // DAB reports strength continuously; show it only there, and only on request.
                    if (state.settings.showSignalStrength && state.selectedBand == Band.DAB) {
                        com.px6.radio.ui.SignalBars(state.signalBars)
                        Spacer(Modifier.width(skin.pad(12)))
                    }
                    // ASA/EWS status — green = receiving an EWS ensemble, red = not (§7.2.1/§5.3).
                    // Same Chip look as the source/following pills so the whole bar reads as one family.
                    if (state.asaStatus != com.px6.radio.model.AsaStatus.OFF) {
                        val c = when (state.asaStatus) {
                            com.px6.radio.model.AsaStatus.ACTIVE -> PILL_GREEN
                            com.px6.radio.model.AsaStatus.DEGRADED -> PILL_ORANGE
                            else -> PILL_RED
                        }
                        Chip(stringResource(R.string.status_asa), c) {
                            pillInfo = com.px6.radio.ui.PillTopic.ASA
                        }
                        Spacer(Modifier.width(skin.pad(12)))
                    }
                    // Same size and weight as the clock — in the original both read as one line of
                    // instrument data, not as a label with a footnote.
                    Text(
                        state.outsideTemp?.let {
                            if (it.endsWith("°C") || it.endsWith("°")) it else "$it °C"
                        } ?: "",
                        color = appColors.text,
                        fontSize = skin.font(skin.labelSize.value.plus(5).sp),
                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                }
            }
        }
        Hairline()
    }

    /**
     * The following tiers as pills. On DAB (primary) each *available* fallback for the current station
     * shows as a quiet outlined "↳" chip — FM (from the FIG 0/6+0/21 link) and Internet (a discovered
     * RadioDNS simulcast). Whichever tier is actually carrying the audio is shown filled with "▶". No
     * coloured status dot, so an available route is never mistaken for an active one.
     */
    @Composable
    private fun FollowingPills(state: RadioUiState, onInfo: (com.px6.radio.ui.PillTopic) -> Unit) {
        val dab = state.nowPlaying?.station?.takeIf { it.band == Band.DAB }
        val linkKhz = dab?.linkedFmFrequencyKhz
        val ipReady = dab != null &&
            state.settings.radioDnsEnabled && state.settings.ipFallbackEnabled &&
            state.nowPlaying?.station?.id in state.ipStreamStationIds
        Row(
            horizontalArrangement = Arrangement.spacedBy(skin.pad(6)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state.following) {
                // Playing THROUGH a fallback -> GREEN "aktiv": it works, this route carries the sound.
                // But if that FM won't come in (no RDS lock + mono = static), say so in ORANGE instead
                // of pretending it works.
                FollowingState.FM_FALLBACK -> {
                    val fmLabel = "FM" + (linkKhz?.let { " %.1f".format(it / 1000.0) } ?: "")
                    val onFm = { onInfo(com.px6.radio.ui.PillTopic.FM) }
                    if (state.fmFallbackWeak) StatusPill("$fmLabel · " + stringResource(R.string.tiles_no_reception), warn = true, onClick = onFm)
                    else StatusPill("$fmLabel " + stringResource(R.string.tiles_active), working = true, onClick = onFm)
                }
                FollowingState.IP_FALLBACK -> StatusPill(
                    "Internet " + stringResource(R.string.tiles_active),
                    // Green only while the stream is really audible; orange while it drops out and
                    // retries. The label stays put so the pill does not jump about — the colour
                    // carries the meaning, as everywhere else in this bar.
                    working = state.ipStreamLive, warn = !state.ipStreamLive,
                    onClick = { onInfo(com.px6.radio.ui.PillTopic.INTERNET) },
                )
                // DAB is playing -> the fallbacks that COULD take over are shown as quiet outlined
                // chips with just their name ("FM 98.8", "Internet") — available, not active.
                FollowingState.DAB_PRIMARY -> {
                    if (linkKhz != null) StatusPill("FM %.1f".format(linkKhz / 1000.0),
                        onClick = { onInfo(com.px6.radio.ui.PillTopic.FM) })
                    if (ipReady) StatusPill("Internet",
                        onClick = { onInfo(com.px6.radio.ui.PillTopic.INTERNET) })
                    if (linkKhz == null && !ipReady && dab != null) StatusPill(stringResource(R.string.tiles_no_fallback), dim = true)
                }
            }
        }
    }

    /**
     * A status-bar pill. Every pill in the bar (source, following tiers, ASA) shares ONE look —
     * an outlined rounded rect with a small status dot and a coloured label — so they read as one
     * family. Only the COLOUR carries meaning: green = live/working, orange = a problem, muted grey
     * = merely available (a route that could take over, not one that is).
     */
    @Composable
    private fun StatusPill(
        text: String,
        active: Boolean = false,
        dim: Boolean = false,
        working: Boolean = false,
        warn: Boolean = false,
        onClick: (() -> Unit)? = null,
    ) {
        val color = when {
            warn -> PILL_ORANGE
            working || active -> PILL_GREEN
            dim -> appColors.muted2
            else -> appColors.muted
        }
        Chip(text, color, onClick)
    }

    /**
     * The shared pill body used by every status-bar chip (see [StatusPill] and the ASA indicator).
     * Outlined, with a status dot and a coloured label all in [color]; the colour is animated so a
     * pill going live / amber / red fades rather than snaps.
     */
    @Composable
    private fun Chip(
        text: String,
        color: androidx.compose.ui.graphics.Color,
        onClick: (() -> Unit)? = null,
    ) {
        val c by animateColorAsState(color, tween(240), label = "pillColor")
        val shape = RoundedCornerShape(skin.cornerSmall)
        Row(
            Modifier.clip(shape)
                .border(skin.border.coerceAtLeast(1.dp), c, shape)
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = skin.pad(8), vertical = skin.pad(3)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c))
            Spacer(Modifier.width(skin.pad(6)))
            Text(
                text, color = c,
                fontSize = skin.font(skin.labelSize.value.minus(1).sp),
                fontWeight = FontWeight.SemiBold,
                letterSpacing = skin.labelTracking.em, maxLines = 1,
            )
        }
    }

    // One palette for EVERY status-bar pill (source, following tiers, ASA) so the same state always
    // reads as the same colour — green "live/OK", orange "problem/degraded", red "inoperable".
    private val PILL_GREEN = androidx.compose.ui.graphics.Color(0xFF25C26A)
    private val PILL_ORANGE = androidx.compose.ui.graphics.Color(0xFFE08A3C)
    private val PILL_RED = androidx.compose.ui.graphics.Color(0xFFE04C4C)

    private fun bandLabel(state: RadioUiState): String = buildString {
        append(
            when (state.selectedBand) {
                Band.DAB -> "DAB+"
                Band.FM -> "FM"
                Band.AM -> "AM"
                Band.IP -> "Internet"
            }
        )
        if (state.selectedBand == Band.DAB && state.noDabCoverage) append(" · KEIN EMPFANG")
        if (state.demoMode) append(" · DEMO")
        if (state.dabScanning) append(" · SUCHLAUF ${state.scanProgress}%")
    }

    @Composable
    private fun Hairline(color: androidx.compose.ui.graphics.Color = appColors.line) {
        if (skin.border.value > 0f) {
            Box(Modifier.fillMaxWidth().height(skin.border).background(color))
        }
    }

    /* ----------------------------------------------------------- preset page */

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun PresetPage(
        state: RadioUiState,
        actions: RadioActions,
        pager: PagerState,
        onGroup: (Int) -> Unit,
        pendingStore: Station?,
        onStored: () -> Unit,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = skin.pad(8)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepArrow("‹", actions::prev)
                Column(
                    Modifier.weight(1f).padding(horizontal = skin.pad(8)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val np = state.nowPlaying
                    val st = np?.station
                    val fixed = st != null && state.fixedNames.containsKey(st.id)
                    // Tap the name to open a floating framed slideshow window — same for DAB and IP,
                    // but only while there actually is a slideshow to show. Reset on station change.
                    var slideOpen by remember(st?.id) { mutableStateOf(false) }
                    val hasSlide = np?.slideshowImage != null
                    if (slideOpen && hasSlide) SlideshowWindow(state) { slideOpen = false }
                    // A frozen name is marked with a dot on either side and stops scrolling.
                    val shown = st?.let { state.displayName(it) } ?: "—"
                    // Crossfade the station name on change (station switch, "(FM)" suffix, freeze).
                    val nameText = (if (fixed) "· $shown ·" else shown)
                        .let { if (skin.titleUppercase) it.uppercase() else it }
                    Crossfade(targetState = nameText, animationSpec = tween(280), label = "npName") { value ->
                        Text(
                            value,
                            color = appColors.text,
                            fontSize = skin.font(skin.titleSize),
                            fontWeight = skin.titleWeight,
                            letterSpacing = skin.titleTracking.em,
                            textAlign = TextAlign.Center,
                            maxLines = 1, softWrap = false,
                            modifier = Modifier.fillMaxWidth()
                                .then(
                                    if (st != null) {
                                        Modifier.combinedClickable(
                                            onClick = { if (hasSlide) slideOpen = true },
                                            onLongClick = { actions.toggleFixedName(st) },
                                        )
                                    } else Modifier
                                )
                                .then(
                                    if (fixed) Modifier
                                    else Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                                ),
                        )
                    }
                    val line = np?.let {
                        if (it.dlArtist != null && it.dlTitle != null) "${it.dlArtist} — ${it.dlTitle}"
                        else it.dlsText
                    }
                    // Crossfade the metadata/DLS line when the track title changes.
                    Crossfade(targetState = line, animationSpec = tween(280), label = "npLine") { value ->
                        if (!value.isNullOrBlank()) {
                            Column(
                                Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Spacer(Modifier.height(skin.pad(6)))
                                Text(
                                    value, color = appColors.muted,
                                    fontSize = skin.font(skin.bodySize),
                                    textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                                    modifier = Modifier.fillMaxWidth()
                                        .basicMarquee(iterations = Int.MAX_VALUE),
                                )
                            }
                        }
                    }
                    // Extra channels carried by this service ("Zusatzsender"). Shown, not yet
                    // selectable — this omri build plays only a service's primary component.
                    val extras = st?.secondaryLabels.orEmpty()
                    if (extras.isNotEmpty()) {
                        Spacer(Modifier.height(skin.pad(8)))
                        Text(
                            stringResource(R.string.tiles_extra, extras.joinToString(" · ")),
                            color = appColors.muted2, fontSize = skin.font(skin.labelSize),
                            textAlign = TextAlign.Center, maxLines = 1,
                            softWrap = false, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (state.mutedNoReception) {
                        Spacer(Modifier.height(skin.pad(10)))
                        Text(
                            stringResource(R.string.tiles_muted_no_dab_fm),
                            color = appColors.fm, fontSize = skin.font(skin.labelSize),
                        )
                    }
                    if (pendingStore != null) {
                        Spacer(Modifier.height(skin.pad(10)))
                        Text(
                            stringResource(R.string.tiles_store_target_hint, pendingStore.name),
                            color = appColors.accent, fontSize = skin.font(skin.labelSize),
                        )
                    }
                }
                StepArrow("›", actions::next)
            }

            when (state.viewMode) {
                ViewMode.PRESETS -> {
                    // Claim the preset swipe area from Android's bottom system-gesture zone, so paging
                    // left/right never gets stolen as an app-switch/back.
                    HorizontalPager(
                        state = pager, pageSpacing = skin.pad(8),
                        modifier = Modifier.systemGestureExclusion(),
                    ) { pageIndex ->
                        PresetRow(state, actions, pageIndex, pendingStore, onStored)
                    }
                    GroupIndicator(pager.currentPage, onGroup)
                }
                ViewMode.STATION_INFO -> StationInfo(state, showImage = true)
                ViewMode.RADIO_TEXT -> StationInfo(state, showImage = false)
                // Full-screen slideshow replaces the whole lower half and then some.
                ViewMode.SLIDESHOW -> Slideshow(state, Modifier.fillMaxWidth().weight(2f))
            }
            Spacer(Modifier.height(skin.pad(8)))
        }
    }

    /** Radio text, optionally alongside the slideshow — the original's "Senderinfo". */
    @Composable
    private fun StationInfo(state: RadioUiState, showImage: Boolean) {
        val np = state.nowPlaying
        Row(
            Modifier.fillMaxWidth().padding(horizontal = skin.pad(16))
                .heightIn(min = touchMin * 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showImage && skin.showArtwork) {
                Slideshow(state, Modifier.size(touchMin * 2))
                Spacer(Modifier.width(skin.pad(16)))
            }
            Column(Modifier.weight(1f)) {
                val text = np?.dlsText ?: np?.let {
                    listOfNotNull(it.dlArtist, it.dlTitle).joinToString(" — ").ifBlank { null }
                }
                Text(
                    text ?: stringResource(R.string.tiles_no_radiotext),
                    color = if (text == null) appColors.muted2 else appColors.bodyText,
                    fontSize = skin.font(skin.bodySize),
                    maxLines = 4, overflow = TextOverflow.Ellipsis,
                )
                np?.slideshowCaption?.takeIf { showImage }?.let {
                    Spacer(Modifier.height(skin.pad(6)))
                    Text(
                        it, color = appColors.muted, fontSize = skin.font(skin.labelSize),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    @Composable
    private fun Slideshow(state: RadioUiState, modifier: Modifier) {
        val bytes = state.nowPlaying?.slideshowImage
        val bmp = remember(bytes) {
            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
        Box(
            modifier.clip(RoundedCornerShape(skin.cornerLarge)).background(appColors.softBg),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp, contentDescription = "Slideshow",
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    stringResource(R.string.tiles_no_slideshow), color = appColors.muted2,
                    fontSize = skin.font(skin.labelSize),
                )
            }
        }
    }

    /** Floating framed window with the station's slideshow + now-playing text. Tap to dismiss. */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun SlideshowWindow(state: RadioUiState, onClose: () -> Unit) {
        val np = state.nowPlaying ?: return
        val name = np.station.let { state.displayName(it) }
        val line = if (np.dlArtist != null && np.dlTitle != null) "${np.dlArtist} — ${np.dlTitle}"
            else np.dlsText
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            // Full-bleed dialog so we can place the card high on the screen (not dead-centre). The
            // surrounding area keeps the dim scrim and closes on tap, like a normal floating window.
            Box(
                // Sit high — just under the status bar — so the card overlays the station-name area
                // and reaches a little into the preset tiles, per the desired placement.
                Modifier.fillMaxSize().clickable(onClick = onClose).padding(top = touchMin * 0.5f),
                contentAlignment = Alignment.TopCenter,
            ) {
            // A clearly-floating card: elevated (drop shadow) above the dimmed scrim, accent frame.
            // Tap anywhere on it — or the ✕, or outside — to close (all three, so it is obvious).
            Column(
                Modifier
                    .width(touchMin * 7)
                    .shadow(24.dp, RoundedCornerShape(skin.cornerLarge))
                    .clip(RoundedCornerShape(skin.cornerLarge))
                    .background(appColors.panel)
                    .border(1.5.dp, appColors.accent, RoundedCornerShape(skin.cornerLarge))
                    .clickable(onClick = onClose)
                    .padding(skin.pad(14)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        name, color = appColors.text,
                        fontSize = skin.font(skin.bodySize), fontWeight = skin.titleWeight,
                        maxLines = 1, softWrap = false,
                        modifier = Modifier.weight(1f).basicMarquee(iterations = Int.MAX_VALUE),
                    )
                    // Explicit close affordance, so dismissing is discoverable at a glance.
                    Text(
                        "✕", color = appColors.muted, fontSize = skin.font(skin.bodySize),
                        modifier = Modifier.clickable(onClick = onClose).padding(start = skin.pad(8)),
                    )
                }
                Spacer(Modifier.height(skin.pad(10)))
                Slideshow(state, Modifier.size(touchMin * 4).aspectRatio(1f))
                if (!line.isNullOrBlank()) {
                    Spacer(Modifier.height(skin.pad(10)))
                    Text(
                        line, color = appColors.muted,
                        fontSize = skin.font(skin.labelSize),
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            }
        }
    }

    @Composable
    private fun StepArrow(glyph: String, onClick: () -> Unit) {
        Box(
            Modifier.size(touchMin).clip(RoundedCornerShape(skin.cornerSmall))
                .then(if (skin.filled) Modifier.background(appColors.softBg) else Modifier)
                .then(
                    if (skin.outlined) {
                        Modifier.border(skin.border, appColors.softBorder, RoundedCornerShape(skin.cornerSmall))
                    } else Modifier
                )
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) { Text(glyph, color = appColors.text, fontSize = skin.font(30.sp)) }
    }

    @Composable
    private fun PresetRow(
        state: RadioUiState,
        actions: RadioActions,
        group: Int,
        pendingStore: Station?,
        onStored: () -> Unit,
    ) {
        val first = group * PER_GROUP + 1
        Row(
            Modifier.fillMaxWidth().padding(horizontal = skin.pad(8)),
            horizontalArrangement = Arrangement.spacedBy(skin.pad(8)),
        ) {
            (first until first + PER_GROUP).forEach { index ->
                val st = state.presets.firstOrNull { it.index == index }?.stationId
                    ?.let { state.station(it) }
                PresetTile(
                    index = index,
                    station = st,
                    name = st?.let { state.displayName(it) },
                    active = st != null && st.id == state.nowPlaying?.station?.id,
                    tuning = st != null && st.id == state.tuningStationId,
                    modifier = Modifier.weight(1f),
                    onTap = {
                        // A station picked in the list is waiting for its target button.
                        if (pendingStore != null) {
                            actions.selectStation(pendingStore)
                            actions.assignPreset(index)
                            onStored()
                        } else actions.tunePreset(index)
                    },
                    onLongPress = { actions.assignPreset(index) },
                )
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun PresetTile(
        index: Int,
        station: Station?,
        name: String?,
        active: Boolean,
        tuning: Boolean,
        modifier: Modifier,
        onTap: () -> Unit,
        onLongPress: () -> Unit,
    ) {
        val shape = RoundedCornerShape(skin.cornerLarge)
        // Tap-feedback (press-scale) + animated active highlight (accent fades in, not a hard flip).
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(
            if (pressed) 0.955f else 1f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "tilePress",
        )
        val bgColor by animateColorAsState(
            if (active) appColors.accentSoft else appColors.softBg, tween(220), label = "tileBg",
        )
        val borderColor by animateColorAsState(
            if (active) appColors.accentBorder else appColors.softBorder, tween(220), label = "tileBorder",
        )
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(skin.tileAspect)
                    .heightIn(min = touchMin)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(shape)
                    .then(if (skin.filled) Modifier.background(bgColor) else Modifier)
                    .then(
                        if (skin.outlined || active) {
                            Modifier.border(if (active) skin.border * 2 else skin.border, borderColor, shape)
                        } else Modifier
                    )
                    .combinedClickable(
                        interactionSource = interaction, indication = null,
                        onClick = onTap, onLongClick = onLongPress,
                    ),
            ) {
                // Stylish, large, faint preset number sitting in the background — the logo/name draw
                // on top. A quiet watermark in the bottom-right corner instead of a small chip.
                Text(
                    "$index",
                    color = (if (active) appColors.accent else appColors.muted2).copy(alpha = 0.22f),
                    fontSize = skin.font((skin.titleSize.value * 2.4f).sp),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = skin.pad(6)),
                )
                if (skin.tileLabel == TileLabel.INSIDE) {
                    TileName(name, active, Modifier.align(Alignment.Center))
                } else if (station == null) {
                    Text(
                        "—", color = appColors.muted2, fontSize = skin.font(skin.bodySize),
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else if (skin.showLogos) {
                    StationLogo(
                        station = station,
                        modifier = Modifier.align(Alignment.Center)
                            .padding(skin.pad(10)).fillMaxSize(),
                    )
                }
                // One shared set of station buttons across all bands — pressing one switches the
                // band along with it. The mark says which band you are about to land on, so the
                // switch is never a surprise. Drawn LAST so a full-bleed station logo cannot cover
                // it: a top-left overlay chip with a solid dark backing, legible on any logo.
                if (station != null && skin.showBandBadges) {
                    Text(
                        when (station.band) {
                            Band.DAB -> "DAB+"
                            Band.FM -> "FM"
                            Band.AM -> "AM"
                            Band.IP -> "NET"
                        },
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.92f),
                        fontSize = skin.font(skin.labelSize.value.minus(2).sp),
                        // Inset symmetrically (top == left) and aligned with the logo's own inset
                        // so the chip sits inside the artwork, not jammed into the corner.
                        modifier = Modifier.align(Alignment.TopStart).padding(skin.pad(11))
                            .clip(RoundedCornerShape(skin.cornerSmall))
                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = skin.pad(6), vertical = skin.pad(3)),
                    )
                }
                // "tuning…" loader over the tile while this station is selected but not yet audible.
                if (tuning) com.px6.radio.ui.TuningOverlay(
                    Modifier.align(Alignment.Center).padding(skin.pad(8)).fillMaxSize(), ring = 30.dp,
                )
            }
            if (skin.tileLabel == TileLabel.BELOW) {
                Spacer(Modifier.height(skin.pad(4)))
                TileName(name, active, Modifier)
            }
            if (skin.activeUnderline) {
                Spacer(Modifier.height(skin.pad(4)))
                Box(
                    Modifier.fillMaxWidth().height(2.dp)
                        .background(if (active) appColors.accent else androidx.compose.ui.graphics.Color.Transparent)
                )
            }
        }
    }

    /**
     * A station's logo, or the coloured initials plate when none is stored. Bitmaps come from the
     * cache, decoded once and kept — decoding inside a scrolling list would cost frames.
     *
     * Deliberately not [com.px6.radio.ui.StationLogo]: this frontend's no-logo case is quiet
     * initials rather than that one's coloured plate. Only the bitmap branch must stay in step.
     */
    @Composable
    private fun StationLogo(station: Station, modifier: Modifier) {
        val store = LocalLogoStore.current
        val version = LocalLogoVersion.current
        val bmp = remember(station.id, version) { store?.bitmap(station) }
        if (bmp != null) {
            Image(
                bitmap = bmp, contentDescription = station.name,
                // Follow the tile's own rounding. A full-bleed square logo (RFI) otherwise kept hard
                // corners inside a rounded tile, and the tuning overlay's rounded scrim then left
                // those corners sticking out undimmed.
                modifier = modifier.clip(RoundedCornerShape(skin.cornerSmall)),
                contentScale = ContentScale.Fit,
            )
        } else {
            // Without a logo the tile stays quiet — just the initials, no coloured block. A wall
            // of gradients would shout louder than the station name it is meant to support.
            Box(modifier, contentAlignment = Alignment.Center) {
                Text(
                    station.logoInitials, color = appColors.muted,
                    fontSize = skin.font(skin.bodySize.value.plus(4).sp),
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = skin.labelTracking.em,
                )
            }
        }
    }

    @Composable
    private fun TileName(name: String?, active: Boolean, modifier: Modifier) {
        Text(
            name?.let { if (skin.labelUppercase) it.uppercase() else it } ?: "—",
            color = if (active) appColors.accent else appColors.muted,
            fontSize = skin.font(skin.labelSize),
            letterSpacing = skin.labelTracking.em,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth().padding(horizontal = skin.pad(2)),
        )
    }

    /** Three short bars marking which group of station buttons is showing. */
    @Composable
    private fun GroupIndicator(group: Int, onGroup: (Int) -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(top = skin.pad(10)),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(GROUPS) { i ->
                Box(
                    Modifier.padding(horizontal = skin.pad(6))
                        // The bar itself is small, so the touch area around it carries the size.
                        .height(touchMin * 0.4f)
                        .width(64.dp)
                        .clickable { onGroup(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(3.dp)
                            .background(if (i == group) appColors.accent else appColors.signalOff)
                    )
                }
            }
        }
    }

    /* ------------------------------------------------------------ list page */

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun StationListPage(
        state: RadioUiState,
        actions: RadioActions,
        pendingStore: Station?,
        onPending: (Station?) -> Unit,
        onClose: () -> Unit,
    ) {
        // The original closes the station list by itself after a while without input. Every touch
        // restarts the countdown; a station waiting to be stored suspends it.
        var idleTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(idleTick, pendingStore) {
            if (pendingStore == null) {
                delay(LIST_IDLE_MILLIS)
                onClose()
            }
        }
        Column(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Final)
                        idleTick++
                    }
                }
            }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(skin.pad(8)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Band tabs: DAB+ / FM / AM / Internet (FM/AM only when a tuner is present). Tapping
                // switches the band and the list shows that band — one mixed library, no favourites.
                val bands = availableBands(state)
                bands.forEach { band ->
                    val on = state.selectedBand == band
                    val label = bandName(band)
                    Box(
                        Modifier.weight(1f).padding(horizontal = skin.pad(4))
                            .heightIn(min = touchMin)
                            .clip(RoundedCornerShape(skin.cornerSmall))
                            .then(
                                if (on) Modifier.background(appColors.accentSoft) else Modifier
                            )
                            .then(
                                if (skin.outlined) Modifier.border(
                                    skin.border,
                                    if (on) appColors.accentSoftBorder else appColors.line,
                                    RoundedCornerShape(skin.cornerSmall),
                                ) else Modifier
                            )
                            .tappable { actions.selectBand(band) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label, color = if (on) appColors.accent else appColors.muted,
                            fontSize = skin.font(skin.bodySize), maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.width(skin.pad(4)))
                // Sorting: alphabetically or in the order the stations sit on the band.
                Box(
                    Modifier.size(touchMin).clip(RoundedCornerShape(skin.cornerSmall))
                        .clickable {
                            actions.setSortMode(
                                if (state.sortMode == SortMode.ALPHABET) SortMode.GROUP
                                else SortMode.ALPHABET
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (state.sortMode == SortMode.ALPHABET) "A–Z" else "▤",
                        color = appColors.muted, fontSize = skin.font(skin.bodySize),
                    )
                }
                Box(
                    Modifier.size(touchMin).clip(RoundedCornerShape(skin.cornerSmall))
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = appColors.muted, fontSize = skin.font(20.sp)) }
            }
            if (pendingStore != null) {
                Text(
                    stringResource(R.string.tiles_store_selected_hint, pendingStore.name),
                    color = appColors.accent, fontSize = skin.font(skin.labelSize),
                    modifier = Modifier.padding(horizontal = skin.pad(14), vertical = skin.pad(4)),
                )
            }
            // Cache the sorted/filtered list: without this the getter (filter + distinctBy +
            // sortedWith) re-runs on every pointer event, because the idle-timer recomposes this
            // page on each touch — visible jank while dragging the list on the RK3399.
            val visible = remember(
                state.stations, state.selectedBand, state.sortMode, state.fmAvailable,
            ) { state.visibleStations }
            // In "Gruppen"-sort, a header before the first station of each group (city for internet,
            // ensemble for DAB); station id -> header text. FM/AM have no group key -> no headers.
            val headerBefore = remember(visible, state.sortMode) {
                if (state.sortMode != SortMode.GROUP) emptyMap()
                else buildMap {
                    var last: String? = null
                    visible.forEach { st ->
                        val g = st.ensemble?.takeIf { it.isNotBlank() }
                        if (g != null && g != last) put(st.id, g)
                        if (g != null) last = g
                    }
                }
            }
            val listState = rememberLazyListState()
            // Open the list already scrolled to the station that's playing (e.g. the current stream),
            // so it's visible and highlighted instead of the user having to scroll to find it.
            val playingId = state.nowPlaying?.station?.id
            LaunchedEffect(playingId, visible) {
                val idx = visible.indexOfFirst { it.id == playingId }
                if (idx >= 0) listState.scrollToItem(idx)
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight().padding(horizontal = skin.pad(8)),
                    state = listState,
                ) {
                    items(visible, key = { it.id }) { st ->
                        headerBefore[st.id]?.let { GroupHeader(it) }
                        val stored = state.presets.any { it.stationId == st.id }
                        ListRow(
                            station = st,
                            name = state.displayName(st),
                            playing = st.id == state.nowPlaying?.station?.id,
                            stored = stored,
                            asaCapable = st.band == Band.DAB &&
                                (st.id.substringBefore('.').toIntOrNull(16) ?: -1) in state.ewsEnsembleIds,
                            // Tapping tunes the station and LEAVES THE LIST OPEN. It used to jump
                            // back to the presets page, which made browsing impossible: every try
                            // cost a trip back into the list. The list closes on its own (the idle
                            // timeout below) or with the close control.
                            onTap = { actions.selectStation(st) },
                            // Long-press picks a station to be stored on the next button tapped.
                            onLongPress = { onPending(st) },
                        )
                        if (skin.listStyle == com.px6.radio.ui.theme.ListStyle.DIVIDED) Hairline()
                    }
                }
                // Up/down rail: a short tap scrolls a step (with a glide), press-and-hold accelerates
                // and releases into a decaying fling — the same "Schwung" feel as flinging the list.
                Column(
                    Modifier.fillMaxHeight()
                        .padding(end = skin.pad(4), top = skin.pad(12), bottom = skin.pad(12)),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    ScrollButton("▲", -1, listState)
                    ScrollButton("▼", +1, listState)
                }
            }
        }
    }

    /**
     * A list-scroll button that supports both a short **tap** (glide one step) and a **hold**
     * (accelerate, then release into a momentum fling). Direction is -1 (up) or +1 (down).
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ScrollButton(glyph: String, direction: Int, listState: LazyListState) {
        val scope = rememberCoroutineScope()
        Box(
            Modifier.size(touchMin)
                .clip(RoundedCornerShape(skin.cornerSmall))
                .then(
                    if (skin.outlined) Modifier.border(
                        skin.border, appColors.line, RoundedCornerShape(skin.cornerSmall),
                    ) else Modifier
                )
                .pointerInput(Unit) {
                    val stepPx = { (listState.layoutInfo.viewportSize.height * 0.5f).coerceAtLeast(240f) }
                    detectTapGestures(
                        // Short tap: glide one comfortable step.
                        onTap = {
                            scope.launch {
                                listState.animateScrollBy(
                                    direction * stepPx(), tween(320, easing = FastOutSlowInEasing),
                                )
                            }
                        },
                        // Press-and-hold: after a short grace period, accelerate; on release, fling on.
                        onPress = {
                            var accelerated = false
                            var v = 22f
                            val job = scope.launch {
                                delay(260)               // below this it's a tap, handled by onTap
                                accelerated = true
                                while (isActive) {
                                    listState.scrollBy(direction * v)
                                    v = (v * 1.06f).coerceAtMost(110f)
                                    delay(16)
                                }
                            }
                            tryAwaitRelease()
                            job.cancel()
                            if (accelerated) scope.launch {
                                listState.animateScrollBy(
                                    direction * v * 6f, tween(420, easing = FastOutSlowInEasing),
                                )
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = appColors.muted, fontSize = skin.font(22.sp))
        }
    }

    /** A city/ensemble group heading in the station list (only in "Gruppen"-sort). */
    @Composable
    private fun GroupHeader(text: String) {
        Text(
            text.uppercase(),
            color = appColors.accent,
            fontSize = skin.font(skin.labelSize.value.minus(1).sp),
            fontWeight = FontWeight.SemiBold,
            letterSpacing = skin.labelTracking.em, maxLines = 1,
            modifier = Modifier.fillMaxWidth()
                .padding(start = skin.pad(8), top = skin.pad(12), bottom = skin.pad(2)),
        )
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ListRow(
        station: Station,
        name: String,
        playing: Boolean,
        stored: Boolean,
        asaCapable: Boolean,
        onTap: () -> Unit,
        onLongPress: () -> Unit,
    ) {
        val shape = RoundedCornerShape(skin.cornerLarge)
        val cards = skin.listStyle == com.px6.radio.ui.theme.ListStyle.CARDS
        Row(
            Modifier.fillMaxWidth().padding(vertical = skin.pad(3))
                .heightIn(min = touchMin + skin.rowExtra)
                .then(if (cards) Modifier.clip(shape) else Modifier)
                .then(
                    if (playing) Modifier.background(appColors.rowSelected) else Modifier
                )
                .combinedClickable(onClick = onTap, onLongClick = onLongPress)
                .padding(horizontal = skin.pad(10)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (playing) "▶" else " ", color = appColors.accent,
                fontSize = skin.font(skin.bodySize), modifier = Modifier.width(skin.pad(22)),
            )
            if (skin.showLogos) {
                StationLogo(station, Modifier.size(touchMin * 0.6f))
                Spacer(Modifier.width(skin.pad(12)))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    name, color = appColors.text, fontSize = skin.font(skin.bodySize),
                    fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    station.subtitle +
                        // A service with extra channels is marked, as the original does with a
                        // symbol next to the name.
                        if (station.secondaryLabels.isNotEmpty()) "  ＋${station.secondaryLabels.size}" else "",
                    color = appColors.muted,
                    fontSize = skin.font(skin.labelSize), maxLines = 1, softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (asaCapable) {
                // This station's ensemble carries EWS (ASA) — small green badge.
                Box(
                    Modifier.padding(end = skin.pad(8)).clip(RoundedCornerShape(6.dp))
                        .border(1.dp, appColors.okText, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(stringResource(R.string.status_asa), color = appColors.okText,
                        fontSize = skin.font(skin.labelSize), fontWeight = FontWeight.Bold)
                }
            }
            if (skin.showBandBadges) {
                Text(
                    when (station.band) {
                        Band.DAB -> "DAB+"
                        Band.FM -> "FM"
                        Band.AM -> "AM"
                        Band.IP -> "NET"
                    },
                    color = appColors.muted2, fontSize = skin.font(skin.labelSize),
                )
                Spacer(Modifier.width(skin.pad(8)))
            }
            // A star simply marks stations that already sit on a station button (read-only now —
            // saving is done by long-pressing a tile; there is no separate favourites list).
            if (stored) {
                Text("★", color = appColors.star, fontSize = skin.font(24.sp))
                Spacer(Modifier.width(skin.pad(6)))
            }
        }
    }

    /* ---------------------------------------------------------- manual page */

    /**
     * Frequency band, behaving as the manual describes: the arrows step when tapped and seek to
     * the next receivable station when held, the marker can be dragged along the scale, and the
     * band hides itself again after a while without input.
     *
     * On DAB there are no frequencies to walk, so a step is the next service and a hold the next
     * ensemble — which is what the original does too.
     */
    @Composable
    private fun ManualPage(state: RadioUiState, actions: RadioActions, onHide: () -> Unit) {
        val band = state.selectedBand
        // Internet radio has no frequency to walk — so the "manual" area becomes search & add. The
        // saved internet stations still play from the tiles/list like any other band.
        if (band == Band.IP) {
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = skin.pad(12)),
            ) {
                com.px6.radio.ui.InternetPanel(
                    stations = state.stations.filter { it.band == Band.IP },
                    results = state.internetResults,
                    searching = state.internetSearching,
                    onSearch = actions::searchInternet,
                    onAdd = actions::addInternetStation,
                    onRemove = actions::removeInternetStation,
                    onPlay = actions::selectStation,
                )
            }
            return
        }
        val profile = remember(band, state.settings.fmRegion) {
            TuningProfile.forBand(band, state.settings.fmRegion)
        }
        // Only take the playing station's frequency when it actually belongs to this band —
        // otherwise a DAB service (whose frequency is its ensemble channel, e.g. 180 064 kHz)
        // would land on the FM scale and read as a nonsense "180064 MHz".
        val playingOnThisBand = state.nowPlaying?.station?.takeIf { it.band == band }
        val current = state.manualFrequencyKhz ?: playingOnThisBand?.frequencyKhz
        val fraction = current?.let {
            ((it - profile.minKhz).toFloat() / (profile.maxKhz - profile.minKhz)).coerceIn(0f, 1f)
        } ?: 0.5f

        // Hides itself again without input, exactly like the original frequency band.
        var idleTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(idleTick) {
            delay(MANUAL_IDLE_MILLIS)
            onHide()
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = skin.pad(24))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Final)
                            idleTick++
                        }
                    }
                },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                when {
                    // On DAB the ensemble is what identifies the multiplex, the channel is below.
                    band == Band.DAB -> state.nowPlaying?.station?.ensemble ?: "DAB+"
                    current != null -> profile.format(current)
                    else -> bandName(band)
                },
                color = appColors.text, fontSize = skin.font(skin.titleSize),
                fontWeight = skin.titleWeight, letterSpacing = skin.titleTracking.em,
            )
            Spacer(Modifier.height(skin.pad(6)))
            Text(
                state.nowPlaying?.station?.let { state.displayName(it) } ?: "—",
                color = appColors.muted, fontSize = skin.font(skin.bodySize), maxLines = 1,
            )

            Spacer(Modifier.height(skin.pad(28)))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TuneArrow("‹", onStep = { actions.tuneStep(false) }, onSeek = { actions.seekStation(false) })
                Box(
                    Modifier.weight(1f).padding(horizontal = skin.pad(16)),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BandScale(
                        profile = profile,
                        fraction = fraction,
                        // A continuous band can be dialled anywhere on the raster; a channel
                        // table cannot — there the marker only reports where we are.
                        onSeekTo = (profile as? TuningProfile.Continuous)?.let { p ->
                            { f ->
                                val raw = p.minKhz + ((p.maxKhz - p.minKhz) * f).toInt()
                                actions.tuneFrequency(p.snap(raw))
                            }
                        },
                    )
                }
                TuneArrow("›", onStep = { actions.tuneStep(true) }, onSeek = { actions.seekStation(true) })
            }

            Spacer(Modifier.height(skin.pad(10)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    edgeLabel(profile, profile.minKhz), color = appColors.muted2,
                    fontSize = skin.font(skin.labelSize),
                )
                Text(
                    edgeLabel(profile, profile.maxKhz), color = appColors.muted2,
                    fontSize = skin.font(skin.labelSize),
                )
            }
            Spacer(Modifier.height(skin.pad(18)))
            Text(
                when (profile) {
                    is TuningProfile.Channels ->
                        stringResource(R.string.tiles_manual_dab_hint, profile.channels.size)
                    is TuningProfile.Continuous ->
                        stringResource(R.string.tiles_manual_cont_hint, stepLabel(profile))
                },
                color = appColors.muted2, fontSize = skin.font(skin.labelSize),
                textAlign = TextAlign.Center,
            )
        }
    }

    private fun edgeLabel(profile: TuningProfile, khz: Int): String = when (profile) {
        is TuningProfile.Channels -> profile.nearest(khz).name
        is TuningProfile.Continuous -> profile.format(khz)
    }

    private fun stepLabel(profile: TuningProfile.Continuous): String =
        if (profile.stepKhz >= 1000) "%.1f MHz".format(profile.stepKhz / 1000f)
        else "${profile.stepKhz} kHz"

    /**
     * The band as it really is: evenly spaced ticks on a continuous band, one tick per channel at
     * its true position on DAB — where the spacing is uneven and an even scale would be a lie.
     */
    @Composable
    private fun BandScale(
        profile: TuningProfile,
        fraction: Float,
        onSeekTo: ((Float) -> Unit)?,
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(touchMin)
                .then(
                    if (onSeekTo == null) Modifier else Modifier.pointerInput(profile) {
                        detectHorizontalDragGestures { change, _ ->
                            onSeekTo((change.position.x / size.width).coerceIn(0f, 1f))
                        }
                    }
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            val width = maxWidth
            when (profile) {
                is TuningProfile.Continuous -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        repeat(TICKS) { i ->
                            val tall = i % 5 == 0
                            Box(
                                Modifier.width(skin.border.coerceAtLeast(1.dp))
                                    .height(if (tall) 18.dp else 10.dp)
                                    .background(if (tall) appColors.muted else appColors.signalOff)
                            )
                        }
                    }
                }
                is TuningProfile.Channels -> {
                    val span = (profile.maxKhz - profile.minKhz).toFloat()
                    profile.channels.forEach { channel ->
                        val f = (channel.khz - profile.minKhz) / span
                        // Block boundaries (5A, 6A, …) get the taller mark.
                        val tall = channel.name.endsWith("A")
                        Box(
                            Modifier.padding(start = (width * f - 0.5.dp).coerceAtLeast(0.dp))
                                .align(Alignment.BottomStart)
                                .width(skin.border.coerceAtLeast(1.dp))
                                .height(if (tall) 18.dp else 10.dp)
                                .background(if (tall) appColors.muted else appColors.signalOff)
                        )
                    }
                }
            }
            Box(
                Modifier.padding(start = (width * fraction - 1.5.dp).coerceAtLeast(0.dp))
                    .align(Alignment.CenterStart)
                    .width(3.dp).height(34.dp).background(appColors.accent)
            )
        }
    }

    /** Tap steps, hold seeks — the two behaviours the arrows have in the original. */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun TuneArrow(glyph: String, onStep: () -> Unit, onSeek: () -> Unit) {
        Box(
            Modifier.size(touchMin).clip(RoundedCornerShape(skin.cornerSmall))
                .then(if (skin.filled) Modifier.background(appColors.softBg) else Modifier)
                .then(
                    if (skin.outlined) {
                        Modifier.border(skin.border, appColors.softBorder, RoundedCornerShape(skin.cornerSmall))
                    } else Modifier
                )
                .combinedClickable(onClick = onStep, onLongClick = onSeek),
            contentAlignment = Alignment.Center,
        ) { Text(glyph, color = appColors.text, fontSize = skin.font(30.sp)) }
    }

    /* --------------------------------------------------------- function bar */

    @Composable
    private fun FunctionBar(
        state: RadioUiState,
        page: Page,
        onPage: (Page) -> Unit,
        onBand: () -> Unit,
        onView: () -> Unit,
        onSettings: () -> Unit,
    ) {
        Hairline(appColors.accent)
        Row(
            Modifier.fillMaxWidth().background(appColors.transportBg)
                .heightIn(min = touchMin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FunctionItem(stringResource(R.string.tiles_band), bandOnly(state), Modifier.weight(1f), false, onBand)
            FunctionItem(stringResource(R.string.tiles_stations), "☰", Modifier.weight(1f), page == Page.LIST) {
                onPage(if (page == Page.LIST) Page.PRESETS else Page.LIST)
            }
            // On internet radio there is nothing to tune, so the same slot becomes "Streams" — the
            // search-and-add view for internet stations.
            FunctionItem(
                if (state.selectedBand == Band.IP) stringResource(R.string.tiles_streams) else stringResource(R.string.tiles_manual),
                "⌁", Modifier.weight(1f), page == Page.MANUAL,
            ) {
                onPage(if (page == Page.MANUAL) Page.PRESETS else Page.MANUAL)
            }
            // "Ansicht" (Senderinfo/Radiotext/Slideshow) on DAB and now IP too — internet stations get
            // live text + slideshow via RadioVIS. FM/AM have no slideshow surface, so it stays hidden.
            if (state.selectedBand == Band.DAB || state.selectedBand == Band.IP) {
                FunctionItem(
                    stringResource(R.string.tiles_view), viewGlyph(state.viewMode), Modifier.weight(1f),
                    state.viewMode != ViewMode.PRESETS, onView,
                )
            }
            FunctionItem(stringResource(R.string.tiles_settings), "⚙", Modifier.weight(1f), false, onSettings)
        }
    }

    private fun viewGlyph(mode: ViewMode) = when (mode) {
        ViewMode.PRESETS -> "▦"
        ViewMode.STATION_INFO -> "▤"
        ViewMode.RADIO_TEXT -> "≡"
        ViewMode.SLIDESHOW -> "▣"
    }

    private fun bandOnly(state: RadioUiState) = when (state.selectedBand) {
        Band.DAB -> "DAB+"
        Band.FM -> "FM"
        Band.AM -> "AM"
        Band.IP -> "Internet"
    }

    /** Clickable with subtle press-scale tap-feedback (no ripple) — the shared "button feel". */
    @Composable
    private fun Modifier.tappable(min: Float = 0.94f, onClick: () -> Unit): Modifier {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(
            if (pressed) min else 1f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "press",
        )
        return this
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    }

    @Composable
    private fun FunctionItem(
        label: String,
        value: String,
        modifier: Modifier,
        active: Boolean,
        onClick: () -> Unit,
    ) {
        Column(
            modifier.heightIn(min = touchMin).widthIn(min = touchMin)
                .tappable(onClick = onClick)
                .padding(vertical = skin.pad(6)),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (skin.labelUppercase) label.uppercase() else label,
                color = if (active) appColors.accent else appColors.muted2,
                fontSize = skin.font(skin.labelSize.value.minus(1).sp),
                letterSpacing = skin.labelTracking.em, maxLines = 1,
            )
            Text(
                value, color = if (active) appColors.accent else appColors.text,
                fontSize = skin.font(skin.bodySize), fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
        }
    }
}
