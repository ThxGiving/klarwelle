package com.px6.radio.ews

import android.content.Context
import android.location.Geocoder
import android.util.Log
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** A place the user can pick to derive a DAB location code from. */
data class Place(val label: String, val latitude: Double, val longitude: Double)

/**
 * Turns a typed place ("Unna", "Hauptstraße 1, Köln") into coordinates, so the app can work out the
 * DAB location code itself (annex F) instead of sending the user to asa.radio in a browser and back
 * with twelve digits to key in.
 *
 * Two sources, in order:
 *  1. Android's own [Geocoder]. Nothing leaves the app's control that the platform would not send
 *     anyway, and on a unit with the service it is the better answer.
 *  2. OpenStreetMap's Nominatim, because an aftermarket head unit often has no geocoder backend at
 *     all ([Geocoder.isPresent] is false without Play services). Free, no key, and their usage policy
 *     asks for an identifying User-Agent and low volume — a search a user typed by hand is exactly
 *     that.
 *
 * Both are outbound lookups of something the user typed, so this only ever runs on an explicit
 * search, never in the background.
 */
object PlaceSearch {

    private const val TAG = "PlaceSearch"
    private const val LIMIT = 8

    fun search(context: Context, query: String, locale: Locale = Locale.getDefault()): List<Place> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return viaGeocoder(context, q, locale).ifEmpty { viaNominatim(q, locale) }
    }

    @Suppress("DEPRECATION")   // the sync overload is the one that exists on API 30 (our minSdk path)
    private fun viaGeocoder(context: Context, q: String, locale: Locale): List<Place> {
        if (!Geocoder.isPresent()) return emptyList()
        return runCatching {
            Geocoder(context, locale).getFromLocationName(q, LIMIT).orEmpty().map { a ->
                val label = listOfNotNull(
                    a.getAddressLine(0)?.takeIf { it.isNotBlank() }
                        ?: listOfNotNull(a.locality, a.countryName).joinToString(", ").takeIf { it.isNotBlank() },
                ).firstOrNull() ?: q
                Place(label, a.latitude, a.longitude)
            }
        }.onFailure { Log.i(TAG, "Geocoder failed: ${it.message}") }.getOrDefault(emptyList())
    }

    private fun viaNominatim(q: String, locale: Locale): List<Place> = runCatching {
        val url = URL(
            "https://nominatim.openstreetmap.org/search?format=json&limit=$LIMIT&q=" +
                URLEncoder.encode(q, "UTF-8")
        )
        val body = (url.openConnection() as HttpURLConnection).run {
            connectTimeout = 8_000
            readTimeout = 8_000
            // Nominatim's usage policy requires an identifying User-Agent; anonymous clients are
            // blocked. Also ask for names in the user's language where OSM has them.
            setRequestProperty("User-Agent", "Klarwelle (DAB ASA location helper)")
            setRequestProperty("Accept-Language", locale.toLanguageTag())
            inputStream.bufferedReader().use { it.readText() }
        }
        val arr = JSONArray(body)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
            Place(o.optString("display_name").ifBlank { q }, lat, lon)
        }
    }.onFailure { Log.i(TAG, "Nominatim failed: ${it.message}") }.getOrDefault(emptyList())

    /**
     * The 12-symbol presentation code (annex A) for a place, ready to store next to hand-entered
     * ones — the reverse of [EwsMatcher.parseReceiverCode]. Null in the polar zones, where
     * [EwsMatcher.codeFromCoordinates] declines to guess.
     */
    fun presentationCodeFor(place: Place): String? =
        EwsMatcher.codeFromCoordinates(place.latitude, place.longitude)
            ?.let { EwsMatcher.presentationOf(it) }
}
