package com.px6.radio.logo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.max
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.px6.radio.model.Station
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Where a stored logo came from. Higher [rank] wins when both have one for the same station. */
enum class LogoSource(val rank: Int) {
    /** Logo pack downloaded on user request (radio-browser.info). */
    PACK(1),

    /** Delivered by the broadcaster itself (SPI/EPG via OMRI `RadioService.getLogos()`). */
    DAB(2),

    /** Official broadcaster logo resolved via RadioDNS SPI (SI.xml). Highest quality/coverage. */
    RADIODNS(3),

    /** Official DAB logo from the German network operator's Media Broadcast DAB-Logoservice,
     *  matched exactly by SId. Curated 600×600 SLS artwork — the best DAB source, so it wins. */
    MEDIA_BROADCAST(4),
}

/**
 * Persistent station-logo cache in `filesDir/logos/`.
 *
 * One file per station plus an `index.json` recording which source each logo came from, so a
 * broadcaster-supplied DAB logo is never overwritten by the downloaded pack — but the pack does
 * fill every gap, and a DAB logo always replaces a pack logo.
 *
 * Everything here is best-effort: a missing directory, unreadable file or broken index degrades
 * to "no logo", and the UI falls back to the coloured initials tile.
 */
class LogoStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "logos")
    private val indexFile = File(dir, "index.json")

    // Both maps are written from IO download threads (put/clear, via LogoPack, RadioDnsLogos,
    // MediaBroadcastLogos) and read from the main thread during composition (bitmap/crossBandKey) and
    // from the cluster mirror. Unsynchronised that is a real crash: crossBandKey ITERATES index.keys,
    // so a logo download landing while the station list is on screen throws a
    // ConcurrentModificationException straight out of composition. A plain lock is enough — the maps
    // are small and every critical section is a map op, never file or bitmap work.
    // (ConcurrentHashMap is not an option: `decoded` stores null to mean "known miss".)
    private val cacheLock = Any()

    /** station key -> source rank, mirrored to [indexFile]. Guarded by [cacheLock]. */
    private val index = HashMap<String, Int>()

    /** Decoded-bitmap cache; null value = "looked, found nothing". Guarded by [cacheLock]. */
    private val decoded = HashMap<String, ImageBitmap?>()

    /** Bumped on every change so Compose can re-read the cache. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    init {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            if (indexFile.exists()) {
                val json = JSONObject(indexFile.readText())
                synchronized(cacheLock) {
                    json.keys().forEach { k -> index[k] = json.optInt(k, LogoSource.PACK.rank) }
                }
            }
        }.onFailure { Log.w(TAG, "init failed: ${it.message}") }
    }

    /** Number of logos currently stored. */
    fun count(): Int = synchronized(cacheLock) { index.size }

    /** Source rank of the logo stored for [key], or 0 if none — lets callers skip already-covered
     *  stations (e.g. don't re-download a RadioDNS logo that's already cached). */
    fun sourceRank(key: String): Int = synchronized(cacheLock) { index[key] ?: 0 }

    /**
     * Stable per-station key. DAB and FM are kept apart because the same brand can differ
     * between the two (regional FM splits), and the name is normalised so "WDR 2 Rheinland",
     * "WDR2" and "wdr 2" all collapse onto the same entry.
     */
    fun key(station: Station): String = key(station.band.name, station.name)

    fun key(band: String, name: String): String = band.lowercase(Locale.ROOT) + ":" + normalize(name)

    fun bitmap(station: Station): ImageBitmap? {
        val k = key(station)
        synchronized(cacheLock) { if (decoded.containsKey(k)) return decoded[k] }
        var bmp = loadFile(k)
        if (bmp == null) {
            // Cross-band reuse: the same brand on the other band usually shares the logo (WDR 2 is on
            // both DAB and FM). FM/AM have far fewer logo sources than DAB, so let them borrow the
            // (often official) DAB logo of the same normalised name instead of showing bare initials.
            crossBandKey(station)?.let { bmp = loadFile(it) }
        }
        synchronized(cacheLock) { decoded[k] = bmp }
        return bmp
    }

    private fun loadFile(key: String): ImageBitmap? = runCatching {
        val f = file(key)
        if (!f.exists()) null else BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()
    }.getOrNull()

    /** A stored logo for the same station name on a different band, if any. */
    private fun crossBandKey(station: Station): String? {
        val norm = normalize(station.name)
        if (norm.isEmpty()) return null
        val selfPrefix = station.band.name.lowercase(Locale.ROOT) + ":"
        return synchronized(cacheLock) {
            index.keys.firstOrNull { it.endsWith(":$norm") && !it.startsWith(selfPrefix) }
        }
    }

    /**
     * Stores [bytes] for [key] unless an equal-or-higher-ranked source already provided one.
     * Returns true when the cache actually changed.
     */
    fun put(key: String, bytes: ByteArray, source: LogoSource): Boolean {
        val existing = synchronized(cacheLock) { index[key] }
        if (existing != null && existing >= source.rank) return false
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            // Reject anything that is not a decodable image — radio-browser favicons are
            // sometimes HTML error pages or SVGs, which BitmapFactory cannot handle.
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return false
            file(key).writeBytes(matteForDarkUi(bytes))
            synchronized(cacheLock) { index[key] = source.rank }
            // Clear the whole decode cache, not just this key: a cross-band consumer (an FM station
            // borrowing this DAB logo) may have cached a miss under its own key.
            synchronized(cacheLock) { decoded.clear() }
            writeIndex()
            _version.value++
            true
        }.getOrElse {
            Log.w(TAG, "put($key) failed: ${it.message}")
            false
        }
    }

    /**
     * Keep logos legible on the dark tiles. Homepage/favicon PNGs are often transparent with dark
     * ink (drawn for a light browser tab) and would vanish on our dark background. So: an opaque logo
     * is left untouched; a transparent one is analysed — if its ink is mostly **dark** it is baked
     * onto white (a light plate, like the factory radio), if mostly **light** it is left transparent
     * (it already reads on dark). Runs once, at store time.
     */
    private fun matteForDarkUi(bytes: ByteArray): ByteArray {
        val src = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: return bytes
        if (!src.hasAlpha()) return bytes
        val w = src.width; val h = src.height
        if (w <= 0 || h <= 0) return bytes
        var lumSum = 0.0; var count = 0
        val stepX = max(1, w / 32); val stepY = max(1, h / 32)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = src.getPixel(x, y)
                if (((p ushr 24) and 0xFF) > 40) {                 // reasonably opaque pixel
                    val r = (p ushr 16) and 0xFF; val g = (p ushr 8) and 0xFF; val b = p and 0xFF
                    lumSum += 0.299 * r + 0.587 * g + 0.114 * b; count++
                }
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return bytes                                // fully transparent — nothing to do
        if (lumSum / count / 255.0 >= 0.5) return bytes             // light ink -> fine on dark, keep alpha
        // Dark ink -> bake onto white so it stays visible on a dark tile.
        return runCatching {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
            ByteArrayOutputStream().use { bos -> out.compress(Bitmap.CompressFormat.PNG, 100, bos); bos.toByteArray() }
        }.getOrDefault(bytes)
    }

    /** Drops every stored logo (settings: "Logos löschen"). */
    fun clear() {
        runCatching {
            dir.listFiles()?.forEach { it.delete() }
            synchronized(cacheLock) { index.clear(); decoded.clear() }
            _version.value++
        }.onFailure { Log.w(TAG, "clear failed: ${it.message}") }
    }

    private fun file(key: String) = File(dir, key.replace(':', '_') + ".img")

    private fun writeIndex() {
        runCatching {
            val json = JSONObject()
            synchronized(cacheLock) { index.forEach { (k, v) -> json.put(k, v) } }
            indexFile.writeText(json.toString())
        }.onFailure { Log.w(TAG, "writeIndex failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "LogoStore"

        /** Lowercase, strip everything but letters/digits: "WDR 2 Rheinland" -> "wdr2rheinland". */
        fun normalize(name: String): String =
            name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    }
}

/**
 * The logo cache for composables. Null when no store exists (previews, tests), in which case the
 * UI falls back to the coloured initials plate.
 */
val LocalLogoStore = androidx.compose.runtime.staticCompositionLocalOf<LogoStore?> { null }

/** Bumped whenever the cache changes, so composables re-read their bitmaps. */
val LocalLogoVersion = androidx.compose.runtime.staticCompositionLocalOf { 0 }
