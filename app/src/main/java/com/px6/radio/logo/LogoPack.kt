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
    private const val MAX_IMAGE_BYTES = 1024 * 1024

    /** A page is read only up to its </head>; this is the hard ceiling if a page has none. */
    private const val HEAD_LIMIT = 1_500_000

    /** How much of a whole page we read when looking for the logo it shows. */
    private const val PAGE_LIMIT = 800_000

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
                if (hit.favicon.startsWith("http") && !isForeignSite(hit.favicon)) add(hit.favicon)
                if (hit.homepage.startsWith("http")) addAll(homepageIcons(hit.homepage).take(5))
            }
            val (bytes, grade) = bestLogo(candidates, station.name) {
                hit.homepage.takeIf { it.startsWith("http") }?.let { pageLogos(it) }.orEmpty()
            } ?: return@forEachIndexed
            if (store.put(store.key(station), bytes, LogoSource.PACK, grade)) stored++
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
            faviconUrl?.takeIf { it.startsWith("http") && !isForeignSite(it) }?.let { add(it) }
            homepage?.takeIf { it.startsWith("http") }?.let { addAll(homepageIcons(it).take(5)) }
        }
        val (bytes, grade) = bestLogo(candidates, key) {
            homepage?.takeIf { it.startsWith("http") }?.let { pageLogos(it) }.orEmpty()
        } ?: return@withContext false
        store.put(key, bytes, source, grade)
    }

    /** A candidate this good ends the search — downloading further pictures would be waste. */
    private const val GOOD_ENOUGH = 200

    /** At most this many addresses per station; the list is ordered, the tail is rarely better. */
    private const val MAX_CANDIDATES = 8

    /**
     * The **best** candidate, not the first passing one — and a mediocre picture is kept rather than
     * none. Every address is downloaded, graded by [LogoQuality.rate] and the highest grade wins;
     * the search stops early at [GOOD_ENOUGH]. Only what is no picture at all (SVG, HTML error page,
     * a blank or a banner strip) is thrown away, so a station whose only artwork is an upscaled icon
     * or a wide press photo still gets a logo instead of coloured initials.
     *
     * [more] is asked only when nothing good was found yet — it costs an extra page download.
     * For every address a WordPress original is tried first (see [unscaledOriginal]).
     */
    private fun bestLogo(candidates: List<String>, what: String, more: () -> List<String> = { emptyList() }): Pair<ByteArray, Int>? {
        var best: ByteArray? = null
        var bestScore = -1
        fun consider(urls: List<String>): Boolean {
            for (url in urls) {
                for (attempt in listOfNotNull(unscaledOriginal(url), url)) {
                    val bytes = runCatching { fetchImage(attempt) }.getOrElse {
                        Log.w(TAG, "logo $what via $attempt failed: ${it.message}"); null
                    } ?: continue
                    val rating = LogoQuality.rate(bytes)
                    if (!rating.acceptable) {
                        Log.i(TAG, "logo $what via $attempt is no usable picture"); continue
                    }
                    if (rating.score > bestScore) { bestScore = rating.score; best = bytes }
                    if (bestScore >= GOOD_ENOUGH) {
                        Log.i(TAG, "logo $what: $attempt ${rating.width}x${rating.height} score ${rating.score}")
                        return true
                    }
                }
            }
            return false
        }
        if (!consider(candidates.take(MAX_CANDIDATES))) consider(more().take(4))
        val found = best ?: run {
            Log.i(TAG, "logo $what: nothing usable among ${candidates.size} addresses"); return null
        }
        return found to bestScore
    }

    /**
     * What radio-browser knows about a **stream address** (`/json/stations/byurl`): its logo and
     * homepage. This is the exact lookup — no name guessing — and it rescues stations whose own
     * site is gone: Radio Tbilisi's tbilisi.fm no longer exists, but the directory carries our
     * stream as "Tbilisi FM 93.5" with a sharp 150 px logo on tbilisifm.ge.
     */
    fun byStreamUrl(streamUrl: String): Pair<String?, String?>? {
        for (host in HOSTS) {
            val body = runCatching {
                get("$host/json/stations/byurl?url=" + java.net.URLEncoder.encode(streamUrl, "UTF-8"), 512 * 1024)
            }.getOrNull() ?: continue
            val array = runCatching { JSONArray(String(body, Charsets.UTF_8)) }.getOrNull() ?: continue
            var favicon: String? = null
            var homepage: String? = null
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                if (favicon == null) o.optString("favicon").trim().takeIf { it.startsWith("http") }?.let { favicon = it }
                if (homepage == null) o.optString("homepage").trim().takeIf { it.startsWith("http") }?.let { homepage = it }
            }
            if (favicon != null || homepage != null) return favicon to homepage
        }
        return null
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
    /**
     * Every icon a station's homepage declares, best first. One candidate is not enough: the best
     * link is regularly dead or points at a mushy upscale, and the next one is fine.
     *
     * Order (from the iOS fassung, where these cases were measured): `apple-touch-icon` — including
     * `-precomposed`, which some stations use exclusively — by declared size, then `rel=icon` with
     * `sizes` >= 120, then the icons of the **web app manifest** (often 192/512 px), then the
     * conventional `/apple-touch-icon.png`, and only last the OpenGraph image (often a wide banner).
     */
    private fun homepageIcons(homepage: String): List<String> {
        if (isForeignSite(homepage)) return emptyList()
        val own = iconsOf(homepage)
        if (own.isNotEmpty()) return own
        // A station page deep in a site ("absolutradio.de/relax") may declare nothing or be gone;
        // the site root nearly always has icons. Costs one request and only when we found nothing.
        val root = runCatching { URL(homepage) }.getOrNull()
            ?.takeIf { it.path.trim('/').isNotEmpty() }
            ?.let { "${it.protocol}://${it.authority}/" }
        return if (root != null) iconsOf(root) else emptyList()
    }

    /**
     * A homepage that is not the station's own site: a Facebook page, a Linktree. Its icons are the
     * platform's logo, which would be plainly wrong on the tile — better none.
     */
    private fun isForeignSite(url: String): Boolean {
        val host = runCatching { URL(url).host }.getOrNull()?.lowercase(java.util.Locale.ROOT) ?: return true
        return FOREIGN_SITE.containsMatchIn(host)
    }

    private val FOREIGN_SITE = Regex(
        "(^|\\.)(facebook|fb|instagram|twitter|x|youtube|tiktok|linktr|bio\\.link|soundcloud|mixcloud|spotify)\\."
    )

    private fun iconsOf(homepage: String): List<String> {
        // Read the HEAD only. Station front pages run past a megabyte (Z100 1.25 MB, France Bleu
        // 950 KB); with a flat byte limit they failed entirely, and everything we need is in <head>.
        val html = runCatching { headOf(homepage) }.getOrNull() ?: return emptyList()
        val base = URL(homepage)
        data class Cand(val url: String, val score: Int)
        val found = ArrayList<Cand>()
        fun consider(href: String?, score: Int) {
            val h = href?.takeIf { it.isNotBlank() } ?: return
            if (h.endsWith(".svg", true)) return                       // BitmapFactory can't decode SVG
            val abs = runCatching { URL(base, h).toString() }.getOrNull() ?: return
            found += Cand(abs, score)
        }
        var manifestHref: String? = null
        Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val rel = attr(m.value, "rel")?.lowercase() ?: return@forEach
            if (rel.contains("manifest")) { manifestHref = attr(m.value, "href"); return@forEach }
            if (!rel.contains("icon")) return@forEach
            val size = attr(m.value, "sizes")?.substringBefore('x')?.trim()?.toIntOrNull() ?: 0
            when {
                rel.contains("apple-touch-icon") -> consider(attr(m.value, "href"), 1000 + size)
                size >= 120 -> consider(attr(m.value, "href"), 500 + size)
                // Any other declared icon, last in line: a plain `rel=icon` without a size is often
                // a 192 px PNG, and a mediocre logo beats coloured initials. The grading sorts it out.
                else -> consider(attr(m.value, "href"), 1)
            }
        }
        // Web app manifest icons — modern sites keep their largest, cleanest artwork there.
        manifestHref?.let { href ->
            val manifestUrl = runCatching { URL(base, href).toString() }.getOrNull()
            if (manifestUrl != null) {
                runCatching {
                    val obj = org.json.JSONObject(String(get(manifestUrl, 256 * 1024), Charsets.UTF_8))
                    val icons = obj.optJSONArray("icons")
                    for (i in 0 until (icons?.length() ?: 0)) {
                        val icon = icons!!.optJSONObject(i) ?: continue
                        val src = icon.optString("src").takeIf { it.isNotBlank() } ?: continue
                        if (icon.optString("type").contains("svg", true)) continue
                        val size = icon.optString("sizes").split(" ")
                            .mapNotNull { it.substringBefore('x').trim().toIntOrNull() }.maxOrNull() ?: 0
                        runCatching { URL(URL(manifestUrl), src).toString() }.getOrNull()
                            ?.let { found += Cand(it, 700 + size) }
                    }
                }
            }
        }
        consider("/apple-touch-icon.png", 10)
        consider("/favicon.ico", 0)                                    // the oldest convention, still common
        Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val prop = (attr(m.value, "property") ?: attr(m.value, "name"))?.lowercase()
            if (prop == "og:image" || prop == "og:image:url") consider(attr(m.value, "content"), 5)
        }
        return found.sortedByDescending { it.score }.map { upgradeSharedBucketSize(it.url) }.distinct()
    }

    /**
     * The logo a station's page actually **shows** — the last resort when its declared icons are
     * missing or mush. Many sites keep a crisp logo in the page while their favicon is a tiny or
     * upscaled copy of it (Radio Fortuna: 512 px porridge declared, a sharp 315x212 logo on screen).
     *
     * Generic, not per station: every `<img>` whose class, id or alt says "logo" — the convention
     * WordPress (`custom-logo`), Elementor and nearly every theme follow — minus the usual foreign
     * logos (sponsors, app stores, social buttons). The pictures are graded like any other candidate,
     * so a wrong guess simply loses.
     */
    private fun pageLogos(homepage: String): List<String> {
        if (isForeignSite(homepage)) return emptyList()
        val html = runCatching { String(get(homepage, PAGE_LIMIT, truncate = true), Charsets.UTF_8) }
            .getOrNull() ?: return emptyList()
        val base = runCatching { URL(homepage) }.getOrNull() ?: return emptyList()
        val found = LinkedHashSet<String>()
        Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { tag ->
            val src0 = attr(tag.value, "src") ?: attr(tag.value, "data-src")
            val marks = listOfNotNull(
                attr(tag.value, "class"), attr(tag.value, "id"), attr(tag.value, "alt"),
                src0?.substringAfterLast('/'),                          // "…/logo-2024.png" says it too
            ).joinToString(" ").lowercase()
            if (!marks.contains("logo") || FOREIGN_LOGO.containsMatchIn(marks)) return@forEach
            val src = src0?.trim()
                ?.takeIf { it.isNotBlank() && !it.startsWith("data:") && !it.endsWith(".svg", true) }
                ?: return@forEach
            val abs = runCatching { URL(base, src) }.getOrNull() ?: return@forEach
            // Only the station's own domain (its CDN counts). Pages carry foreign logos too —
            // tbilisifm.ge shows a "LIVE" logo of heretifm.com, and that must never become our logo.
            if (registrableDomain(abs.host) != registrableDomain(base.host)) return@forEach
            found += abs.toString()
        }
        return found.toList()
    }

    /** "cdn.fortuna.ge" -> "fortuna.ge": the last two labels, enough to tell sites apart here. */
    private fun registrableDomain(host: String): String =
        host.lowercase(java.util.Locale.ROOT).split('.').takeLast(2).joinToString(".")

    /** Logos on a station page that are not the station's: sponsors, stores, social buttons. */
    private val FOREIGN_LOGO = Regex(
        "sponsor|partner|advert|werbung|payment|visa|mastercard|facebook|instagram|twitter|youtube|" +
            "tiktok|whatsapp|telegram|app-?store|google-?play|windows|amazon|alexa"
    )

    /**
     * A page's `<head>`, at most [HEAD_LIMIT] bytes. Stops reading at `</head>` so a one-megabyte
     * front page costs a few kilobytes.
     */
    private fun headOf(url: String): String {
        val whole = String(get(url, HEAD_LIMIT, stopAt = "</head"), Charsets.UTF_8)
        val end = whole.indexOf("</head", ignoreCase = true)
        return if (end > 0) whole.substring(0, end) else whole
    }

    /**
     * WordPress serves scaled copies named `…-300x300.png` beside the uploaded original `….png`.
     * For such an address, try the original first — it is usually larger (Radio Fortuna: 512
     * instead of 300 px). Null when the address carries no size suffix.
     */
    private fun unscaledOriginal(url: String): String? {
        val m = Regex("-\\d{2,4}x\\d{2,4}(\\.(?:png|jpe?g))(\\?.*)?$", RegexOption.IGNORE_CASE).find(url)
            ?: return null
        return url.removeRange(m.range.first, m.range.first + m.value.length) + m.groupValues[1] + m.groupValues[2]
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

    /**
     * [truncate] returns what was read instead of failing when [limit] is reached — right for a
     * page we only scan, wrong for an image, which would be half a file.
     */
    private fun get(url: String, limit: Int = 8 * 1024 * 1024, stopAt: String? = null, truncate: Boolean = false): ByteArray {
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
                    // Early stop for HTML: everything we read a page for lives in its <head>.
                    if (stopAt != null && out.size() > 2048 &&
                        String(out.toByteArray(), Charsets.UTF_8).contains(stopAt, ignoreCase = true)
                    ) break
                    if (out.size() > limit) {
                        if (truncate) break
                        throw IllegalStateException("Antwort zu groß")
                    }
                }
            }
            return out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }
}
