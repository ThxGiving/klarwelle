package com.px6.radio.logo

import android.content.Context
import android.util.Log
import com.px6.radio.model.Band
import com.px6.radio.model.RadioDnsBearer
import com.px6.radio.model.Station
import eu.hradio.core.radiodns.PxRadioDnsLookup
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches **official broadcaster logos** (and collects IP simulcast stream URLs) via RadioDNS.
 *
 * This is the coverage engine that lifts us from "a logo for a few stations" (radio-browser
 * favicons) to "a logo for essentially every station" — the same official artwork the factory
 * radio shows, served by the broadcasters themselves. Validated live against Deutschlandfunk;
 * see the project memory `radiodns-viable-validated`.
 *
 * How it works: DAB stations are grouped by ensemble (EId), because a single RadioDNS SI document
 * describes every service in an ensemble at once. One lookup per ensemble therefore yields logos +
 * streams for all its stations. Results land in the persistent [LogoStore] (cached on disk, so a
 * logo is fetched once and then survives restarts) and the discovered stream URLs are returned for
 * the later DAB->FM->IP fallback tier.
 *
 * Everything is best-effort and blocking (DNS + HTTP) — run it off the main thread.
 */
object RadioDnsLogos {

    private const val TAG = "RadioDnsLogos"

    data class Result(
        val logosStored: Int,
        /** DAB ensembles ACTUALLY looked up — not the number present. A run that skipped every
         *  ensemble ("already tried this session") reports 0, which is what tells the caller its
         *  report carries nothing and must not overwrite a meaningful one. */
        val ensemblesQueried: Int,
        /** FM stations actually looked up, same meaning as [ensemblesQueried]. */
        val fmQueried: Int = 0,
        val streamsByStationId: Map<String, String>,
        val error: String? = null,
        /** Per-ensemble resolver trace (FQDN + which step resolved/failed), for the diagnostics file. */
        val diag: List<String> = emptyList(),
    )

    /**
     * @param stations the current station list (DAB entries are used; FM handled separately later)
     * @param tried ensemble ids already looked up this session — skipped unless [force]. Updated in
     *   place, so "look once" is honoured: an ensemble with no RadioDNS entry (or one whose SI is
     *   missing a station's logo) is not re-queried on every scan/startup/tune. Pass a fresh set to
     *   ignore.
     * @param force re-query even already-tried ensembles (the manual "Logos laden" button, so a
     *   user can re-check when something is still missing).
     * @param onProgress (done, total) over ensembles
     */
    fun fetch(
        context: Context,
        stations: List<Station>,
        store: LogoStore,
        tried: MutableSet<Int> = mutableSetOf(),
        /** Station ids that already have an IP simulcast — used to decide whether an FM lookup is
         *  still worth doing once its logo is cached. */
        knownStreams: Set<String> = emptySet(),
        force: Boolean = false,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Result {
        // NOTE: no early return when there are no DAB services — the FM branch below stands on its
        // own, and returning here left an FM-only head unit without RadioDNS logos or simulcasts.
        val dab = stations.filter { it.band == Band.DAB }

        // Group by ensemble id (the first half of "eid.sid"); one SI lookup covers the whole group.
        val byEnsemble: Map<Int, List<Station>> = dab
            .mapNotNull { st -> parseEid(st.id)?.let { it to st } }
            .groupBy({ it.first }, { it.second })

        var stored = 0
        var ensemblesQueried = 0
        var fmQueried = 0
        var logoOk = 0
        var logoFail = 0
        var noLogoUrl = 0
        val streams = HashMap<String, String>()
        val diag = mutableListOf<String>()
        val ensembles = byEnsemble.entries.toList()

        ensembles.forEachIndexed { idx, (eid, group) ->
            onProgress(idx, ensembles.size)

            // "Look once": skip an ensemble we already queried this session, unless forced.
            if (!force && tried.contains(eid)) return@forEachIndexed

            // Skip the whole ensemble if every station already has a RadioDNS logo cached.
            val needLogo = group.any { store.sourceRank(store.key(it)) < LogoSource.RADIODNS.rank }

            val repSid = parseSid(group.first().id) ?: return@forEachIndexed
            // ECC from the ensemble (any station in it carries it) -> gcc derived per country.
            val ecc = group.firstNotNullOfOrNull { it.ecc } ?: RadioDnsBearer.DEFAULT_ECC
            tried.add(eid)
            ensemblesQueried++
            val result = try {
                PxRadioDnsLookup.lookupDab(context, eid, repSid, ecc)
            } catch (t: Throwable) {
                diag += "eid=${eid.toString(16)} EXC ${t.message}"
                Log.w(TAG, "lookup failed for eid ${eid.toString(16)}: ${t.message}")
                return@forEachIndexed
            }
            diag += PxRadioDnsLookup.lastDiag   // e.g. "0.d210.10bc.de0.dab… cname=8.8.8.8 srv=8.8.8.8 services=4"
            if (result.isEmpty) return@forEachIndexed

            // Index our stations in this ensemble by SId for mapping.
            val bySid: Map<Int, Station> = group.mapNotNull { st -> parseSid(st.id)?.let { it to st } }.toMap()

            for (entry in result.services) {
                val station = bySid[entry.sid] ?: continue

                // Remember the best IP simulcast (lowest RadioDNS cost) for the fallback tier.
                bestStream(entry.streams)?.let { streams[station.id] = it }

                if (!needLogo) continue
                val key = store.key(station)
                if (store.sourceRank(key) >= LogoSource.RADIODNS.rank) continue
                val logoUrl = bestLogo(entry.logos)
                if (logoUrl == null) { noLogoUrl++; continue }
                val bytes = downloadBytes(logoUrl)
                if (bytes == null) { logoFail++; continue }
                logoOk++
                if (store.put(key, bytes, LogoSource.RADIODNS)) stored++
            }
        }
        // FM stations resolve individually via the FM RadioDNS FQDN (<freq5>.<pi>.<gcc>.fm…) — no
        // ensemble, keyed by the RDS PI we've learned. Only stations with a known PI + frequency and
        // still missing a RadioDNS logo are queried, so it's a handful of lookups at most.
        val fmStations = stations.filter {
            it.band == Band.FM && it.piCode != null && (it.frequencyKhz ?: 0) > 0
        }
        for (st in fmStations) {
            val key = store.key(st)
            // The logo alone is no longer the reason to look up: the SI also carries the IP simulcast
            // for the DAB/FM->internet fallback. Skipping a station whose logo was cached long ago
            // meant its stream address was never discovered at all.
            val haveLogo = store.sourceRank(key) >= LogoSource.RADIODNS.rank
            if (!force && haveLogo && st.id in knownStreams) continue
            val pi = st.piCode!!
            val gcc = RadioDnsBearer.fmGcc(pi, st.ecc)
            fmQueried++
            val res = try {
                PxRadioDnsLookup.lookupFm(context, pi, st.frequencyKhz!!, gcc)
            } catch (t: Throwable) {
                diag += "fm pi=${pi.toString(16)} EXC ${t.message}"; continue
            }
            if (res.isEmpty) continue
            // Pick the service whose fm bearer carries THIS PI — the FM authority lists the whole
            // provider (WDR → 1LIVE, WDR 2, 3, 5…), so taking the first service's logo gave 1LIVE for
            // every WDR frequency. Only fall back to the first when NO service advertises a PI (a
            // single-station SI); if it's a multi-station SI and ours isn't listed, assign nothing
            // rather than a wrong logo.
            val match = res.services.firstOrNull { it.fmPi == pi }
            // Remember the IP simulcast, exactly as the DAB branch does. Its absence here was why an
            // FM-only station (a local one like Antenne Unna) could never fall back to the internet.
            (match ?: res.services.singleOrNull())?.let { svc ->
                bestStream(svc.streams)?.let { streams[st.id] = it }
            }
            if (haveLogo) continue
            val logoUrl = when {
                match != null -> bestLogo(match.logos)
                res.services.none { it.fmPi >= 0 } -> res.services.firstNotNullOfOrNull { bestLogo(it.logos) }
                else -> null
            }
            if (logoUrl == null) { noLogoUrl++; continue }
            val bytes = downloadBytes(logoUrl)
            if (bytes == null) { logoFail++; continue }
            logoOk++
            if (store.put(key, bytes, LogoSource.RADIODNS)) stored++
        }

        onProgress(ensembles.size, ensembles.size)
        diag += "Logos: $logoOk geladen · $logoFail Download-Fail · $noLogoUrl ohne Logo-URL"
        return Result(stored, ensemblesQueried, fmQueried, streams, diag = diag)
    }

    /** Pick the highest-quality logo: prefer square (looks best as a tile / album art), then largest. */
    private fun bestLogo(logos: List<PxRadioDnsLookup.Logo>): String? =
        logos.filter { it.url.isNotBlank() }
            .maxWithOrNull(
                compareBy<PxRadioDnsLookup.Logo>({ if (it.width == it.height && it.width > 0) 1 else 0 })
                    .thenBy { it.width * it.height }
            )?.url

    /** Lowest RadioDNS cost wins (broadcaster's preferred simulcast); ties broken by higher bitrate. */
    private fun bestStream(streams: List<PxRadioDnsLookup.Stream>): String? =
        streams.filter { it.url.isNotBlank() }
            .minWithOrNull(
                compareBy<PxRadioDnsLookup.Stream>({ if (it.cost >= 0) it.cost else Int.MAX_VALUE })
                    .thenByDescending { it.bitrate }
            )?.url

    private fun parseEid(id: String): Int? = id.substringBefore('.', "").toIntOrNull(16)
    private fun parseSid(id: String): Int? = id.substringAfter('.', "").toIntOrNull(16)

    /**
     * Download the logo, following redirects manually — HttpURLConnection's own redirect handling
     * does NOT cross http<->https, and broadcaster logo hosts (WDR/ARD) commonly bounce between the
     * two. That silently failed all such logos (302 != 200 -> null) while a non-redirecting host
     * (Deutschlandradio) worked — the "streams found but logos not stored" symptom.
     */
    private fun downloadBytes(url: String): ByteArray? {
        var current = url
        try {
            for (i in 0 until 5) {
                val conn = URL(current).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                conn.connect()
                when (conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> return conn.inputStream.use { it.readBytes() }
                    301, 302, 303, 307, 308 -> {
                        val loc = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (loc.isNullOrEmpty()) return null
                        current = URL(URL(current), loc).toString()   // resolve relative locations too
                    }
                    else -> { conn.disconnect(); return null }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "download failed $url: ${t.message}")
        }
        return null
    }
}
