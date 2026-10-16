package com.px6.radio.logo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs
import kotlin.math.max

/**
 * Whether a downloaded picture is usable as a station logo. Size alone is not enough: showing a
 * wrong or mushy picture is worse than showing the station's initials.
 *
 * Three gates, learned from the iOS fassung (`ImageQuality.swift`, `LogoDiscovery.swift`) where
 * these cases were measured:
 *  - **raster image** at all — radio-browser serves HTML error pages and SVGs as "favicon";
 *  - **at least 96 px** and, for automatically discovered pictures, roughly **square** (0.8–1.25).
 *    Rai Radio 1's radio-browser favicon is a 560×292 press photo; hand-checked logos of seeded
 *    stations may have other proportions and pass `requireSquare = false`;
 *  - **really sharp**. Some stations store a small icon scaled up to 512 px (Radio Fortuna): it
 *    passes every size check and is mush on screen.
 */
object LogoQuality {

    /** Smallest edge we accept — below this a tile shows pixel porridge. */
    const val MIN_PX = 96

    /** Edge measure below this counts as blurred; see [edgeSharpness] for the measurements. */
    const val MIN_EDGE = 40

    /**
     * How sharp a picture really is, independent of its pixel count: the strongest brightness step
     * between neighbouring pixels (99th percentile of the clear steps, > 8). A real logo has hard
     * edges somewhere — measured on station logos from 128 to 1024 px it lies between 78 and 202,
     * while the upscaled Fortuna icon reaches 11. Measures based on "detail lost when shrinking"
     * were tried first and do NOT separate these: a plain, sharp logo loses as little as a blurred one.
     */
    fun edgeSharpness(bitmap: Bitmap): Int {
        val side = minOf(512, max(bitmap.width, bitmap.height))
        if (side <= 2) return 0
        val scaled = runCatching {
            if (bitmap.width == side && bitmap.height == side) bitmap
            else Bitmap.createScaledBitmap(bitmap, side, side, true)
        }.getOrNull() ?: return 0
        val px = IntArray(side * side)
        runCatching { scaled.getPixels(px, 0, side, 0, 0, side, side) }.getOrElse { return 0 }
        val grey = IntArray(px.size) { i ->
            val c = px[i]
            // Rec. 601 luma, integer — alpha ignored on purpose: a transparent logo is drawn on a
            // plate later, and its ink is what must be sharp.
            ((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        }
        val steps = ArrayList<Int>(side * side / 4)
        for (y in 0 until side - 1) {
            val row = y * side
            for (x in 0 until side - 1) {
                val i = row + x
                val step = max(abs(grey[i] - grey[i + 1]), abs(grey[i] - grey[i + side]))
                if (step > 8) steps.add(step)
            }
        }
        if (steps.isEmpty()) return 0          // a blank picture is no logo either
        steps.sort()
        return steps[((steps.size - 1) * 0.99).toInt()]
    }

    /**
     * Gate for a picture about to be stored as a logo. [requireSquare] is true for anything found
     * automatically (web icons, directory favicons) and false for hand-checked or broadcaster-supplied
     * artwork, which may legitimately be wide.
     */
    fun isUsable(bytes: ByteArray, requireSquare: Boolean = true): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w < MIN_PX || h < MIN_PX) return false
        val ratio = w.toDouble() / h.toDouble()
        if (requireSquare && (ratio < 0.8 || ratio > 1.25)) return false
        if (ratio < 0.4 || ratio > 2.5) return false
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: return false
        return edgeSharpness(bitmap) >= MIN_EDGE
    }
}
