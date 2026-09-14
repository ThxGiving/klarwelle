package com.px6.radio.ews

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Derives the receiver's own DAB location code from the vehicle's position (ETSI TS 104 089 annex F),
 * so the alert area follows the car instead of describing wherever the user last typed in an address.
 *
 * Deliberately coarse and cheap: the finest location code cell is roughly 1 km across, so there is no
 * point sampling GPS more often or more precisely than that. Updates are requested at a slow interval
 * and a large displacement, which on a head unit that is permanently powered costs almost nothing —
 * and the code only actually changes when the car crosses a cell boundary.
 *
 * This never becomes the ONLY location: it is matched alongside the user's fixed codes, so watching
 * home while driving elsewhere keeps working.
 */
class GpsLocationCode(private val appContext: Context) {

    /** Latest derived code, or null if unknown (no permission, no fix yet, or a polar position). */
    @Volatile
    var code: EwsMatcher.Code? = null
        private set

    /** The position the current [code] came from, for the diagnostics panel. Null if none. */
    @Volatile
    var lastFix: Pair<Double, Double>? = null
        private set

    private var listener: LocationListener? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Begin following the vehicle position. Safe to call repeatedly; a no-op without permission. */
    fun start() {
        if (listener != null) return
        if (!hasPermission()) {
            Log.i(TAG, "no location permission — GPS location code stays off")
            return
        }
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val l = LocationListener { loc -> apply(loc) }
        listener = l
        // Seed from the last known fix so a code exists before the first update arrives.
        runCatching {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { lm.isProviderEnabled(it) }
                .firstNotNullOfOrNull { lm.getLastKnownLocation(it) }
                ?.let { apply(it) }
        }
        var any = false
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, UPDATE_MS, UPDATE_METERS, l, Looper.getMainLooper())
                    any = true
                }
            }.onFailure { Log.w(TAG, "requestLocationUpdates($p) failed: ${it.message}") }
        }
        if (!any) Log.i(TAG, "no location provider enabled")
    }

    /**
     * Stop following. Note this is the only thing that clears [code] — losing the fix (a tunnel, a
     * car park) deliberately keeps the last one. A slightly stale area still matches the region you
     * are in; having none at all would silently stop matching the moving area entirely.
     */
    fun stop() {
        val l = listener ?: return
        listener = null
        code = null
        lastFix = null
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        runCatching { lm?.removeUpdates(l) }
    }

    /** Called for every fix. Cheap enough to run each time — the code is a handful of shifts. */
    private fun apply(loc: Location) {
        val c = EwsMatcher.codeFromCoordinates(loc.latitude, loc.longitude) ?: return
        lastFix = loc.latitude to loc.longitude
        if (c == code) return                       // same cell — nothing to report
        code = c
        onCodeChanged?.invoke(c)
    }

    /** Notified only when the vehicle actually crosses into a new cell, for the diagnostics log. */
    @Volatile
    var onCodeChanged: ((EwsMatcher.Code) -> Unit)? = null

    private companion object {
        const val TAG = "GpsLocationCode"

        /**
         * How often to ask for a position, and how far the car must move before we care.
         *
         * Annex F fixes the useful scale: the finest location-code cell is 977 m north-south, and
         * east-west 978 m at the equator down to 302 m at 72 degrees — around 600 m at central
         * European latitudes. Sampling finer than that cannot change the answer, so the distance
         * filter is set to roughly half a cell and does the real work: parked, it costs nothing;
         * moving, it fires about when a cell boundary could have been crossed.
         *
         * The time bound is the backstop. It does not need to beat the cell-crossing time (17 s at
         * 130 km/h), because an alert is not a single event — Trigger repeats 10-30x/s for as long
         * as the alert runs. Being one cell stale for a few seconds therefore cannot lose an alert;
         * it only delays the match, and the alert is still being broadcast when the next fix lands.
         */
        const val UPDATE_MS = 20_000L
        const val UPDATE_METERS = 250f
    }
}
