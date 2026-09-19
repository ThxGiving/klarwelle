package com.px6.radio

import com.px6.radio.diag.Diag
import com.px6.radio.diag.DiagFile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.px6.radio.ui.theme.appColors
import com.px6.radio.ui.MiniPlayerOverlay
import com.px6.radio.ui.RadioScreen
import com.px6.radio.ui.theme.AccentColor
import com.px6.radio.ui.theme.ModernDarkSkin
import com.px6.radio.ui.theme.ModernLightSkin
import com.px6.radio.ui.theme.Px6RadioTheme
import com.px6.radio.ui.theme.ScreenScale
import com.px6.radio.ui.theme.withAccent
import com.px6.radio.vm.RadioViewModel

class MainActivity : ComponentActivity() {

    // Apply the user-selected app language (see LocaleHelper) — read synchronously here, before the
    // UI/ViewModel exist, so resources resolve in the chosen language from the first frame.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.px6.radio.i18n.LocaleHelper.wrap(newBase))
    }


    private val vm: RadioViewModel by viewModels()

    /** Floating "now playing" window shown while the app is in the background and the radio plays. */
    private val overlay by lazy { MiniPlayerOverlay(this) }

    // Debug/emulator only: lets a stream be started/stopped from adb so the RadioDNS IP simulcast
    // can be heard without a DAB tuner —
    //   adb shell am broadcast -a com.px6.radio.DEBUG_IP_PLAY
    //   adb shell am broadcast -a com.px6.radio.DEBUG_IP_STOP
    private val debugIpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.px6.radio.DEBUG_IP_PLAY" -> vm.debugPlayIp()
                "com.px6.radio.DEBUG_IP_STOP" -> vm.debugStopIp()
                "com.px6.radio.DEBUG_SCAN" -> vm.debugSimulateScan()
                "com.px6.radio.DEBUG_EWS" -> {
                    // Optional scenario setup (debug only): set the receiver location code / test toggle
                    // so the positive matching paths can be demonstrated. Only touches settings, not
                    // the ASA logic.
                    intent.getStringExtra("setcode")?.let { code ->
                        vm.updateSettings { it.copy(asaLocationCodes = listOf(code), asaEnabled = true) }
                    }
                    if (intent.hasExtra("settest")) {
                        val t = intent.getBooleanExtra("settest", false)
                        vm.updateSettings { it.copy(asaTestAlerts = t, asaEnabled = true) }
                    }
                    // Follow the vehicle position, so an alert can be matched against the code
                    // DERIVED FROM GPS rather than one typed in. Without this the emulator cannot
                    // exercise that path at all: the ASA settings page is hidden on a unit with no
                    // DAB tuner, and this is the only way in.
                    if (intent.hasExtra("setgps")) {
                        val g = intent.getBooleanExtra("setgps", false)
                        vm.updateSettings { it.copy(asaFollowGps = g, asaEnabled = true) }
                    }
                    vm.debugSimulateEws(
                    form = intent.getIntExtra("form", 3),           // 3 = TRIGGER
                    stage = intent.getIntExtra("stage", 0),         // 0 = Level 1 Start
                    test = intent.getBooleanExtra("test", false),
                    otherEnsemble = intent.getBooleanExtra("oe", false),
                    idValue = intent.getIntExtra("id", 5),          // SubChId (or EId for oe)
                    incidentId = intent.getIntExtra("incident", 1),
                    locationCsv = intent.getStringExtra("loc") ?: "",
                    messageText = intent.getStringExtra("msg"),
                    )
                }
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enterImmersive()
        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(
                this, debugIpReceiver,
                IntentFilter().apply {
                    addAction("com.px6.radio.DEBUG_IP_PLAY")
                    addAction("com.px6.radio.DEBUG_SCAN")
                    addAction("com.px6.radio.DEBUG_IP_STOP")
                    addAction("com.px6.radio.DEBUG_EWS")
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            val skins by vm.skins.collectAsStateWithLifecycle()
            // Confirmed "Beenden" -> finish the Activity, which runs ViewModel.onCleared() and tears
            // every backend down (no leaked DAB audio, no second instance on relaunch).
            val quit by vm.shouldFinish.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(quit) { if (quit) finish() }
            // Location permission for "follow the vehicle": asked at START when the setting is on
            // and the grant is missing. The request in the settings row only fires when the switch
            // is flipped — a switch that was already on (set before the request existed, or on a
            // reinstall) never asked, and the user had to toggle it off and on to be prompted.
            val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
            ) { result ->
                // Re-evaluate: the GPS follower starts the moment the grant lands, not at next launch.
                if (result.values.any { it }) vm.updateSettings { it }
            }
            // Orientation follows the setting, not the manifest: landscape is the safe default for
            // head units; "automatic" lets a tablet or an upright unit turn. The Activity survives the
            // turn (configChanges), so audio and every open panel carry on.
            androidx.compose.runtime.LaunchedEffect(state.settingsLoaded, state.settings.autoRotate) {
                if (!state.settingsLoaded) return@LaunchedEffect
                requestedOrientation = if (state.settings.autoRotate)
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            androidx.compose.runtime.LaunchedEffect(state.settingsLoaded) {
                if (!state.settingsLoaded) return@LaunchedEffect
                val s = state.settings
                if (s.asaEnabled && s.asaFollowGps && !hasLocationPermission()) {
                    askLocation.launch(arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    ))
                }
            }
            // Two skins are kept — one dark, one light — so switching by time or headlights keeps
            // working with custom skins too.
            val wanted = if (state.darkActive) state.settings.skinDarkId else state.settings.skinLightId
            val base = skins.firstOrNull { it.id == wanted }
                ?: if (state.darkActive) ModernDarkSkin else ModernLightSkin
            val active = base.withAccent(AccentColor.byId(state.settings.accentId))
            ScreenScale { Px6RadioTheme(active) {
                // Non-blocking start: the themed background is up immediately (responsive), and the
                // radio itself slides in from the side once the persisted settings are in. Gating the
                // slide on settingsLoaded means the first *visible* frame already uses the saved skin,
                // so the tiles never jump size — the skin settle is hidden inside the entrance, not
                // shown as a relayout. settingsLoaded fires right after the settings read (before the
                // DAB/FM backends), so this waits on nothing heavy.
                val intro = remember { MutableTransitionState(false) }
                intro.targetState = state.settingsLoaded
                Box(Modifier.fillMaxSize().background(appColors.bg), contentAlignment = Alignment.Center) {
                    // Themed loading placeholder until the persisted state is in — so the wait shows the
                    // app name + a subtle progress instead of a blank/janky empty list.
                    AnimatedVisibility(
                        visible = !state.settingsLoaded,
                        enter = fadeIn(tween(0)),
                        exit = fadeOut(tween(260)),
                    ) {
                        StartupPlaceholder()
                    }
                    AnimatedVisibility(
                        visibleState = intro,
                        enter = slideInHorizontally(tween(320)) { it / 4 } + fadeIn(tween(320)),
                    ) {
                        RadioScreen(vm)
                    }
                }
            } }
        }
    }

    override fun onStart() {
        super.onStart()
        overlay.hide()   // back in the foreground — the full UI replaces the mini player
    }

    override fun onStop() {
        super.onStop()
        // Went to the background (HOME / another app) while still alive and playing — float the mini
        // player over other apps. Requires the "draw over other apps" permission (granted in
        // settings); without it we simply show nothing. Not shown when finishing (the exit modal).
        if (isFinishing) return
        val s = vm.state.value
        if (!s.settings.miniPlayerOverlay) return   // disabled in settings — never float the mini-player
        val station = s.nowPlaying?.station ?: return
        if (!s.isPlaying) return
        if (android.os.Build.VERSION.SDK_INT < 23 || !android.provider.Settings.canDrawOverlays(this)) return
        val logo = runCatching { vm.logoStore.bitmap(station)?.asAndroidBitmap() }.getOrNull()
        overlay.show(
            stationName = station.name,
            logo = logo,
            onOpen = {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onClose = { overlay.hide() },
        )
    }

    /**
     * Hardware / panel keys that reach the focused Activity. We log every key to the stick so an
     * unknown button (e.g. the "≡" list key) can be identified without adb, and open the station list
     * on the standard MENU key. Once the real code of the "3-lines" button is known from klarwelle-keys.txt
     * it can be added here.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            runCatching {
                Diag.write(
                    this, DiagFile.KEYS,
                    "key down code=${event.keyCode} (${android.view.KeyEvent.keyCodeToString(event.keyCode)}) scan=${event.scanCode}\n",
                    append = true,
                )
            }
            if (event.keyCode == android.view.KeyEvent.KEYCODE_MENU) {
                vm.openStationList()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        overlay.hide()
        if (BuildConfig.DEBUG) runCatching { unregisterReceiver(debugIpReceiver) }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    /** Head-unit UI: hide status + navigation bars (sticky, swipe reveals transiently). */
    private fun enterImmersive() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

/** Themed loading placeholder shown while the persisted state is read+decoded (off the main thread),
 *  so startup shows the app name + a subtle spinner instead of a blank or half-populated screen. */
@Composable
private fun StartupPlaceholder() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Klarwelle", color = appColors.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(22.dp))
        // The same wait mark the tuning overlay uses — see PulsingDots.
        com.px6.radio.ui.PulsingDots(color = appColors.accent, dot = 11.dp)
    }
}
