package com.px6.radio.logo

import android.content.Context
import android.util.Log
import com.px6.radio.model.Band
import com.px6.radio.model.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Official DAB station logos from the German network operator **Media Broadcast**'s public
 * "DAB-Logoservice". The machine-readable form is two ZIPs of PNGs named
 * `<SId-HEX>_<Label>_<size>.png` (e.g. `D79D_Hellweg_600 x 600.png`). Because the filename carries
 * the **SId**, the match is exact — no name guessing — for ~205 services in the standard SLS sizes.
 *
 * One ~23 MB download per run, so this is wired to the manual "Logos laden" button only, never the
 * auto-fetch. Everything is best-effort: an unreachable server just stores nothing.
 */
object MediaBroadcastLogos {

    private const val TAG = "MediaBroadcastLogos"

    private val ZIPS = listOf(
        "https://www.media-broadcast.com/s/DAB-Programmlogos-Teil-1.zip",
        "https://www.media-broadcast.com/s/DAB-Programmlogos-Teil-2.zip",
    )
    private const val USER_AGENT = "Klarwelle/1.0"

    data class Result(val stored: Int, val error: String? = null)

    suspend fun download(
        context: Context,
        stations: List<Station>,
        store: LogoStore,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        // Our DAB stations grouped by SId (the ZIP's key). Several services can share a name but not
        // a SId, so this is a clean 1:1 lookup.
        val bySid = HashMap<Int, MutableList<Station>>()
        stations.filter { it.band == Band.DAB }.forEach { st ->
            sidOf(st.id)?.let { bySid.getOrPut(it) { mutableListOf() }.add(st) }
        }
        if (bySid.isEmpty()) return@withContext Result(0)

        // Keep each ZIP cached on disk and only re-download when it actually changed: the
        // media-broadcast.com URL 302-redirects, and the redirect carries a stable ETag that changes
        // only when the file is replaced. A tiny HEAD compares it to the ETag stored last time — same
        // ETag + a cached copy present ⇒ no 10 MB download, but we still re-match locally so a newly
        // added station gets its logo. Different ETag (or no cache) ⇒ download once and refresh both.
        val etags = loadEtags(context).toMutableMap()
        val cacheDir = File(context.applicationContext.filesDir, "mb_cache").apply { mkdirs() }

        // SId -> best (width, bytes) seen across both ZIPs (largest wins).
        val best = HashMap<Int, Pair<Int, ByteArray>>()
        var lastError: String? = null
        ZIPS.forEachIndexed { i, zurl ->
            onProgress(i, ZIPS.size)
            val cacheFile = File(cacheDir, zurl.substringAfterLast('/'))
            val remoteTag = runCatching { headEtag(zurl) }.getOrNull()
            val fresh = cacheFile.exists() && remoteTag != null && remoteTag == etags[zurl]
            if (!fresh) {
                val ok = runCatching { downloadTo(zurl, cacheFile) }
                    .onFailure { lastError = it.message; Log.w(TAG, "zip $zurl download failed: ${it.message}") }
                    .isSuccess
                if (ok && remoteTag != null) etags[zurl] = remoteTag
            } else {
                Log.i(TAG, "zip $zurl unverändert (ETag $remoteTag) — aus Cache")
            }
            if (cacheFile.exists()) {
                runCatching { readZipFile(cacheFile, bySid.keys, best) }
                    .onFailure { lastError = it.message; Log.w(TAG, "zip $cacheFile read failed: ${it.message}") }
            }
        }
        onProgress(ZIPS.size, ZIPS.size)
        saveEtags(context, etags)

        if (best.isEmpty()) return@withContext Result(0, lastError?.let { "Media Broadcast: $it" })

        var stored = 0
        for ((sid, wb) in best) {
            for (st in bySid[sid].orEmpty()) {
                if (store.put(store.key(st), wb.second, LogoSource.MEDIA_BROADCAST)) stored++
            }
        }
        Result(stored)
    }

    /** The ETag the media-broadcast.com URL returns on its 302 redirect (stable per file version). */
    private fun headEtag(zurl: String): String? {
        val conn = (URL(zurl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = false      // read the redirect's own ETag, don't chase the CDN
            requestMethod = "HEAD"
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            conn.responseCode                    // trigger the request
            conn.getHeaderField("ETag")?.trim()?.ifBlank { null }
        } finally {
            conn.disconnect()
        }
    }

    private fun etagFile(context: Context) = File(context.applicationContext.filesDir, "mb_etags.txt")

    private fun loadEtags(context: Context): Map<String, String> = runCatching {
        etagFile(context).takeIf { it.exists() }?.readLines()?.mapNotNull { line ->
            line.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }?.toMap()
    }.getOrNull() ?: emptyMap()

    private fun saveEtags(context: Context, etags: Map<String, String>) {
        runCatching {
            etagFile(context).writeText(etags.entries.joinToString("\n") { "${it.key}\t${it.value}" })
        }.onFailure { Log.w(TAG, "saveEtags failed: ${it.message}") }
    }

    /** Download one ZIP (following the CDN redirect) to [file], via a temp file so a failed transfer
     *  never leaves a truncated cache that would be trusted next time. */
    private fun downloadTo(zurl: String, file: File) {
        val conn = (URL(zurl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
            if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        } finally {
            conn.disconnect()
        }
    }

    /** Read a cached ZIP from disk, keeping the largest PNG for each wanted SId. */
    private fun readZipFile(file: File, wanted: Set<Int>, best: HashMap<Int, Pair<Int, ByteArray>>) {
        ZipInputStream(file.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".png", ignoreCase = true)) {
                    val base = entry.name.substringAfterLast('/').dropLast(4)   // strip ".png"
                    val sid = base.substringBefore('_').toIntOrNull(16)
                    if (sid != null && sid in wanted) {
                        // Width from the size suffix only ("600 x 600" / "320x240" / "112x32").
                        val width = Regex("\\d+").findAll(base.substringAfterLast('_'))
                            .mapNotNull { it.value.toIntOrNull() }.maxOrNull() ?: 0
                        val cur = best[sid]
                        if (cur == null || width > cur.first) {
                            val bytes = zis.readBytes()       // reads just this entry to its end
                            if (bytes.size > 64) best[sid] = width to bytes
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun sidOf(id: String): Int? = id.substringAfter('.', "").toIntOrNull(16)
}
