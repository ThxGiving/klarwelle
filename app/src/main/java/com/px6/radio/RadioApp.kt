package com.px6.radio

import android.app.Application
import android.util.Log
import com.px6.radio.diag.Diag
import com.px6.radio.diag.HiddenApi
import com.px6.radio.ui.SplitFrontend
import com.px6.radio.ui.frontend.Frontends
import com.px6.radio.ui.frontend.TilesFrontend

/**
 * Crash diagnostics for a device we can't reach with adb.
 *
 * Writes any uncaught exception to the app-specific external dir on every volume (incl. a plugged
 * USB stick: Android/data/com.px6.radio/files/klarwelle-crash.txt) — just pull the stick and read it.
 * A pure native SIGSEGV won't land here; for that the boot-guard safe-mode names the crashing
 * subsystem on the next launch and [com.px6.radio.vm.RadioViewModel] writes klarwelle-diag.txt.
 */
class RadioApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Must run before any reflection on vendor framework classes (CarManager): Android 9+
        // otherwise reports "NoSuchMethod" for members that do exist.
        HiddenApi.unseal()

        // Available interfaces. The first registered one is the fallback when the persisted
        // choice names a frontend this build no longer has — keep it in sync with the default
        // Settings.frontendId (tiles).
        Frontends.register(TilesFrontend)
        Frontends.register(SplitFrontend)

        // Dev-only diagnostic: dump installed apps to find the one showing the outside temperature
        // (already served its purpose — temperature now arrives via the com.microntek.sync broadcast).
        // Debug-gated so the release/Play build needs no QUERY_ALL_PACKAGES permission.
        if (BuildConfig.DEBUG) com.px6.radio.diag.CarAppScout.dump(this)

        // Stamp this run into every diagnostics file it touches, before anything writes. The files
        // are append-only across weeks of driving; without a marker there is no way to tell which
        // lines came from which version, and a diagnosis then rests on guessing where a run began.
        // Until the persisted setting is read, only the debug build writes; the ViewModel applies the
        // user's choice as soon as settings are loaded (RadioViewModel.applyDiagnostics).
        Diag.enabled = BuildConfig.DEBUG
        Diag.startSession(BuildConfig.VERSION_NAME, BuildConfig.DEBUG)

        Diag.write(
            this, "klarwelle-alive.txt",
            "Klarwelle ${BuildConfig.VERSION_NAME} (${if (BuildConfig.DEBUG) "debug" else "release"}) " +
                "started at ${System.currentTimeMillis()}\n" +
                "hiddenApi=${HiddenApi.status}\n",
        )

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val text = buildString {
                append("=== Klarwelle crash ts=${System.currentTimeMillis()} thread=${thread.name} ===\n")
                append("app ${BuildConfig.VERSION_NAME} (${if (BuildConfig.DEBUG) "debug" else "release"})\n")
                append(Log.getStackTraceString(error))
                append("\n\n")
            }
            // writeNow, not write: the process is going down, so a queued write would be lost.
            Diag.writeNow(this, "klarwelle-crash.txt", text, append = true, force = true)
            Log.e(TAG, "uncaught on ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }

    private companion object {
        const val TAG = "RadioApp"
    }
}
