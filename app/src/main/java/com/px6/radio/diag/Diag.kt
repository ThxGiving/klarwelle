package com.px6.radio.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Writes diagnostics to the app-specific external dir on **every** volume — including a plugged
 * USB stick (`getExternalFilesDirs` returns one entry per volume). App-specific dirs are always
 * writable, so this needs no runtime storage permission. On the USB stick the file lands in
 * `Android/data/com.px6.radio/files/<name>` — just pull the stick and read it on a PC.
 *
 * Diagnostics must never make a caller wait: [write] queues, only [writeNow] does I/O inline.
 */
object Diag {

    /** Bound on queued writes; past it the newest lines are discarded (see the executor below). */
    private const val QUEUE_CAPACITY = 256

    // One lock for every write. The same file is written from more than one thread (e.g. the DAB↔FM
    // link log fires from both the DAB and FM state collectors): two concurrent writers to the shared
    // temp file interleaved their UTF-8 bytes and produced a corrupt (binary-looking) file.
    private val lock = Any()

    // ...and because that lock is process-wide, doing the I/O on the caller's thread coupled every
    // writer to the slowest one. A write to the USB stick can take hundreds of milliseconds, and the
    // callers include both the omri USB read thread (which sees a FIG 0/15 heartbeat ~1/s) and the UI
    // thread (SwitchTiming on every station change). The read thread then held the lock almost
    // continuously, the UI thread queued up behind it, and Android put up the "wait or close" ANR
    // dialog. So all writes go to one low-priority background thread: order is preserved, and no
    // caller — least of all the real-time USB read loop — ever blocks on storage.
    private val writer = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(QUEUE_CAPACITY),
        { r -> Thread(r, "klarwelle-diag").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
        // Diagnostics are expendable: if a stalled volume backs the queue up past its bound, drop the
        // newest lines rather than grow without limit or push back onto the caller.
        ThreadPoolExecutor.DiscardPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    /**
     * Identifies THIS run of the app, stamped once into every diagnostics file the run touches.
     *
     * Without it the files are one undated stream across weeks of driving, and telling "the version
     * with the fix behaved like this" from "the old one did" meant guessing where a run began. That
     * really happened: a boundary had to be inferred from an unrelated format change, and the whole
     * diagnosis rested on that guess. The header also carries the DATE, which the per-line stamps
     * (HH:mm) lack — every line below a header belongs to the day it names.
     */
    /**
     * Master switch. OFF by default in release: the files hold GPS-derived location codes, station
     * changes and key presses over days — a movement profile — and land on any plugged-in USB
     * stick. That is invaluable when chasing a fault and unacceptable as a silent default for a
     * store user. Set from the "Diagnosedateien schreiben" setting; the debug build starts with it
     * on. Crash reports pass regardless (see [writeNow]'s `force`): a stack trace carries no user
     * data, and it is the one file worth having when nothing else is known.
     */
    @Volatile var enabled: Boolean = false

    @Volatile private var sessionHeader: String? = null

    /** Files that already carry this run's header, so it is written once per file, lazily. */
    private val headered = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Begin a run. Call once at startup, before anything else writes. Cheap: it only builds the
     * header string — nothing is written until a file is actually used, so a run that never touches
     * a given file leaves no trace in it.
     */
    fun startSession(versionName: String, debug: Boolean) {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        // Short run id: minutes since the epoch, base 36. Unique enough to correlate lines ACROSS
        // files for one run, short enough not to bury the line it prefixes.
        val runId = java.lang.Long.toString(System.currentTimeMillis() / 60_000L, 36)
        sessionHeader = "\n=== Klarwelle $versionName${if (debug) " (debug)" else ""} · " +
            "Lauf $runId · Start $stamp ===\n"
        headered.clear()
    }

    /** The current run id, for callers that want to stamp it into a line of their own. */
    fun sessionId(): String = sessionHeader?.substringAfter("Lauf ")?.substringBefore(" ·") ?: "—"

    /**
     * Queue a diagnostic write. Returns immediately — the I/O happens on the diag thread, so a slow
     * or unplugged volume can never stall the caller. Use [writeNow] where the write must have landed
     * before the caller continues (crash paths, where the process may not survive the queue).
     */
    fun write(context: Context, name: String, text: String, append: Boolean = false) {
        if (!enabled) return
        // applicationContext so a queued task can never outlive and leak an Activity.
        val app = context.applicationContext
        writer.execute { writeNow(app, name, text, append) }
    }

    /** Remove every diagnostics file this app wrote, on every volume. Returns how many were deleted. */
    fun deleteAll(context: Context): Int {
        val dirs = try { context.getExternalFilesDirs(null) } catch (t: Throwable) { arrayOf(context.getExternalFilesDir(null)) }
        var n = 0
        synchronized(lock) {
            for (dir in dirs) {
                dir ?: continue
                dir.listFiles { f -> f.name.startsWith("klarwelle-") && f.name.endsWith(".txt") }
                    ?.forEach { if (it.delete()) n++ }
            }
        }
        headered.clear()
        return n
    }

    /** Blocking write — see [write]. Only for paths that must not lose the line to a dying process. */
    fun writeNow(context: Context, name: String, text: String, append: Boolean = false, force: Boolean = false) {
        if (!enabled && !force) return
        // First touch of this file in this run: prefix the header. Only for appends — an overwriting
        // write replaces the whole file anyway, and its content is a self-contained report.
        val body = if (append && headered.add(name)) (sessionHeader ?: "") + text else text
        val dirs = try {
            context.getExternalFilesDirs(null)
        } catch (t: Throwable) {
            arrayOf(context.getExternalFilesDir(null))
        }
        synchronized(lock) {
            for (dir in dirs) {
                dir ?: continue
                try {
                    if (!dir.exists()) dir.mkdirs()
                    val f = File(dir, name)
                    if (append) {
                        f.appendText(body)
                    } else {
                        // Atomic-ish overwrite: write a temp file, then replace the target. A plain
                        // writeText() truncates first, so pulling the stick mid-write left a 0-byte
                        // stub. Android's renameTo() won't overwrite an existing file, so delete first;
                        // the tiny gap is far safer than a truncated or half-written target.
                        val tmp = File(dir, "$name.tmp")
                        tmp.writeText(body)
                        if (f.exists()) f.delete()
                        if (!tmp.renameTo(f)) {
                            f.writeText(text)          // rename unsupported — direct write (under lock)
                            tmp.delete()
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "diag write to $dir failed: ${t.message}")
                }
            }
        }
    }

    private const val TAG = "Diag"
}
