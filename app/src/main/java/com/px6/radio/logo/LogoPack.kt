package com.px6.radio.logo

import android.util.Log
import com.px6.radio.model.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Outcome of a logo download, shown in the settings. */
data class LogoPackResult(val matched: Int, val stored: Int, val error: String? = null)

/**
 * Downloads station logos on request.
 *
 * Source is **radio-browser.info** — an open community database with a documented API, no key and
 * no registration, which publishes a `favicon` URL per station. We deliberately do not ship logos
 * inside the app: they are third-party trademarks, so the user fetches them, we only cache them.
 * The original head unit works the same way — it has logos preinstalled and offers a download.
 *
 * Everything is best effort. No network, a slow server, a broken image: the run reports what it
 * managed and changes nothing else.
 */
object LogoPack {

    private const val TAG = "LogoPack"

    /** Mirrors of the API. The first that answers wins. */
    private val HOSTS = listOf(
        "https://de1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
        "https://all.api.radio-browser.info",
    )

    /** radio-browser asks callers to identify themselves. */
    private const val USER_AGENT = "Klarwelle/1.0"

    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 15_000

    /** A favicon larger than this is not a logo but a mistake. */
    private const val MAX_IMAGE_BYTES = 512 * 1024

    /**
     * Looks up every station in [stations] and stores the logos it finds.
     *
     * [onProgress] receives (done, total) so the settings can show progress. Stations that already
     * have a broadcaster-supplied logo are left alone by [LogoStore] itself.
     */
    suspend fun download(
        stations: List<Station>,
        store: LogoStore,
        countryCode: String = "DE",
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): LogoPackResult = withContext(Dispatchers.IO) {
        val index = runCatching { fetchIndex(countryCode) }.getOrElse {
            Log.w(TAG, "index failed: ${it.message}")
            return@withContext LogoPackResult(0, 0, "Senderliste nicht erreichbar: ${it.message}")
        }
        if (index.isEmpty()) {
            return@withContext LogoPackResult(0, 0, "Keine Senderdaten für Land $countryCode")
        }

        var matched = 0
        var stored = 0
        stations.forEachIndexed { i, station ->
            onProgress(i + 1, stations.size)
            val hit = match(index, station.name) ?: return@forEachIndexed
            matched++
            // Try, in order: the radio-browser favicon, then the icon the station's own homepage
            // declares (favicon / apple-touch-icon / og:image). Small local stations (NRW Lokalfunk
            // like Antenne Unna) are in radio-browser by name but their favicon link is often dead —
            // their live logo only lives on their own site, reachable via the homepage the DB carries.
            val candidates = buildList {
                if (hit.favicon.startsWith("http")) add(hit.favicon)
                if (hit.homepage.startsWith("http")) homepageIcon(hit.homepage)?.let { add(it) }
            }
            val bytes = candidates.firstNotNullOfOrNull { url ->
                runCatching { fetchImage(url) }.getOrElse {
                    Log.w(TAG, "logo ${station.name} via $url failed: ${it.message}"); null
                }
            } ?: return@forEachIndexed
            if (store.put(store.key(station), bytes, LogoSource.PACK)) stored++
        }
        LogoPackResult(matched, stored)
    }

    /**
     * Fetch a logo for one station whose favicon/homepage we already know (internet-radio stations,
     * where the stream DB hands us both directly — no name matching needed). Tries the favicon first,
     * then the icon the homepage declares, and stores the first that decodes. Best-effort.
     */
    suspend fun downloadDirect(
        store: LogoStore,
        key: String,
        faviconUrl: String?,
        homepage: String?,
        source: LogoSource = LogoSource.PACK,
    ): Boolean = withContext(Dispatchers.IO) {
        val candidates = buildList {
            faviconUrl?.takeIf { it.startsWith("http") }?.let { add(it) }
            homepage?.takeIf { it.startsWith("http") }?.let { homepageIcon(it)?.let(::add) }
        }
        val bytes = candidates.firstNotNullOfOrNull { url ->
            runCatching { fetchImage(url) }.getOrElse {
                Log.w(TAG, "logo via $url failed: ${it.message}"); null
            }
        } ?: return@withContext false
        store.put(key, bytes, source)
    }

    /** One radio-browser entry, prepared for matching. */
    private data class BrowserStation(
        val norm: String,
        val tokens: Set<String>,
        val favicon: String,
        val homepage: String,
    )

    /**
     * Resolves a station name against the browser index.
     *
     * Exact normalised equality is tried first (most-popular entry wins, the list is vote-ordered).
     * If that misses — and DAB names routinely do, because the broadcaster's short name ("WDR 2")
     * lacks the region/bitrate suffix radio-browser carries ("WDR 2 Rheinland") — we fall back to a
     * **token-subset** match: every token of the DAB name must appear in the browser name, and the
     * candidate with the fewest extra tokens (then highest popularity) wins. Requiring at least two
     * tokens keeps a generic single word ("Radio", "Antenne") from grabbing an unrelated station.
     */
    private fun match(index: List<BrowserStation>, name: String): BrowserStation? {
        val norm = LogoStore.normalize(name)
        if (norm.isEmpty()) return null
        index.firstOrNull { it.norm == norm }?.let { return it }       // exact, most popular
        val tokens = tokenize(name)
        if (tokens.size < 2) return null
        index.filter { it.tokens.containsAll(tokens) }
            .minByOrNull { it.tokens.size }                            // fewest extras; ties keep popularity
            ?.let { return it }
        // Prefix-token fallback: an abbreviated FM RDS name ("ANT UNNA") won't be a subset of the full
        // DB name ("Antenne Unna"), but each of its tokens is a PREFIX of a DB token. Require every
        // query token to prefix-match a distinct DB token, and the DB name to have no extra tokens
        // beyond that (so "ANT UNNA" hits "Antenne Unna", not "Antenne Unna Dein 80er Radio").
        return index
            .filter { it.tokens.size == tokens.size && prefixCovers(tokens, it.tokens) }
            .minByOrNull { it.tokens.size }
    }

    /** True if every [query] token is a prefix of a distinct token in [candidate]. */
    private fun prefixCovers(query: Set<String>, candidate: Set<String>): Boolean {
        val remaining = candidate.toMutableList()
        for (q in query) {
            val hit = remaining.firstOrNull { it.startsWith(q) } ?: return false
            remaining.remove(hit)
        }
        return true
    }

    /** Splits a name into lowercase alphanumeric tokens: "WDR 2 Rheinland" -> [wdr, 2, rheinland]. */
    private fun tokenize(name: String): Set<String> =
        name.lowercase(java.util.Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .toSet()

    /** Vote-ordered list of browser stations for the country (most popular first). */
    private fun fetchIndex(countryCode: String): List<BrowserStation> {
        var lastError: Exception? = null
        for (host in HOSTS) {
            try {
                val body = get("$host/json/stations/bycountrycodeexact/$countryCode?hidebroken=true")
                val array = JSONArray(String(body, Charsets.UTF_8))
                val list = ArrayList<BrowserStation>(array.length())
                val seen = HashSet<String>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val name = obj.optString("name").trim()
                    val favicon = obj.optString("favicon").trim()
                    val homepage = obj.optString("homepage").trim()
                    if (name.isEmpty()) continue
                    // Keep an entry if it offers *either* a favicon or a homepage — the homepage is the
                    // second chance (its declared site icon) when the favicon is missing or dead.
                    val hasFav = favicon.startsWith("http")
                    val hasHome = homepage.startsWith("http")
                    if (!hasFav && !hasHome) continue
                    val norm = LogoStore.normalize(name)
                    if (norm.isEmpty()) continue
                    // Keep the first (most-popular) favicon per exact name; keep every distinct name
                    // as its own entry so token-subset matching still has all regional variants.
                    if (!seen.add("$norm|$favicon|$homepage")) continue
                    list.add(BrowserStation(norm, tokenize(name), if (hasFav) favicon else "", if (hasHome) homepage else ""))
                }
                Log.i(TAG, "index from $host: ${list.size} stations")
                return list
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "host $host failed: ${e.message}")
            }
        }
        throw lastError ?: IllegalStateException("kein Server erreichbar")
    }

    private fun fetchImage(url: String): ByteArray? {
        val bytes = get(url, MAX_IMAGE_BYTES)
        // SVG and HTML error pages are common in this database and are not usable as logos.
        return if (bytes.size < 64) null else bytes
    }

    /**
     * The icon a station's homepage declares — the general, standardised fallback when the DB favicon
     * is missing or dead. Reads the page's `<link rel="icon"|"apple-touch-icon">` and `og:image` (a
     * W3C/OpenGraph convention every site follows), resolves it against the page URL and returns the
     * best square-ish candidate. This is not scraping one specific host: it reads whatever icon the
     * site itself advertises (for Antenne Unna that resolves to their own logo host, for WDR to WDR's).
     */
    private fun homepageIcon(homepage: String): String? {
        val html = runCatching { String(get(homepage, 1_000_000), Charsets.UTF_8) }.getOrNull() ?: return null
        val base = URL(homepage)
        var bestUrl: String? = null
        var bestScore = -1
        fun consider(href: String?, score: Int) {
            val h = href?.takeIf { it.isNotBlank() } ?: return
            if (h.endsWith(".svg", true)) return                       // BitmapFactory can't decode SVG
            val abs = runCatching { URL(base, h).toString() }.getOrNull() ?: return
            if (score > bestScore) { bestScore = score; bestUrl = abs }
        }
        // <link rel="... icon ..."> — apple-touch-icon is the cleanest square PNG, then largest sizes.
        Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val rel = attr(m.value, "rel")?.lowercase() ?: return@forEach
            if (!rel.contains("icon")) return@forEach
            val size = attr(m.value, "sizes")?.substringBefore('x')?.trim()?.toIntOrNull() ?: 0
            consider(attr(m.value, "href"), if (rel.contains("apple-touch-icon")) 1000 + size else 100 + size)
        }
        // Open Graph image as a last resort (often the logo, sometimes a wide banner).
        Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val prop = (attr(m.value, "property") ?: attr(m.value, "name"))?.lowercase()
            if (prop == "og:image" || prop == "og:image:url") consider(attr(m.value, "content"), 50)
        }
        return bestUrl?.let(::upgradeSharedBucketSize)
    }

    /**
     * The whole NRW Lokalfunk shares one logo bucket where the filename suffix encodes the size; a
     * station's `<link rel=icon>` points at the small 96×96 (`-37-40`), but the same key with `-35-39`
     * is a crisp 300×300 — far better for a tile. Rewrite the suffix when we recognise that bucket.
     */
    private fun upgradeSharedBucketSize(url: String): String =
        if (url.contains("logos-der-nrwlokalradios"))
            url.replace(Regex("-3\\d-\\d\\d\\.png$", RegexOption.IGNORE_CASE), "-35-39.png")
        else url

    /** Value of an HTML attribute in a single tag, single- or double-quoted. */
    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
            ?: Regex("\\b$name\\s*=\\s*'([^']*)'", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)

    private fun get(url: String, limit: Int = 8 * 1024 * 1024): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            connection.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > limit) throw IllegalStateException("Antwort zu groß")
                }
            }
            return out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }
}
