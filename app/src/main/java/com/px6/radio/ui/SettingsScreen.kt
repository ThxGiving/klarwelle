package com.px6.radio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.px6.radio.R
import com.px6.radio.model.Band
import com.px6.radio.model.Settings
import com.px6.radio.model.Station
import com.px6.radio.model.StepMode
import com.px6.radio.model.ThemeMode
import com.px6.radio.ui.frontend.RadioFrontend
import com.px6.radio.ui.theme.AccentColor
import com.px6.radio.ui.theme.Skin
import com.px6.radio.ui.theme.appColors
import com.px6.radio.ui.theme.touchMin

/** Public, versioned in the repository; GitHub renders the Markdown. Switch to a Pages URL later. */
private const val PRIVACY_URL = "https://github.com/ThxGiving/klarwelle/blob/master/docs/datenschutz.md"
private const val SOURCE_URL = "https://github.com/ThxGiving/klarwelle"

/**
 * Settings as a list of uniform rows with sub-pages: a title bar with a back key, one row per
 * setting, a scroll bar as soon as the page is longer than the screen. Rows come in a few fixed
 * shapes — a value that opens an option window, a check box, a row that leads deeper, an action
 * with its button on the right.
 *
 * The settings themselves are ours; only the shape follows the head-unit convention, because that
 * is the one that works while driving: everything the same size, in the same place, never more
 * than two levels deep. Scrolling carries momentum, as everywhere else in this app.
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    dabPresent: Boolean,
    dabScanning: Boolean,
    scanProgress: Int,
    fmAvailable: Boolean,
    fmSeeking: Boolean,
    headlightOn: Boolean?,
    onScanDab: () -> Unit,
    onScanFm: () -> Unit,
    skins: List<Skin>,
    frontends: List<RadioFrontend>,
    onClearPreset: (Int?) -> Unit,
    presets: List<Pair<Int, String>>,
    logoCount: Int,
    logoDownloading: Boolean,
    logoStatus: String?,
    onDownloadLogos: () -> Unit,
    onClearLogos: () -> Unit,
    onRefreshRadioDns: () -> Unit,
    onResetBackends: () -> Unit,
    onDeleteDiagnostics: () -> Unit,
    internetStations: List<Station>,
    internetResults: List<Station>,
    internetSearching: Boolean,
    onSearchInternet: (String) -> Unit,
    onAddInternet: (Station) -> Unit,
    onRemoveInternet: (String) -> Unit,
    onAddManualStream: (String, String) -> Unit,
    placeResults: List<com.px6.radio.ews.Place>,
    placeSearching: Boolean,
    onSearchPlace: (String) -> Unit,
    onBack: () -> Unit,
    onChange: ((Settings) -> Settings) -> Unit,
) {
    var page by remember { mutableStateOf(Page.ROOT) }

    val title = when (page) {
        Page.ROOT -> stringResource(R.string.settings_title_root, "")
        Page.BANDS -> stringResource(R.string.settings_bands)
        Page.BAND_DAB -> "DAB+"
        Page.BAND_FM -> "FM"
        Page.BAND_AM -> "AM"
        Page.INTERNET -> stringResource(R.string.settings_internet)
        Page.PLAYBACK -> stringResource(R.string.settings_playback)
        Page.AUDIO -> stringResource(R.string.settings_audio)
        Page.LOGOS -> stringResource(R.string.settings_logos)
        Page.RADIODNS -> "RadioDNS"
        Page.ASA -> stringResource(R.string.settings_asa)
        Page.ASA_CODES -> stringResource(R.string.settings_dab_location_code)
        Page.APPEARANCE -> stringResource(R.string.settings_appearance)
        Page.SYSTEM -> stringResource(R.string.settings_system)
        Page.ABOUT -> stringResource(R.string.settings_about)
        Page.LICENSE -> stringResource(R.string.settings_license)
    }

    Column(Modifier.fillMaxSize().background(appColors.bg)) {
        TitleBar(title) { page = page.parent()?.also { } ?: run { onBack(); Page.ROOT } }
        HairLine()

        val scroll = rememberScrollState()
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(scroll)) {
                when (page) {
                    Page.ROOT -> RootPage(
                        settings = settings,
                        dabPresent = dabPresent,
                        logoCount = logoCount,
                        internetCount = internetStations.size,
                        onOpen = { page = it },
                    )
                    Page.BANDS -> BandsPage(
                        dabPresent = dabPresent,
                        fmAvailable = fmAvailable,
                        internetCount = internetStations.size,
                        onOpen = { page = it },
                    )
                    Page.BAND_DAB -> BandDabPage(
                        settings = settings,
                        dabScanning = dabScanning,
                        scanProgress = scanProgress,
                        fmAvailable = fmAvailable,
                        onScanDab = onScanDab,
                        onChange = onChange,
                    )
                    Page.BAND_FM -> BandFmPage(settings, fmSeeking, onScanFm, onChange)
                    Page.BAND_AM -> BandAmPage(settings, fmSeeking, onScanFm, onChange)
                    Page.PLAYBACK -> PlaybackPage(
                        settings = settings,
                        presets = presets,
                        onClearPreset = onClearPreset,
                        onChange = onChange,
                    )
                    Page.AUDIO -> AudioPage(settings, onChange)
                    Page.APPEARANCE -> AppearancePage(settings, skins, frontends, headlightOn, onChange)
                    Page.LOGOS -> LogoPage(
                        settings, logoCount, logoDownloading, logoStatus,
                        onDownloadLogos, onClearLogos, onChange,
                    )
                    Page.RADIODNS -> RadioDnsPage(settings, onChange, onRefreshRadioDns)
                    Page.INTERNET -> InternetPage(
                        stations = internetStations,
                        results = internetResults,
                        searching = internetSearching,
                        onSearch = onSearchInternet,
                        onAdd = onAddInternet,
                        onRemove = onRemoveInternet,
                        onAddManual = onAddManualStream,
                    )
                    Page.ASA -> AsaPage(settings, onOpen = { page = it }, onChange = onChange)
                    Page.ASA_CODES -> LocationCodesSection(
                        codes = settings.asaLocationCodes,
                        enabled = settings.asaEnabled,
                        placeResults = placeResults,
                        placeSearching = placeSearching,
                        onSearchPlace = onSearchPlace,
                    ) { codes -> onChange { it.copy(asaLocationCodes = codes) } }
                    Page.SYSTEM -> SystemPage(
                        settings = settings,
                        onOpen = { page = it },
                        onChange = onChange,
                        onResetBackends = onResetBackends,
                        onDeleteDiagnostics = onDeleteDiagnostics,
                    )
                    Page.ABOUT -> AboutPage(fmAvailable, dabPresent)
                    Page.LICENSE -> LicensePage()
                }
                Spacer(Modifier.height(24.dp))
            }
            ScrollBar(scroll.value, scroll.maxValue)
        }
    }
}

/**
 * Every dialog in the app needs this. Compose's default (usePlatformDefaultWidth = true) caps a
 * dialog at a platform width that has nothing to do with our layout, so any widthIn/fillMaxWidth we
 * set was silently ignored — on the car screen the panels came out about a quarter of the display
 * wide and their values wrapped or were cut off. Switching it off hands the sizing back to us.
 */
private val DIALOG_FULL_WIDTH = DialogProperties(usePlatformDefaultWidth = false)

/**
 * A dialog size that fits the screen it is actually on.
 *
 * Fixed dp minimums are a trap here: the head unit's panel is 1024x600 pixels, but at a higher
 * display density that is far fewer dp than the emulator's, and a `widthIn(min = 700.dp)` then
 * exceeds the display instead of filling it. Worse for height — the location-code keypad simply ran
 * off the bottom and its buttons were unreachable. So ask the configuration and take a fraction.
 */
@Composable
private fun dialogSize(maxWidth: androidx.compose.ui.unit.Dp): Modifier {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val w = (cfg.screenWidthDp * 0.92f).dp
    return Modifier
        .widthIn(max = if (w < maxWidth) w else maxWidth)
        .heightIn(max = (cfg.screenHeightDp * 0.94f).dp)
}

/**
 * The settings tree. Two levels for most things, three where a group has real depth (a band, the
 * ASA location codes). Grouped by subject rather than by which backend implements it — the band
 * pages are the clearest case: what a scan does used to depend on a band switch at the top of the
 * root page, which was a hidden mode.
 */
private enum class Page {
    ROOT,
    BANDS, BAND_DAB, BAND_FM, BAND_AM, INTERNET,
    PLAYBACK, AUDIO, LOGOS, RADIODNS,
    ASA, ASA_CODES,
    APPEARANCE,
    SYSTEM, ABOUT, LICENSE,
}

/** Where the back key goes from each page. */
private fun Page.parent(): Page? = when (this) {
    Page.ROOT -> null
    Page.BAND_DAB, Page.BAND_FM, Page.BAND_AM, Page.INTERNET -> Page.BANDS
    Page.ASA_CODES -> Page.ASA
    Page.ABOUT, Page.LICENSE -> Page.SYSTEM
    else -> Page.ROOT
}

/** Manage internet-radio stations (search radio-browser, add, remove). Same panel as the main
 *  screen's "Streams" view — this is just the settings entry point to it. */
@Composable
private fun InternetPage(
    stations: List<Station>,
    results: List<Station>,
    searching: Boolean,
    onSearch: (String) -> Unit,
    onAdd: (Station) -> Unit,
    onRemove: (String) -> Unit,
    onAddManual: (String, String) -> Unit,
) {
    InternetPanel(
        stations = stations,
        results = results,
        searching = searching,
        onSearch = onSearch,
        onAdd = onAdd,
        onRemove = onRemove,
        onAddManual = onAddManual,
    )
}

/* ------------------------------------------------------------------- pages */

@Composable
private fun RootPage(
    settings: Settings,
    dabPresent: Boolean,
    logoCount: Int,
    internetCount: Int,
    onOpen: (Page) -> Unit,
) {
    // Language sits at the very top of the ROOT page on purpose: if the app is in a language the
    // user cannot read, this is the one control they must be able to find without navigating a
    // menu tree. Applied by recreating the Activity so LocaleHelper re-wraps the context; driven
    // entirely off LocaleHelper.LANGUAGES, so adding a language needs no change here.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val langs = com.px6.radio.i18n.LocaleHelper.LANGUAGES
    val curTag = com.px6.radio.i18n.LocaleHelper.language(ctx)
    OptionRow(
        label = stringResource(R.string.settings_language),
        value = com.px6.radio.i18n.LocaleHelper.forTag(curTag).label,
        options = langs.map { it.label },
        selectedIndex = langs.indexOfFirst { it.tag == curTag }.coerceAtLeast(0),
        onSelect = { i ->
            com.px6.radio.i18n.LocaleHelper.setLanguage(ctx, langs[i].tag)
            (ctx as? android.app.Activity)?.recreate()
        },
    )

    NavRow(stringResource(R.string.settings_bands), null) { onOpen(Page.BANDS) }
    NavRow(stringResource(R.string.settings_playback), null) { onOpen(Page.PLAYBACK) }
    NavRow(stringResource(R.string.settings_audio), null) { onOpen(Page.AUDIO) }
    NavRow(
        stringResource(R.string.settings_logos),
        if (logoCount == 0) stringResource(R.string.settings_none) else "$logoCount",
    ) { onOpen(Page.LOGOS) }
    NavRow(
        "RadioDNS",
        if (settings.radioDnsEnabled) stringResource(R.string.settings_on) else stringResource(R.string.settings_off),
    ) { onOpen(Page.RADIODNS) }
    // ASA is a DAB+ feature — only offered when a DAB tuner is present.
    if (dabPresent) {
        NavRow(
            stringResource(R.string.settings_asa),
            if (settings.asaEnabled) stringResource(R.string.settings_on) else stringResource(R.string.settings_off),
        ) { onOpen(Page.ASA) }
    }
    NavRow(stringResource(R.string.settings_appearance), null) { onOpen(Page.APPEARANCE) }
    NavRow(stringResource(R.string.settings_system), null) { onOpen(Page.SYSTEM) }
}

/**
 * One entry per band. The band used to be a picker at the top of the root page that silently
 * changed what the rows below it meant — the "rescan" row in particular. A band is a place you go
 * to, not a mode the page is in.
 */
@Composable
private fun BandsPage(
    dabPresent: Boolean,
    fmAvailable: Boolean,
    internetCount: Int,
    onOpen: (Page) -> Unit,
) {
    if (dabPresent) NavRow("DAB+", null) { onOpen(Page.BAND_DAB) }
    if (fmAvailable) {
        NavRow("FM", null) { onOpen(Page.BAND_FM) }
        NavRow("AM", null) { onOpen(Page.BAND_AM) }
    }
    NavRow(
        stringResource(R.string.settings_internet),
        if (internetCount == 0) stringResource(R.string.settings_none)
        else stringResource(R.string.settings_station_count, internetCount),
    ) { onOpen(Page.INTERNET) }
}

/** DAB+: the station list builds itself, so a rescan is the only thing to offer. */
@Composable
private fun BandDabPage(
    settings: Settings,
    dabScanning: Boolean,
    scanProgress: Int,
    fmAvailable: Boolean,
    onScanDab: () -> Unit,
    onChange: ((Settings) -> Settings) -> Unit,
) {
    ActionRow(
        label = stringResource(R.string.settings_rescan_list),
        hint = if (dabScanning) stringResource(R.string.settings_dab_scanning_hint)
            else stringResource(R.string.settings_dab_scan_hint),
        button = if (dabScanning) "$scanProgress %" else stringResource(R.string.settings_scan_button),
        enabled = !dabScanning,
        progress = if (dabScanning) scanProgress / 100f else null,
        onClick = onScanDab,
    )

    // Service following lives here rather than under "playback": all three tiers start at DAB
    // (DAB->DAB, DAB->FM, DAB->internet), so without a tuner they are settings for nothing. Kept
    // with the band, they disappear together with it on a unit that has no stick.
    CheckRow(
        stringResource(R.string.settings_dab_dab_following),
        stringResource(R.string.settings_dab_dab_following_hint),
        settings.dabDabFollowing,
    ) { v -> onChange { it.copy(dabDabFollowing = v) } }

    if (fmAvailable) {
        CheckRow(
            stringResource(R.string.settings_dab_fm_following),
            stringResource(R.string.settings_dab_fm_following_hint),
            settings.serviceFollowing,
        ) { v -> onChange { it.copy(serviceFollowing = v) } }
        OptionRow(
            label = stringResource(R.string.settings_handover_threshold),
            hint = stringResource(R.string.settings_handover_threshold_hint),
            value = "${settings.handoverThreshold}",
            options = listOf("1", "2", "3", "4"),
            selectedIndex = (settings.handoverThreshold - 1).coerceIn(0, 3),
            enabled = settings.serviceFollowing,
            onSelect = { i -> onChange { it.copy(handoverThreshold = i + 1) } },
        )
        CheckRow(
            stringResource(R.string.settings_prefer_dab),
            stringResource(R.string.settings_prefer_dab_hint),
            settings.preferDab,
        ) { v -> onChange { it.copy(preferDab = v) } }
        // Which fallback comes first once DAB itself fails. Only worth offering when both tiers can
        // actually be reached; with one of them switched off the order describes nothing.
        if (settings.radioDnsEnabled && settings.ipFallbackEnabled) {
            val orders = listOf(
                com.px6.radio.model.FallbackOrder.FM_FIRST,
                com.px6.radio.model.FallbackOrder.IP_FIRST,
            )
            val labels = listOf(
                stringResource(R.string.settings_fallback_fm_first),
                stringResource(R.string.settings_fallback_ip_first),
            )
            OptionRow(
                label = stringResource(R.string.settings_fallback_order),
                hint = stringResource(R.string.settings_fallback_order_hint),
                value = labels[orders.indexOf(settings.fallbackOrder).coerceAtLeast(0)],
                options = labels,
                selectedIndex = orders.indexOf(settings.fallbackOrder).coerceAtLeast(0),
                onSelect = { i -> onChange { it.copy(fallbackOrder = orders[i]) } },
            )
        }
    } else {
        InfoRow(
            stringResource(R.string.settings_dab_only),
            stringResource(R.string.settings_dab_only_hint),
        )
    }

    // The third following tier. Needs RadioDNS for the simulcast address, hence the gate.
    CheckRow(
        stringResource(R.string.settings_ip_fallback),
        stringResource(R.string.settings_ip_fallback_hint),
        settings.ipFallbackEnabled && settings.radioDnsEnabled,
    ) { v -> onChange { it.copy(ipFallbackEnabled = v) } }
    if (settings.ipFallbackEnabled && settings.radioDnsEnabled) {
        CheckRow(
            stringResource(R.string.settings_ip_fallback_wifi),
            stringResource(R.string.settings_ip_fallback_wifi_hint),
            settings.ipFallbackWifiOnly,
        ) { v -> onChange { it.copy(ipFallbackWifiOnly = v) } }
    }

}

/** FM: rescan plus the frequency raster, which is a property of the band, not of the app. */
@Composable
private fun BandFmPage(
    settings: Settings,
    fmSeeking: Boolean,
    onScanFm: () -> Unit,
    onChange: ((Settings) -> Settings) -> Unit,
) {
    ActionRow(
        label = stringResource(R.string.settings_rescan_list),
        hint = if (fmSeeking) stringResource(R.string.settings_fm_scanning_hint)
            else stringResource(R.string.settings_fm_scan_hint),
        button = if (fmSeeking) stringResource(R.string.settings_scanning_button)
            else stringResource(R.string.settings_scan_button),
        enabled = !fmSeeking,
        onClick = onScanFm,
    )
    RegionRow(settings, onChange)
}

/**
 * The tuning region. One setting, shown on both analog band pages, because it genuinely governs
 * both: FM gets its band and raster from it (Europe 87,5-108 in 50 kHz steps, OIRT 65-74, US
 * 87,5-107,9 in 200), and AM its medium-wave grid (9 kHz outside the Americas, 10 kHz inside).
 * Offering it only under FM — as it was — left the AM page silent about where its own raster came
 * from, and gave no way to change it from there.
 *
 * NOTE: these are PERSISTED values matched by string (TuningProfile.forBand) — do not localise.
 */
@Composable
private fun RegionRow(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    val regions = listOf("Europa", "OIRT", "US")
    OptionRow(
        label = stringResource(R.string.settings_region),
        hint = stringResource(R.string.settings_region_hint),
        value = settings.fmRegion,
        options = regions,
        selectedIndex = regions.indexOf(settings.fmRegion).coerceAtLeast(0),
        onSelect = { i -> onChange { it.copy(fmRegion = regions[i]) } },
    )
}

/** AM: same sweep as FM — the head unit has no band switch, the band follows from the frequency. */
@Composable
private fun BandAmPage(
    settings: Settings,
    fmSeeking: Boolean,
    onScanFm: () -> Unit,
    onChange: ((Settings) -> Settings) -> Unit,
) {
    ActionRow(
        label = stringResource(R.string.settings_rescan_list),
        hint = if (fmSeeking) stringResource(R.string.settings_am_scanning_hint)
            else stringResource(R.string.settings_am_scan_hint),
        button = if (fmSeeking) stringResource(R.string.settings_scanning_button)
            else stringResource(R.string.settings_scan_button),
        enabled = !fmSeeking,
        onClick = onScanFm,
    )
    RegionRow(settings, onChange)
    InfoRow(
        stringResource(R.string.settings_medium_wave),
        stringResource(R.string.settings_medium_wave_text),
    )
}

/**
 * How playing behaves: what the arrow keys step through, the preset buttons, and service following
 * — all three of its tiers together (DAB->DAB, DAB->FM, DAB->Internet). Those used to be split
 * between "Advanced" and the RadioDNS page, which made a single feature look like three.
 */
@Composable
private fun PlaybackPage(
    settings: Settings,
    presets: List<Pair<Int, String>>,
    onClearPreset: (Int?) -> Unit,
    onChange: ((Settings) -> Settings) -> Unit,
) {
    val stepModes = listOf(StepMode.STORED, StepMode.RECEIVABLE)
    OptionRow(
        label = stringResource(R.string.settings_arrow_keys),
        hint = stringResource(R.string.settings_arrow_keys_hint),
        value = if (settings.stepMode == StepMode.STORED) stringResource(R.string.settings_step_presets)
            else stringResource(R.string.settings_step_stations),
        options = listOf(
            stringResource(R.string.settings_step_presets),
            stringResource(R.string.settings_step_stations),
        ),
        selectedIndex = stepModes.indexOf(settings.stepMode).coerceAtLeast(0),
        onSelect = { i -> onChange { it.copy(stepMode = stepModes[i]) } },
    )

    // Single buttons as well as all of them, as in the original.
    var clearPicker by remember { mutableStateOf(false) }
    ActionRow(
        label = stringResource(R.string.settings_clear_presets),
        hint = if (presets.isEmpty()) stringResource(R.string.settings_no_presets)
            else stringResource(R.string.settings_presets_used, presets.size),
        button = stringResource(R.string.settings_choose_button),
        enabled = presets.isNotEmpty(),
        onClick = { clearPicker = true },
    )
    if (clearPicker) {
        val entries = presets.map { (index, name) -> "$index · $name" } +
            stringResource(R.string.settings_clear_all)
        OptionWindow(
            title = stringResource(R.string.settings_clear_presets),
            options = entries,
            selectedIndex = -1,   // this window acts, it does not choose a setting
            onPick = { i ->
                if (i == entries.lastIndex) onClearPreset(null) else onClearPreset(presets[i].first)
                clearPicker = false
            },
            onDismiss = { clearPicker = false },
        )
    }
}

/**
 * The box and the app itself: hardware keys, the background player, recovery, and the about and
 * licence pages. Everything here is about the unit rather than about listening.
 */
@Composable
private fun SystemPage(
    settings: Settings,
    onOpen: (Page) -> Unit,
    onChange: ((Settings) -> Settings) -> Unit,
    onResetBackends: () -> Unit,
    onDeleteDiagnostics: () -> Unit,
) {
    CheckRow(
        stringResource(R.string.settings_steering_wheel),
        stringResource(R.string.settings_steering_wheel_hint),
        settings.steeringWheelKeys,
    ) { v -> onChange { it.copy(steeringWheelKeys = v) } }

    CheckRow(
        stringResource(R.string.settings_miniplayer),
        stringResource(R.string.settings_miniplayer_hint),
        settings.miniPlayerOverlay,
    ) { v -> onChange { it.copy(miniPlayerOverlay = v) } }
    if (settings.miniPlayerOverlay && android.os.Build.VERSION.SDK_INT >= 23) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        if (!android.provider.Settings.canDrawOverlays(ctx)) {
            ActionRow(
                label = stringResource(R.string.settings_grant_permission),
                hint = stringResource(R.string.settings_overlay_permission_hint),
                button = stringResource(R.string.settings_allow_button),
                enabled = true,
                onClick = {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${ctx.packageName}"),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        }
    }

    // Diagnostics files: off by default, because they hold GPS-derived location codes and the
    // listening history — on a USB stick, if one is plugged in. Worth switching on to chase a
    // fault; not something a store user should get without asking.
    CheckRow(
        stringResource(R.string.settings_diagnostics),
        stringResource(R.string.settings_diagnostics_hint),
        settings.diagnostics,
    ) { v -> onChange { it.copy(diagnostics = v) } }
    ActionRow(
        label = stringResource(R.string.settings_diagnostics_delete),
        hint = stringResource(R.string.settings_diagnostics_delete_hint),
        button = stringResource(R.string.settings_delete_button),
        enabled = true,
        onClick = onDeleteDiagnostics,
    )

    // Lifts the safe-mode disable a native crash triggered (e.g. DAB+), for a fresh try on restart.
    ActionRow(
        stringResource(R.string.settings_reset_backends),
        stringResource(R.string.settings_reset_backends_hint),
        button = stringResource(R.string.settings_reset_button),
        enabled = true,
    ) { onResetBackends() }

    NavRow(stringResource(R.string.settings_about), null) { onOpen(Page.ABOUT) }
    NavRow(stringResource(R.string.settings_license), null) { onOpen(Page.LICENSE) }
}

/**
 * DAB Emergency Warning System (ASA — Automatic Safety Alert, ETSI TS 104 089). Master switch, the
 * receiver's DAB location code (for geo-matching), and the test-alert toggle. Alert playback itself
 * is built in a later milestone; today this configures reception and the status-bar indicator.
 */
@Composable
private fun AsaPage(
    settings: Settings,
    onOpen: (Page) -> Unit,
    onChange: ((Settings) -> Settings) -> Unit,
) {
    CheckRow(
        stringResource(R.string.settings_asa_receive),
        stringResource(R.string.settings_asa_receive_hint),
        settings.asaEnabled,
    ) { v -> onChange { it.copy(asaEnabled = v) } }
    // Current position first — for a radio that drives around, the moving area is the primary
    // interest — then any number of fixed codes on top (home, family). They all apply together.
    // Following the vehicle needs a runtime location grant. Both permissions are in the manifest,
    // but on API 23+ that alone grants nothing: without asking, GpsLocationCode.start() logged "no
    // permission" and returned, so the switch sat there looking enabled while doing nothing at all.
    // Ask the moment it is switched on, and if the user says no, say so instead of pretending.
    val gpsCtx = androidx.compose.ui.platform.LocalContext.current
    fun hasLocationPermission() =
        androidx.core.content.ContextCompat.checkSelfPermission(
            gpsCtx, android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                gpsCtx, android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    var locationGranted by remember { mutableStateOf(hasLocationPermission()) }
    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> locationGranted = result.values.any { it } }

    CheckRow(
        stringResource(R.string.settings_asa_follow_gps),
        stringResource(R.string.settings_asa_follow_gps_hint),
        settings.asaFollowGps,
    ) { v ->
        onChange { it.copy(asaFollowGps = v) }
        if (v && !locationGranted) {
            askLocation.launch(arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
        }
    }
    if (settings.asaFollowGps && !locationGranted) {
        ActionRow(
            label = stringResource(R.string.settings_grant_permission),
            hint = stringResource(R.string.settings_location_permission_hint),
            button = stringResource(R.string.settings_allow_button),
            enabled = true,
            onClick = {
                askLocation.launch(arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ))
            },
        )
    }
    // The codes live one level deeper: with more than one entered, listing them here made the page
    // long and repeated the same row label over and over.
    NavRow(
        stringResource(R.string.settings_dab_location_code),
        if (settings.asaLocationCodes.isEmpty()) stringResource(R.string.settings_none)
        else settings.asaLocationCodes.size.toString(),
    ) { onOpen(Page.ASA_CODES) }
    CheckRow(
        stringResource(R.string.settings_asa_test_alerts),
        stringResource(R.string.settings_asa_test_alerts_hint),
        settings.asaTestAlerts,
    ) { v -> onChange { it.copy(asaTestAlerts = v) } }
    CheckRow(
        stringResource(R.string.settings_asa_attention_tone),
        stringResource(R.string.settings_asa_attention_tone_hint),
        settings.asaAttentionTone,
    ) { v -> onChange { it.copy(asaAttentionTone = v) } }
    InfoRow(
        stringResource(R.string.settings_note),
        stringResource(R.string.settings_asa_note_text),
    )
}

/**
 * The fixed DAB location codes. A list rather than a single field: a car radio has more than one
 * legitimate area of interest — where it is driving (covered by the GPS option) and where it came
 * from (home, family). Every entry is matched, so adding one can only widen coverage.
 */
@Composable
private fun LocationCodesSection(
    codes: List<String>,
    enabled: Boolean,
    placeResults: List<com.px6.radio.ews.Place>,
    placeSearching: Boolean,
    onSearchPlace: (String) -> Unit,
    onChange: (List<String>) -> Unit,
) {
    // Index being edited, -1 = adding a new one, null = dialog closed.
    var editing by remember { mutableStateOf<Int?>(null) }
    var searching by remember { mutableStateOf(false) }
    codes.forEachIndexed { i, code ->
        val valid = com.px6.radio.ews.EwsMatcher.parseReceiverCode(code) != null
        val grouped = code.filter { it in '1'..'8' }.chunked(4).joinToString("-")
        SettingRow(onClick = { if (enabled) editing = i }, enabled = enabled) {
            LabelBlock(
                stringResource(R.string.settings_dab_location_code),
                if (valid) "✓ $grouped" else stringResource(R.string.settings_location_invalid, grouped),
                enabled = enabled,
                warning = !valid,
            )
            Text(
                stringResource(R.string.settings_asa_remove),
                color = if (enabled) appColors.dangerText else appColors.muted2,
                fontSize = 15.sp,
                modifier = Modifier.clickable(enabled = enabled) {
                    onChange(codes.filterIndexed { j, _ -> j != i })
                },
            )
            Spacer(Modifier.width(14.dp))
            Text("›", color = appColors.muted, fontSize = 24.sp)
        }
    }
    SettingRow(onClick = { if (enabled) editing = -1 }, enabled = enabled) {
        LabelBlock(
            stringResource(R.string.settings_asa_add_code),
            if (codes.isEmpty()) stringResource(R.string.settings_location_not_set) else null,
            enabled = enabled,
        )
        Text(
            stringResource(R.string.settings_enter),
            color = if (enabled) appColors.accent else appColors.muted2,
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(10.dp))
        Text("›", color = appColors.muted, fontSize = 24.sp)
    }
    if (searching) {
        PlaceSearchDialog(
            results = placeResults,
            searching = placeSearching,
            onSearch = onSearchPlace,
            onPick = { place ->
                com.px6.radio.ews.PlaceSearch.presentationCodeFor(place)?.let { code ->
                    if (code !in codes) onChange(codes + code)
                }
                searching = false
            },
            onDismiss = { searching = false },
        )
    }
    editing?.let { idx ->
        LocationCodeDialog(
            initial = codes.getOrNull(idx) ?: "",
            // Offered inside the add dialog rather than as a row of its own: "add a code" is one
            // intention, and typing twelve digits or naming a place are just two ways to do it.
            onSearchPlace = { editing = null; searching = true },
            onSave = { entered ->
                val cleaned = entered.filter { it in '1'..'8' }
                onChange(
                    when {
                        cleaned.isEmpty() && idx >= 0 -> codes.filterIndexed { j, _ -> j != idx }
                        cleaned.isEmpty() -> codes
                        idx >= 0 -> codes.toMutableList().also { it[idx] = entered }
                        entered in codes -> codes                     // no duplicates
                        else -> codes + entered
                    }
                )
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/**
 * Search a place and turn it into a location code. Shows the derived code next to each hit, so the
 * user can see what they are about to store rather than trusting a black box.
 */
@Composable
private fun PlaceSearchDialog(
    results: List<com.px6.radio.ews.Place>,
    searching: Boolean,
    onSearch: (String) -> Unit,
    onPick: (com.px6.radio.ews.Place) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DIALOG_FULL_WIDTH) {
        PlaceSearchCard(results, searching, onSearch, onPick, onDismiss)
    }
}

/** The dialog body without its window — split out so the screenshot harness can render it (Roborazzi
 *  captures the composition root, and a Dialog lives in a window of its own). */
@Composable
internal fun PlaceSearchCard(
    results: List<com.px6.radio.ews.Place>,
    searching: Boolean,
    onSearch: (String) -> Unit,
    onPick: (com.px6.radio.ews.Place) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    run {
        Column(
            dialogSize(maxWidth = 820.dp).clip(RoundedCornerShape(16.dp))
                .background(appColors.panel).border(1.dp, appColors.line, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.settings_asa_search_place),
                color = appColors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).heightIn(min = touchMin).clip(RoundedCornerShape(10.dp))
                        .background(appColors.softBg)
                        .border(1.dp, appColors.softBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = appColors.text, fontSize = 17.sp),
                        cursorBrush = SolidColor(appColors.accent),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text(
                                    stringResource(R.string.settings_asa_search_placeholder),
                                    color = appColors.muted2, fontSize = 17.sp,
                                )
                            }
                            inner()
                        },
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.heightIn(min = touchMin).clip(RoundedCornerShape(10.dp))
                        .background(appColors.softBg)
                        .border(1.dp, appColors.softBorder, RoundedCornerShape(10.dp))
                        .clickable { onSearch(query) }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.action_search),
                        color = appColors.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                when {
                    searching -> Text(
                        stringResource(R.string.settings_asa_searching),
                        color = appColors.muted, fontSize = 15.sp,
                    )
                    results.isEmpty() && query.isNotBlank() -> Text(
                        stringResource(R.string.settings_asa_no_places),
                        color = appColors.muted, fontSize = 15.sp,
                    )
                    else -> results.forEach { place ->
                        val code = com.px6.radio.ews.PlaceSearch.presentationCodeFor(place)
                        val grouped = code?.chunked(4)?.joinToString("-")
                        SettingRow(onClick = { if (code != null) onPick(place) }, enabled = code != null) {
                            LabelBlock(place.label, grouped, enabled = code != null)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(appColors.softBg)
                        .border(1.dp, appColors.softBorder, RoundedCornerShape(10.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 22.dp, vertical = 11.dp),
                ) {
                    Text(stringResource(R.string.action_close), color = appColors.text, fontSize = 16.sp)
                }
            }
        }
    }
}

/**
 * Keypad entry for the DAB location code. The presentation format (annex A) uses only the symbols
 * 1–8, so the keypad offers exactly those — no invalid character can be entered. Save is enabled only
 * for a complete, checksum-valid code (or an empty one, which clears it), so nothing invalid is ever
 * stored.
 */
@Composable
private fun LocationCodeDialog(
    initial: String,
    onSearchPlace: (() -> Unit)? = null,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(initial.filter { it in '1'..'8' }.take(12)) }
    val complete = input.length == 12
    val valid = com.px6.radio.ews.EwsMatcher.parseReceiverCode(input) != null
    val shown = buildString {
        for (i in 0 until 12) {
            append(if (i < input.length) input[i] else '–')
            if (i == 3 || i == 7) append('-')
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DIALOG_FULL_WIDTH) {
        Column(
            dialogSize(maxWidth = 760.dp).clip(RoundedCornerShape(16.dp))
                .background(appColors.panel).border(1.dp, appColors.line, RoundedCornerShape(16.dp))
                // Scrolls if the keypad does not fit: on the car screen the button row at the bottom
                // was simply cut off and could not be reached.
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.settings_dab_location_code), color = appColors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                shown,
                color = if (complete && !valid) appColors.dangerText else appColors.accent,
                fontSize = 30.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
            )
            Text(
                when {
                    input.isEmpty() -> stringResource(R.string.settings_location_empty_hint)
                    !complete -> stringResource(R.string.settings_location_remaining, 12 - input.length)
                    valid -> stringResource(R.string.settings_location_code_valid)
                    else -> stringResource(R.string.settings_location_checksum_invalid)
                },
                color = if (complete && !valid) appColors.dangerText else appColors.muted, fontSize = 14.sp,
            )
            // All eight symbols in ONE row. The dialog is wide but the car screen is short: two rows
            // of four made it taller than the display and the button row at the bottom was cut off
            // and unreachable. Horizontally there is room to spare.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                "12345678".forEach { d ->
                    KeypadKey(d.toString(), enabled = input.length < 12) { input += d }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeypadKey("⌫", enabled = input.isNotEmpty()) { input = input.dropLast(1) }
                KeypadKey(stringResource(R.string.settings_clear_button), enabled = input.isNotEmpty(), wide = true) { input = "" }
            }
            HairLine()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialogButton(stringResource(R.string.settings_cancel), accent = false, enabled = true, onClick = onDismiss)
                // The other way to fill this in — one line in the existing button row rather than a
                // block of its own, which crowded a dialog that is mostly keypad.
                onSearchPlace?.let { search ->
                    Text(
                        stringResource(R.string.settings_asa_search_place),
                        color = appColors.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { search() }.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
                DialogButton(stringResource(R.string.settings_save), accent = true, enabled = valid || input.isEmpty()) { onSave(input) }
            }
        }
    }
}

@Composable
private fun KeypadKey(label: String, enabled: Boolean, wide: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .then(if (wide) Modifier.width(148.dp) else Modifier.size(70.dp))
            .height(if (wide) 70.dp else 70.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) appColors.softBg else appColors.panel)
            .border(1.dp, appColors.softBorder, RoundedCornerShape(12.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) appColors.text else appColors.muted2,
            fontSize = if (label.length > 1) 16.sp else 24.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DialogButton(label: String, accent: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (accent && enabled) appColors.accent else appColors.softBg)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 28.dp, vertical = 14.dp),
    ) {
        Text(
            label,
            color = when { !enabled -> appColors.muted2; accent -> appColors.onAccent; else -> appColors.text },
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Audio-related settings: internet loudness matching, meter. */
@Composable
private fun AudioPage(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    CheckRow(
        stringResource(R.string.settings_normalize_loudness),
        stringResource(R.string.settings_normalize_loudness_hint),
        settings.normalizeStreamLoudness,
    ) { v -> onChange { it.copy(normalizeStreamLoudness = v) } }
    CheckRow(
        stringResource(R.string.settings_ui_sounds),
        stringResource(R.string.settings_ui_sounds_hint),
        settings.uiSounds,
    ) { v -> onChange { it.copy(uiSounds = v) } }
}


@Composable
private fun AppearancePage(
    settings: Settings,
    skins: List<Skin>,
    frontends: List<RadioFrontend>,
    headlightOn: Boolean?,
    onChange: ((Settings) -> Settings) -> Unit,
) {
    OptionRow(
        label = stringResource(R.string.settings_orientation),
        hint = stringResource(R.string.settings_orientation_hint),
        value = stringResource(if (settings.autoRotate) R.string.settings_orientation_auto else R.string.settings_orientation_landscape),
        options = listOf(stringResource(R.string.settings_orientation_landscape), stringResource(R.string.settings_orientation_auto)),
        selectedIndex = if (settings.autoRotate) 1 else 0,
        onSelect = { i -> onChange { it.copy(autoRotate = i == 1) } },
    )
    CheckRow(
        stringResource(R.string.settings_show_signal),
        stringResource(R.string.settings_show_signal_hint),
        settings.showSignalStrength,
    ) { v -> onChange { it.copy(showSignalStrength = v) } }
    OptionRow(
        label = stringResource(R.string.settings_frontend),
        hint = frontends.firstOrNull { it.id == settings.frontendId }?.descriptionRes?.let { stringResource(it) },
        value = frontends.firstOrNull { it.id == settings.frontendId }?.nameRes?.let { stringResource(it) } ?: "—",
        options = androidx.compose.ui.platform.LocalContext.current.let { c -> frontends.map { c.getString(it.nameRes) } },
        selectedIndex = frontends.indexOfFirst { it.id == settings.frontendId }.coerceAtLeast(0),
        onSelect = { i ->
            frontends.getOrNull(i)?.let { f -> onChange { it.copy(frontendId = f.id) } }
        },
    )

    val modes = listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.TIME, ThemeMode.HEADLIGHT)
    val modeLabels = listOf(
        stringResource(R.string.settings_theme_dark),
        stringResource(R.string.settings_theme_light),
        stringResource(R.string.settings_theme_time),
        stringResource(R.string.settings_theme_headlight),
    )
    OptionRow(
        label = stringResource(R.string.settings_theme_mode),
        hint = when (settings.themeMode) {
            ThemeMode.TIME -> stringResource(R.string.settings_theme_time_hint)
            ThemeMode.HEADLIGHT -> stringResource(R.string.settings_theme_headlight_hint) +
                (if (headlightOn == null) stringResource(R.string.settings_theme_headlight_unknown) else "")
            ThemeMode.DARK -> stringResource(R.string.settings_theme_always_dark)
            ThemeMode.LIGHT -> stringResource(R.string.settings_theme_always_light)
        },
        value = modeLabels[modes.indexOf(settings.themeMode).coerceAtLeast(0)],
        options = modeLabels,
        selectedIndex = modes.indexOf(settings.themeMode).coerceAtLeast(0),
        onSelect = { i -> onChange { it.copy(themeMode = modes[i]) } },
    )

    val darkSkins = skins.filter { it.dark }
    val lightSkins = skins.filter { !it.dark }
    OptionRow(
        label = stringResource(R.string.settings_skin_dark),
        value = darkSkins.firstOrNull { it.id == settings.skinDarkId }?.name ?: "—",
        options = darkSkins.map { it.name },
        selectedIndex = darkSkins.indexOfFirst { it.id == settings.skinDarkId }.coerceAtLeast(0),
        onSelect = { i ->
            darkSkins.getOrNull(i)?.let { sk -> onChange { it.copy(skinDarkId = sk.id) } }
        },
    )
    OptionRow(
        label = stringResource(R.string.settings_skin_light),
        value = lightSkins.firstOrNull { it.id == settings.skinLightId }?.name ?: "—",
        options = lightSkins.map { it.name },
        selectedIndex = lightSkins.indexOfFirst { it.id == settings.skinLightId }.coerceAtLeast(0),
        onSelect = { i ->
            lightSkins.getOrNull(i)?.let { sk -> onChange { it.copy(skinLightId = sk.id) } }
        },
    )

    val accents = AccentColor.entries
    OptionRow(
        label = stringResource(R.string.settings_accent_color),
        hint = stringResource(R.string.settings_accent_color_hint),
        value = AccentColor.byId(settings.accentId).label,
        options = accents.map { it.label },
        selectedIndex = accents.indexOfFirst { it.id == settings.accentId }.coerceAtLeast(0),
        onSelect = { i -> onChange { it.copy(accentId = accents[i].id) } },
    )
}

@Composable
private fun LogoPage(
    settings: Settings,
    count: Int,
    running: Boolean,
    status: String?,
    onDownload: () -> Unit,
    onClear: () -> Unit,
    onChange: ((Settings) -> Settings) -> Unit,
) {
    ActionRow(
        label = stringResource(R.string.settings_load_logos),
        hint = if (count == 0) stringResource(R.string.settings_no_logos) else stringResource(R.string.settings_logos_stored, count),
        button = if (running) stringResource(R.string.settings_loading_button) else stringResource(R.string.settings_load_button),
        enabled = !running,
        onClick = onDownload,
    )
    // The RadioDNS master switch lives on its own page but silently gates the best logo source:
    // downloadLogos() skips the RadioDNS pass entirely when it is off, and "fetch automatically"
    // below is ANDed with it. Without this line the button just quietly finds far fewer logos and
    // the reason is one page away.
    if (!settings.radioDnsEnabled) {
        InfoRow(
            stringResource(R.string.settings_logos_radiodns_off),
            stringResource(R.string.settings_logos_radiodns_off_text),
        )
    }
    ActionRow(
        label = stringResource(R.string.settings_delete_logos),
        hint = stringResource(R.string.settings_delete_logos_hint),
        button = stringResource(R.string.settings_delete_button),
        enabled = !running && count > 0,
        onClick = onClear,
    )
    CheckRow(
        stringResource(R.string.settings_auto_assign),
        stringResource(R.string.settings_auto_assign_hint),
        settings.autoAssignLogos,
    ) { v -> onChange { it.copy(autoAssignLogos = v) } }
    // These two used to live on the RadioDNS page. They are about where a logo comes from, which
    // is a logo question — RadioDNS is merely the transport for one of the two sources.
    CheckRow(
        stringResource(R.string.settings_auto_fetch_logos),
        stringResource(R.string.settings_auto_fetch_logos_hint),
        settings.autoFetchLogos && settings.radioDnsEnabled,
    ) { v -> onChange { it.copy(autoFetchLogos = v) } }
    CheckRow(
        stringResource(R.string.settings_media_broadcast_logos),
        stringResource(R.string.settings_media_broadcast_logos_hint),
        settings.mediaBroadcastLogos,
    ) { v -> onChange { it.copy(mediaBroadcastLogos = v) } }
    status?.let { InfoRow(stringResource(R.string.settings_last_operation), it) }
    InfoRow(
        stringResource(R.string.settings_origin),
        stringResource(R.string.settings_origin_text),
    )
}

@Composable
private fun RadioDnsPage(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    onRefresh: () -> Unit,
) {
    CheckRow(
        stringResource(R.string.settings_radiodns_use),
        stringResource(R.string.settings_radiodns_use_hint),
        settings.radioDnsEnabled,
    ) { v -> onChange { it.copy(radioDnsEnabled = v) } }
    CheckRow(
        stringResource(R.string.settings_radiovis),
        stringResource(R.string.settings_radiovis_hint),
        settings.radioVisEnabled && settings.radioDnsEnabled,
    ) { v -> onChange { it.copy(radioVisEnabled = v) } }
    CheckRow(
        stringResource(R.string.settings_slideshow),
        stringResource(R.string.settings_slideshow_hint),
        settings.radioVisSlideshow && settings.radioVisEnabled && settings.radioDnsEnabled,
    ) { v -> onChange { it.copy(radioVisSlideshow = v) } }
    ActionRow(
        stringResource(R.string.settings_radiodns_refresh),
        stringResource(R.string.settings_radiodns_refresh_hint),
        button = stringResource(R.string.settings_refresh_button),
        enabled = settings.radioDnsEnabled,
    ) { onRefresh() }
    InfoRow(
        stringResource(R.string.settings_data_usage),
        stringResource(R.string.settings_data_usage_text),
    )
}

/** Open-source + attribution notices — LGPL libraries and the RadioDNS standard. */
@Composable
private fun LicensePage() {
    InfoRow("Klarwelle", stringResource(R.string.settings_license_app))
    InfoRow(
        "omri-usb",
        stringResource(R.string.settings_license_omri),
    )
    InfoRow(
        "radiodns (Resolver)",
        stringResource(R.string.settings_license_radiodns_resolver),
    )
    InfoRow(
        "RadioDNS",
        stringResource(R.string.settings_license_radiodns),
    )
    InfoRow(
        stringResource(R.string.settings_license_lgpl_title),
        stringResource(R.string.settings_license_lgpl_text),
    )
}


@Composable
private fun AboutPage(fmAvailable: Boolean, dabPresent: Boolean) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun open(url: String) = runCatching {
        ctx.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    InfoRow("Klarwelle", stringResource(R.string.settings_about_text))
    InfoRow(stringResource(R.string.settings_about_version), com.px6.radio.BuildConfig.VERSION_NAME)
    InfoRow(stringResource(R.string.settings_about_developer), "Sebastian Müller")
    InfoRow(stringResource(R.string.settings_about_contact), "sebastian@masesoftware.de")
    // Play requires the policy to be reachable from inside the app as well as from the listing.
    ActionRow(
        label = stringResource(R.string.settings_about_privacy),
        hint = stringResource(R.string.settings_about_privacy_hint),
        button = stringResource(R.string.settings_about_open),
        enabled = true,
        onClick = { open(PRIVACY_URL) },
    )
    ActionRow(
        label = stringResource(R.string.settings_about_source),
        hint = "GPL-3.0",
        button = stringResource(R.string.settings_about_open),
        enabled = true,
        onClick = { open(SOURCE_URL) },
    )
    InfoRow(stringResource(R.string.settings_dab_receiver), if (dabPresent) stringResource(R.string.settings_detected) else stringResource(R.string.settings_not_detected))
    InfoRow(
        "FM-Tuner",
        if (fmAvailable) stringResource(R.string.settings_reachable) else stringResource(R.string.settings_not_reachable),
    )
}

/* -------------------------------------------------------------------- rows */

@Composable
private fun TitleBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = touchMin + 8.dp)
            .background(appColors.panel).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(touchMin).clip(RoundedCornerShape(12.dp)).background(appColors.softBg)
                .border(1.dp, appColors.softBorder, RoundedCornerShape(12.dp))
                .clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) { Text("‹", color = appColors.text, fontSize = 30.sp) }
        Spacer(Modifier.width(16.dp))
        Text(title, color = appColors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HairLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(appColors.line))
}

/** Base row: every setting is the same height and starts in the same place. */
@Composable
private fun SettingRow(
    onClick: (() -> Unit)?,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = touchMin)
            .then(if (onClick != null && enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
    HairLine()
}

@Composable
private fun RowScope.LabelBlock(
    label: String,
    hint: String?,
    enabled: Boolean,
    warning: Boolean = false,
) {
    Column(Modifier.weight(1f)) {
        Text(
            label, color = if (enabled) appColors.text else appColors.muted2,
            fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        hint?.let {
            Text(
                it, color = if (warning) appColors.dangerText else appColors.muted,
                fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Row that leads to a sub-page. */
@Composable
private fun NavRow(label: String, value: String?, onClick: () -> Unit) {
    SettingRow(onClick = onClick) {
        LabelBlock(label, null, enabled = true)
        value?.let {
            Text(it, color = appColors.muted, fontSize = 15.sp)
            Spacer(Modifier.width(12.dp))
        }
        Text("›", color = appColors.muted, fontSize = 24.sp)
    }
}

/** Row showing the current value; tapping opens the option window. */
@Composable
private fun OptionRow(
    label: String,
    value: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    hint: String? = null,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    SettingRow(onClick = { open = true }, enabled = enabled && options.size > 1) {
        LabelBlock(label, hint, enabled)
        Text(
            value, color = if (enabled) appColors.accent else appColors.muted2,
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(10.dp))
        Text("▽", color = appColors.muted, fontSize = 16.sp)
    }
    if (open) {
        OptionWindow(label, options, selectedIndex, { onSelect(it); open = false }) { open = false }
    }
}

/** Row with a check box — the shape for anything that is simply on or off. */
@Composable
private fun CheckRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(onClick = { onChange(!checked) }) {
        LabelBlock(label, hint, enabled = true)
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
                .background(if (checked) appColors.accent else appColors.softBg)
                .border(
                    1.dp,
                    if (checked) appColors.accentBorder else appColors.softBorder,
                    RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text("✓", color = appColors.onAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Row that does something, with its button on the right. */
@Composable
private fun ActionRow(
    label: String,
    hint: String?,
    button: String,
    enabled: Boolean,
    progress: Float? = null,
    hintIsWarning: Boolean = false,
    onClick: () -> Unit,
) {
    SettingRow(onClick = null) {
        Column(Modifier.weight(1f)) {
            Text(
                label, color = if (enabled) appColors.text else appColors.muted2,
                fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            hint?.let {
                Text(
                    it, color = if (hintIsWarning) appColors.dangerText else appColors.muted,
                    fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            progress?.let {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { it },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = appColors.accent,
                    trackColor = appColors.softBg,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.heightIn(min = touchMin).widthIn(min = touchMin)
                .clip(RoundedCornerShape(12.dp))
                .background(if (enabled) appColors.softBg else appColors.panelAlt)
                .border(
                    1.dp,
                    if (enabled) appColors.softBorder else appColors.line,
                    RoundedCornerShape(12.dp),
                )
                .clickable(enabled = enabled) { onClick() }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                button, color = if (enabled) appColors.text else appColors.muted2,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
        }
    }
}

/** Row that only informs. */
@Composable
private fun InfoRow(label: String, text: String, monospace: Boolean = false) {
    SettingRow(onClick = null) {
        Column(Modifier.weight(1f)) {
            Text(label, color = appColors.text, fontSize = 17.sp)
            Text(
                text, color = appColors.muted, fontSize = 13.sp,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            )
        }
    }
}

/**
 * Appears only when the page is longer than the screen — on a touch screen it is the only honest
 * hint that there is more below — and shows how far down we are.
 */
@Composable
private fun ScrollBar(value: Int, max: Int) {
    if (max <= 0) return
    Box(Modifier.width(6.dp).fillMaxHeight().padding(vertical = 4.dp).background(appColors.softBg)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val thumb = (maxHeight * 0.25f).coerceAtLeast(40.dp)
            Box(
                Modifier.padding(top = (maxHeight - thumb) * (value.toFloat() / max).coerceIn(0f, 1f))
                    .fillMaxWidth().height(thumb).background(appColors.accent)
            )
        }
    }
}

/**
 * Option window: a title bar with a close key and one row per choice, the active one ticked.
 * Choosing closes it immediately — a setting is two taps, never a scroll through segments.
 */
@Composable
private fun OptionWindow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DIALOG_FULL_WIDTH) {
        Column(
            dialogSize(maxWidth = 560.dp).clip(RoundedCornerShape(16.dp))
                .background(appColors.panel)
                .border(1.dp, appColors.line, RoundedCornerShape(16.dp)),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = touchMin).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title, color = appColors.text, fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(touchMin).clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = appColors.muted, fontSize = 22.sp) }
            }
            HairLine()
            // Scroll the options: the list is unbounded (languages, skins, station lists) while the
            // car screen is only 600 px tall, and a plain Column would push the last entries off the
            // bottom with no way to reach them. Capped so the title bar always stays visible.
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            options.forEachIndexed { i, label ->
                val on = i == selectedIndex
                Row(
                    Modifier.fillMaxWidth().heightIn(min = touchMin)
                        .clickable { onPick(i) }.padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label, modifier = Modifier.weight(1f),
                        color = if (on) appColors.accent else appColors.text,
                        fontSize = 17.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (on) Text("✓", color = appColors.accent, fontSize = 20.sp)
                }
                if (i < options.lastIndex) HairLine()
            }
            }
        }
    }
}
