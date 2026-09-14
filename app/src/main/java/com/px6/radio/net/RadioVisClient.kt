package com.px6.radio.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

/**
 * Minimal **RadioVIS** client (RadioDNS visualisation, ETSI TS 101 499) over the small STOMP 1.0
 * subset the broadcasters that still run it speak (BBC etc.). We talk that subset directly over a raw
 * TCP socket — **no library**: the old `gozirra` STOMP dep the hradio lib used is dead on Maven, and
 * the subscriber side is tiny. (SSE is the newer recommended transport, but STOMP is still live and
 * reuses the `_radiovis._tcp` SRV our RadioDNS resolver already finds.)
 *
 * This is the *general*, broadcaster-independent now-playing path for IP-only stations that carry no
 * in-band ICY/ID3 (the BBC HLS case) — not a per-broadcaster API. Two topics per bearer:
 *   `<base>/text`  → body `TEXT <now playing>`
 *   `<base>/image` → body `SHOW <image-url>` (+ optional `link:` header to a programme page)
 *
 * Verified live against BBC Radio 1 (`/topic/fm/ce1/c201/09880`):
 * ```
 * TEXT Now Playing: kenzo jae - end of the world
 * SHOW http://cdn.radiovis.api.bbci.co.uk/images/nowplaying/bbc_radio_one/segments/p0p3lhqk.png
 * ```
 * A wrong bearer makes the server answer `ERROR: Invalid Bearer in topic path` **and drop the whole
 * connection** — so on `ERROR` we stop for good and never reconnect that bearer (only transport drops
 * are retried, with backoff). The client is playback-scoped: run it in a Job tied to the currently
 * playing station and cancel it on any station change (cancellation closes the socket).
 */
class RadioVisClient(
    /** Resolves the RadioVIS endpoint (host, port, topic base) — called at the start of EVERY connect
     *  attempt, so a DNS failure at cold boot (network not up yet) is retried, not fatal. Return null
     *  when it can't be resolved right now. */
    private val resolve: () -> Target?,
    private val onText: (String) -> Unit,
    private val onImage: (url: String, link: String?) -> Unit,
    private val log: (String) -> Unit = {},
) {
    /** A resolved RadioVIS endpoint. */
    class Target(val host: String, val port: Int, val topicBase: String)

    suspend fun run() = withContext(Dispatchers.IO) {
        var backoff = 2_000L
        while (coroutineContext.isActive) {
            var badBearer = false
            // Resolve inside the loop: at cold boot the network is often still coming up, so the DNS
            // lookup fails once and then succeeds — retry it like any transport error instead of giving
            // up (that was "BBC plays but no now-playing until you switch stations once").
            val target = resolve()
            if (target == null) {
                delay(backoff); backoff = (backoff * 2).coerceAtMost(30_000L); continue
            }
            val host = target.host; val port = target.port; val topicBase = target.topicBase
            try {
                Socket().use { sock ->
                    // Cancellation is cooperative; a blocking socket read is not. Without this the
                    // coroutine stays parked inside readFrame() until READ_TIMEOUT_MS (five minutes)
                    // expires — holding a socket and an IO thread long after the station changed, and
                    // piling up one more for every switch in between. So a sibling coroutine sits on
                    // awaitCancellation(), which IS a suspension point: the moment this job is
                    // cancelled it resumes and closes the socket, and the blocked read throws. That is
                    // what the class contract above has always claimed happens.
                    //
                    // invokeOnCompletion does NOT work here: it fires at FINAL completion, which is
                    // precisely what the blocked read is holding up.
                    val closeOnCancel = launch {
                        try {
                            kotlinx.coroutines.awaitCancellation()
                        } finally {
                            runCatching { sock.close() }
                        }
                    }
                    try {
                    sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    // STOMP 1.0 has no heart-beats; if the feed is silent this long, drop and
                    // reconnect (a reconnect re-delivers the current text/image, so it is harmless).
                    sock.soTimeout = READ_TIMEOUT_MS
                    val out = sock.getOutputStream()
                    val ins = BufferedInputStream(sock.getInputStream())

                    send(out, "CONNECT\naccept-version:1.0,1.1,1.2\nhost:$host\nheart-beat:0,0\n")
                    val hs = readFrame(ins) ?: throw java.io.IOException("no CONNECTED frame")
                    if (hs.command != "CONNECTED") throw java.io.IOException("unexpected ${hs.command}")

                    send(out, "SUBSCRIBE\nid:0\ndestination:$topicBase/text\nack:auto\n")
                    send(out, "SUBSCRIBE\nid:1\ndestination:$topicBase/image\nack:auto\n")
                    log("radiovis connected $host:$port $topicBase")
                    backoff = 2_000L

                    while (coroutineContext.isActive) {
                        val f = readFrame(ins) ?: break   // server closed
                        when (f.command) {
                            "MESSAGE" -> dispatch(f)
                            "ERROR" -> { badBearer = true; log("radiovis ERROR: ${f.headers["message"]}"); break }
                        }
                    }
                    } finally {
                        closeOnCancel.cancel()
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                log("radiovis conn err: ${t.message}")
            }
            if (badBearer || !coroutineContext.isActive) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private fun dispatch(f: Frame) {
        val dest = f.headers["destination"] ?: return
        val body = f.body.trim()
        when {
            dest.endsWith("/text") -> body.removePrefix("TEXT ").trim().takeIf { it.isNotEmpty() }?.let(onText)
            dest.endsWith("/image") -> body.removePrefix("SHOW ").trim().takeIf { it.isNotEmpty() }
                ?.let { onImage(it, f.headers["link"]?.trim()) }
        }
    }

    private data class Frame(val command: String, val headers: Map<String, String>, val body: String)

    /** Write a STOMP frame: the passed text ends after the last header line; we add the blank line
     *  (header/body separator), an empty body, and the NUL terminator. */
    private fun send(out: OutputStream, frame: String) {
        out.write(frame.toByteArray(Charsets.UTF_8))
        out.write('\n'.code)   // blank line → empty body follows
        out.write(0)           // frame terminator
        out.flush()
    }

    /** Read one NUL-terminated STOMP frame. Returns null at end of stream. */
    private fun readFrame(ins: InputStream): Frame? {
        val buf = ByteArrayOutputStream(256)
        while (true) {
            val b = ins.read()
            if (b == -1) return if (buf.size() == 0) null else parse(buf.toString("UTF-8"))
            if (b == 0) return parse(buf.toString("UTF-8"))
            buf.write(b)
        }
    }

    private fun parse(rawIn: String): Frame {
        // Servers may pad frames with leading newlines (STOMP heart-beat/keep-alive); strip them.
        val raw = rawIn.trimStart('\n', '\r')
        val sep = raw.indexOf("\n\n")
        val head = if (sep >= 0) raw.substring(0, sep) else raw
        val body = if (sep >= 0) raw.substring(sep + 2) else ""
        val lines = head.split("\n")
        val command = lines.firstOrNull()?.trim().orEmpty()
        val headers = lines.drop(1).mapNotNull { line ->
            val c = line.indexOf(':')
            if (c < 0) null else line.substring(0, c).trim() to line.substring(c + 1)
        }.toMap()
        return Frame(command, headers, body)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 300_000   // BBC only pushes on song change; long idle is normal
    }
}
