package com.px6.radio.internet

import android.util.Log
import com.px6.radio.model.Band
import com.px6.radio.model.Station
import com.px6.radio.model.stationNameKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Internet radio as a first-class band ([Band.IP]).
 *
 * Two ways a station gets here, deliberately mirroring how the rest of the app works:
 *  - **Example seeds** — a small, *editable* starter list (a few London and Tbilisi stations, so the
 *    band is useful the moment it appears). These are examples, not a fixed catalogue: the user can
 *    delete them and add their own. We do not ship a hardcoded lock-in station DB.
 *  - **Search** — live lookup against **radio-browser.info**, the same open, keyless community API we
 *    already use for logos. Anything the user wants (BBC, Radio Tbilisi, …) is one search away, and
 *    the resolved stream URL comes from the DB so it stays fresh instead of rotting in the source.
 *
 * A found/seeded station is an ordinary [Station] with `band = IP` and a `streamUrl`; it plays
 * through the existing [com.px6.radio.audio.IpPlayer] via `AudioRouter.toIp`, the same path the
 * DAB→FM→IP fallback already uses. No following, no tuner — just a URL.
 */
object InternetRadio {

    private const val TAG = "InternetRadio"
    private const val USER_AGENT = "Klarwelle/1.0"

    private val HOSTS = listOf(
        "https://de1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
        "https://all.api.radio-browser.info",
    )

    /** A ready-to-add internet station plus the favicon URL radio-browser knows for it (for the logo). */
    data class Hit(val station: Station, val favicon: String?)

    /** One editable example seed. `homepage` powers the logo lookup; `favicon` is a direct hint. */
    data class Seed(
        val name: String,
        val city: String,
        val country: String,
        val url: String,
        val homepage: String,
        val favicon: String = "",
        /** RadioDNS bearer for RadioVIS now-playing (see [Station.radioDnsBearer]). */
        val bearer: String? = null,
    )

    /**
     * Editable example stations — London + Tbilisi, so the band is immediately useful. Stream URLs
     * were verified reachable (200 / audio content-type) at authoring time; if one dies the user can
     * replace it via search. NOT a hardcoded catalogue — just examples the user owns.
     */
    /** Bumped whenever seeds are added; existing installs merge the new ones in (see the ViewModel). */
    const val SEED_VERSION = 7

    /** Seed ids retired in a later version — removed from existing installs on the seed-version
     *  migration (not just withheld from fresh installs). Russian stations pulled per request. */
    val REMOVED_SEED_IDS = setOf(
        "ip.europaplus", "ip.retrofm", "ip.vestifm", "ip.dfm",   // Russian stations
        "ip.bbcasiannetwork",                                    // trimmed from the BBC set
        "ip.bfbsradiogermany", "ip.bfbsgurkharadio",             // kept only one BFBS
        "ip.radioimedi", "ip.ardaidardo",                        // Tbilisi: pulled per request
    )

    val SEEDS: List<Seed> = listOf(
        // --- London (commercial, plain MP3) ---
        Seed("LBC", "London", "GB",
            "http://media-ice.musicradio.com/LBCUK", "https://www.lbc.co.uk"),
        Seed("Heart London", "London", "GB",
            "http://ice-sov.musicradio.com/HeartLondonMP3", "https://www.heart.co.uk"),
        Seed("Classic FM", "London", "GB",
            "http://ice-the.musicradio.com/ClassicFMMP3", "https://www.classicfm.com"),
        // --- BBC national (HLS; plays via media3) ---
        Seed("BBC Radio 1", "London", "GB",
            "http://as-hls-ww-live.akamaized.net/pool_01505109/live/ww/bbc_radio_one/bbc_radio_one.isml/bbc_radio_one-audio%3d320000.norewind.m3u8",
            "https://www.bbc.co.uk/radio1", bearer = "fm/ce1/c201/09880"),
        Seed("BBC Radio 1Xtra", "London", "GB",
            "http://as-hls-ww-live.akamaized.net/pool_92079267/live/ww/bbc_1xtra/bbc_1xtra.isml/bbc_1xtra-audio%3d320000.norewind.m3u8",
            "https://www.bbc.co.uk/1xtra"),
        Seed("BBC Radio 2", "London", "GB",
            "http://as-hls-ww-live.akamaized.net/pool_74208725/live/ww/bbc_radio_two/bbc_radio_two.isml/bbc_radio_two-audio%3d320000.norewind.m3u8",
            "https://www.bbc.co.uk/radio2"),
        Seed("BBC Radio 3", "London", "GB",
            "http://as-hls-ww-live.akamaized.net/pool_23461179/live/ww/bbc_radio_three/bbc_radio_three.isml/bbc_radio_three-audio%3d96000.norewind.m3u8",
            "https://www.bbc.co.uk/radio3"),
        Seed("BBC Radio 4", "London", "GB",
            "http://as-hls-ww-live.akamaized.net/pool_55057080/live/ww/bbc_radio_fourfm/bbc_radio_fourfm.isml/bbc_radio_fourfm-audio=128000.norewind.m3u8",
            "https://www.bbc.co.uk/radio4"),
        Seed("BBC Radio 4 Extra", "London", "GB",
            "http://as-hls-ww-live.akamaized.net/pool_26173715/live/ww/bbc_radio_four_extra/bbc_radio_four_extra.isml/bbc_radio_four_extra-audio=320000.norewind.m3u8",
            "https://www.bbc.co.uk/radio4extra"),
        Seed("BBC Radio 5 Live", "London", "GB",
            "http://as-hls-ww-live.akamaized.net/pool_89021708/live/ww/bbc_radio_five_live/bbc_radio_five_live.isml/bbc_radio_five_live-audio%3d128000.norewind.m3u8",
            "https://www.bbc.co.uk/5live"),
        Seed("BBC Radio 6 Music", "London", "GB",
            "http://as-hls-ww-live.akamaized.net/pool_81827798/live/ww/bbc_6music/bbc_6music.isml/bbc_6music-audio=320000.norewind.m3u8",
            "https://www.bbc.co.uk/6music"),
        Seed("BBC World Service", "London", "GB",
            "http://stream.live.vc.bbcmedia.co.uk/bbc_world_service",
            "https://www.bbc.co.uk/worldserviceradio"),
        Seed("BBC Radio London", "London", "GB",
            "http://as-hls-ww-live.akamaized.net/pool_98137350/live/ww/bbc_london/bbc_london.isml/bbc_london-audio%3d96000.norewind.m3u8",
            "https://www.bbc.co.uk/radiolondon"),
        Seed("BFBS Radio", "London", "GB",
            "https://listen-ssvcbfbs.sharp-stream.com/ssvcbfbs1.aac", "https://www.bfbs.com"),
        // --- Tbilisi / Georgia ---
        Seed("Radio Tbilisi", "Tiflis", "GE",
            "http://iis.ge:8000/radiotbilisi.mp3", "https://www.tbilisi.fm"),
        // Master playlist (single 22.05 kHz mono ~49 kbps AAC-LC rendition — the only one the CDN
        // offers; verified). Cleaner than hardcoding the a1 variant it points to.
        Seed("Radio Fortuna", "Tiflis", "GE",
            "https://tv.cdn.xsg.ge/cld9-0386/fortuna/playlist.m3u8", "https://fortuna.ge"),
        Seed("Radio Fortuna Plus", "Tiflis", "GE",
            "https://tv.cdn.xsg.ge/cld9-0386/fortunaplus/playlist.m3u8", "https://fortuna.ge"),
        Seed("Radio Palitra", "Tiflis", "GE",
            "https://radiostream.palitra.ge/stream.mp3", "https://www.palitranews.ge"),
        Seed("Radio Positive", "Tiflis", "GE",
            "https://stream.radiojar.com/sr4vzamzfnruv", ""),
        Seed("Radio 2 (Tbilisi FM)", "Tiflis", "GE",
            "https://tv.cdn.xsg.ge/gpb-radio2/tracks-a1/index.m3u8", ""),
        Seed("Radio Commersant", "Tiflis", "GE",
            "https://radio.cdn.xsg.ge/fm-commersant-95.5/tracks-a1/mono.m3u8", ""),
        Seed("Radio Chveneburi", "Tiflis", "GE",
            "https://radio.cdn.xsg.ge/cld9-1050/chveneburi/index.m3u8", ""),
        Seed("Radio Sivrce", "Tiflis", "GE",
            "https://proxy.streamer.mediabox.ge/ice/8000/sivrce", ""),
        Seed("Marneuli FM", "Marneuli", "GE",
            "http://stream.it-solutions.ge:8000/mp3-radiomarneulifm", ""),
        Seed("Radio Adjara", "Batumi", "GE",
            "https://edge.mixlr.com/channel/bzorq", ""),
        Seed("Radio Amra", "Tiflis", "GE",
            "https://streamer.radio.co/s34b5469e0/listen", ""),
        // --- London (weitere) ---
        Seed("Capital London", "London", "GB",
            "http://media-ice.musicradio.com/CapitalMP3", "https://www.capitalfm.com"),
        Seed("NTS Radio 1", "London", "GB",
            "http://stream-relay-geo.ntslive.net/stream", "https://www.nts.live"),
        // --- Berlin ---
        Seed("Deutschlandfunk", "Berlin", "DE",
            "https://st01.sslstream.dlf.de/dlf/01/128/mp3/stream.mp3?aggregator=web", "https://www.deutschlandfunk.de"),
        Seed("Deutschlandfunk Kultur", "Berlin", "DE",
            "https://st02.sslstream.dlf.de/dlf/02/128/mp3/stream.mp3?aggregator=web", "https://www.deutschlandfunkkultur.de"),
        Seed("radioeins", "Berlin", "DE",
            "http://dispatcher.rndfnk.com/rbb/radioeins/live/mp3/mid", "https://www.radioeins.de"),
        Seed("Inforadio", "Berlin", "DE",
            "http://dispatcher.rndfnk.com/rbb/inforadio/live/mp3/mid", "https://www.inforadio.de"),
        // --- Paris ---
        Seed("RFI Monde", "Paris", "FR",
            "http://live02.rfi.fr/rfimonde-64.mp3", "https://www.rfi.fr"),
        Seed("France Bleu Paris", "Paris", "FR",
            "http://direct.francebleu.fr/live/fb1071-midfi.mp3", "https://www.francebleu.fr"),
        Seed("Générations", "Paris", "FR",
            "http://generationfm.ice.infomaniak.ch/generationfm-high.mp3", "https://generations.fr"),
        Seed("Le Son Parisien", "Paris", "FR",
            "http://stream.lesonparisien.com/hi.mp3", ""),
        // --- New York ---
        Seed("Z100", "New York", "US",
            "https://stream.revma.ihrhls.com/zc1469", "https://z100.iheart.com"),
        Seed("Classic Vinyl HD", "New York", "US",
            "https://icecast.walmradio.com:8443/classic", "https://walmradio.com"),
        Seed("The Big 80s Station", "New York", "US",
            "http://158.69.114.190:8065/;", ""),
        Seed("Frisky", "New York", "US",
            "http://stream2.friskyradio.com/frisky_mp3_hi", "https://www.friskyradio.com"),
        // --- Tokyo ---
        Seed("NHK World Radio Japan", "Tokyo", "JP",
            "https://masterpl.hls.nhkworld.jp/hls/r1/live/master.m3u8", "https://www3.nhk.or.jp/nhkworld"),
        Seed("Stereo Anime", "Tokyo", "JP",
            "https://radio.stereoanime.com/listen/stereoanime/128", ""),
        Seed("J1 HITS", "Tokyo", "JP",
            "https://jenny.torontocast.com:2000/stream/J1HITS", "https://www.j1fm.com"),
        Seed("FM Setagaya", "Tokyo", "JP",
            "https://fmsetagaya834.out.airtime.pro/fmsetagaya834_a", ""),
        // --- Madrid ---
        Seed("esRadio", "Madrid", "ES",
            "http://livestreaming.esradio.fm/stream64.mp3", "https://esradio.libertaddigital.com"),
        Seed("KISS FM España", "Madrid", "ES",
            "https://adhandler.kissfmradio.cires21.com/get_link?url=https://bbkissfm.kissfmradio.cires21.com/bbkissfm.mp3", "https://www.kissfm.es"),
        Seed("Onda Cero", "Madrid", "ES",
            "https://atres-live.ondacero.es/live/ondacero/bitrate_1.m3u8", "https://www.ondacero.es"),
        Seed("Cadena 100", "Madrid", "ES",
            "https://cadena100-cope.flumotion.com/chunks.m3u8", "https://www.cadena100.es"),
        // --- Rom ---
        Seed("Rai Radio 1", "Rom", "IT",
            "http://icestreaming.rai.it/1.mp3", "https://www.raiplaysound.it"),
        Seed("Rai Radio 2", "Rom", "IT",
            "http://icestreaming.rai.it/2.mp3", "https://www.raiplaysound.it"),
        Seed("Rai Radio 3", "Rom", "IT",
            "http://icestreaming.rai.it/3.mp3", "https://www.raiplaysound.it"),
        Seed("Radio Radicale", "Rom", "IT",
            "http://live.radioradicale.it/live.mp3", "https://www.radioradicale.it"),
        // --- Amsterdam ---
        Seed("NPO Radio 1", "Amsterdam", "NL",
            "https://icecast.omroep.nl/radio1-sb-aac", "https://www.nporadio1.nl"),
        Seed("Radio 538", "Amsterdam", "NL",
            "http://playerservices.streamtheworld.com/api/livestream-redirect/TLPSTR01.mp3", "https://www.538.nl"),
        Seed("NPO Radio 6 Soul & Jazz", "Amsterdam", "NL",
            "https://icecast.omroep.nl/radio6-bb-mp3", "https://www.nporadio6.nl"),
        Seed("FunX", "Amsterdam", "NL",
            "https://icecast.omroep.nl/funx-sb-aac", "https://www.funx.nl"),
    )

    /** The example stations as ready [Station]s (band=IP). */
    fun seedStations(): List<Station> = SEEDS.map { seed ->
        station(
            rawId = seed.name,
            name = seed.name,
            city = seed.city,
            country = seed.country,
            url = seed.url,
            homepage = seed.homepage,
            bearer = seed.bearer,
        )
    }

    /** Favicon hints for seeds, keyed by station id — so their logos can be fetched like search hits. */
    fun seedHits(): List<Hit> = SEEDS.map { seed ->
        Hit(
            station = station(seed.name, seed.name, seed.city, seed.country, seed.url, seed.homepage),
            favicon = seed.favicon.ifBlank { null },
        )
    }

    /**
     * Search radio-browser for internet stations matching [query]. Returns up to [limit] playable
     * hits (broken streams hidden, most-clicked first), each a ready-to-add [Station]. Blocking —
     * runs on [Dispatchers.IO].
     */
    suspend fun search(query: String, limit: Int = 40): List<Hit> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        val enc = URLEncoder.encode(q, "UTF-8")
        val path = "/json/stations/search?name=$enc&limit=$limit&hidebroken=true&order=clickcount&reverse=true"
        val body = firstReachable(path) ?: return@withContext emptyList()
        parse(body)
    }

    /**
     * The internet stream of a **broadcast** station (DAB/FM), for the DAB->FM->internet fallback.
     *
     * RadioDNS is the proper source for this and is tried first, but most broadcasters simply do not
     * run it: WDR, and every local station like Antenne Unna, publish nothing at all — measured, not
     * assumed. radio-browser knows both. So this is the second source, deliberately strict, because a
     * loose name match is how you end up playing a Russian house channel when the user asked for a
     * Spanish talk station (that really happens on this API):
     *
     *  - the normalised name must match EXACTLY — no "contains", no fuzzy distance;
     *  - the country must match when we know it (derived from the broadcast ECC);
     *  - of the remaining hits the most-voted one wins.
     *
     * Returns null rather than a doubtful guess: no fallback is better than the wrong station.
     */
    suspend fun findSimulcast(name: String, countryCode: String?): String? = withContext(Dispatchers.IO) {
        val target = name.stationNameKey()
        if (target.length < 3) return@withContext null
        val enc = URLEncoder.encode(name.trim(), "UTF-8")
        val cc = countryCode?.takeIf { it.length == 2 }?.let { "&countrycode=$it" }.orEmpty()
        val path = "/json/stations/search?name=$enc$cc&limit=20&hidebroken=true&order=votes&reverse=true"
        val body = firstReachable(path) ?: return@withContext null
        runCatching {
            val arr = JSONArray(String(body, Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("name").stationNameKey() != target) continue
                val url = o.optString("url_resolved").ifBlank { o.optString("url") }.trim()
                if (url.startsWith("http")) return@runCatching url
            }
            null
        }.getOrNull()
    }

    /**
     * ISO country for a RadioDNS **gcc** (country id nibble + ECC), for the [findSimulcast] filter.
     *
     * The ECC alone does not identify a country — 0xE0 covers Germany (country id D) as well as Italy
     * (5) and Ireland (2); only the pair is unique. Listed here are the two we can currently verify;
     * everything else returns null, which widens the search rather than filtering it wrongly. The
     * exact-name requirement, not this filter, is what keeps a match honest.
     */
    fun countryForGcc(gcc: String?): String? = when (gcc?.lowercase(Locale.ROOT)) {
        "de0" -> "DE"
        "ce1" -> "GB"
        else -> null
    }

    /** Build a stable [Station] for an internet stream. Id is `ip.<slug>` so presets survive restarts. */
    fun station(
        rawId: String,
        name: String,
        city: String?,
        country: String?,
        url: String,
        homepage: String?,
        bearer: String? = null,
    ): Station {
        val where = listOfNotNull(city?.takeIf { it.isNotBlank() }, country?.takeIf { it.isNotBlank() })
            .joinToString(" · ")
        return Station(
            id = "ip." + slug(rawId.ifBlank { name }),
            radioDnsBearer = bearer,
            name = name,
            band = Band.IP,
            // City as the group key, so the "Gruppen"-sort clusters the list by city (like DAB by
            // ensemble). Falls back to country, then a generic bucket.
            ensemble = city?.takeIf { it.isNotBlank() } ?: country?.takeIf { it.isNotBlank() } ?: "Internet",
            subtitle = if (where.isBlank()) "Internet" else "Internet · $where",
            logoInitials = name.filter { it.isLetterOrDigit() }.take(2).uppercase(Locale.ROOT).ifBlank { "IN" },
            logoStart = 0xFF2E6BF0L, logoEnd = 0xFF123FA8L,   // internet = blue plate
            streamUrl = url,
            homepage = homepage?.takeIf { it.isNotBlank() },
        )
    }

    /** Lowercase alphanumeric slug for a stable id: "BBC Radio 1" -> "bbcradio1". */
    private fun slug(s: String): String =
        s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.ifBlank { s.hashCode().toString(16) }

    private fun parse(body: ByteArray): List<Hit> = runCatching {
        val arr = JSONArray(String(body, Charsets.UTF_8))
        val seen = HashSet<String>()
        val out = ArrayList<Hit>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }.trim()
            if (name.isEmpty() || !url.startsWith("http")) continue
            val uuid = o.optString("stationuuid").ifBlank { url }
            if (!seen.add(uuid)) continue
            val station = station(
                rawId = uuid,
                name = name,
                city = o.optString("state").trim().ifBlank { null },
                country = o.optString("country").trim().ifBlank { null },
                url = url,
                homepage = o.optString("homepage").trim().ifBlank { null },
            )
            out += Hit(station, o.optString("favicon").trim().ifBlank { null })
        }
        out
    }.getOrElse {
        Log.w(TAG, "parse failed: ${it.message}"); emptyList()
    }

    private fun firstReachable(path: String): ByteArray? {
        for (host in HOSTS) {
            runCatching { return get("$host$path") }
                .onFailure { Log.w(TAG, "host $host failed: ${it.message}") }
        }
        return null
    }

    private fun get(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 4 * 1024 * 1024) throw IllegalStateException("Antwort zu groß")
                }
            }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }
}
