package com.px6.radio.logo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs
import kotlin.math.max

/**
 * Whether a downloaded picture is usable as a station logo. Size alone is not enough: showing a
 * wrong or mushy picture is worse than showing the station's initials.
 *
 * The grade is a score, not a yes/no: the best candidate for a station wins, and a mediocre picture
 * is still stored when nothing better exists — a station with a logo of sorts beats coloured initials.
 * Only what is no logo at all is thrown away.
 *
 * Measured on the iOS fassung (`ImageQuality.swift`, `LogoDiscovery.swift`) where
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
            val luma = ((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
            // Over white, like the tile draws it. Transparency must NOT be ignored: a fully
            // transparent pixel carries arbitrary colour, so an ignored alpha channel produced hard
            // "edges" all around a cut-out logo — every transparent picture then measured 255 and the
            // whole check was dead. Composited, Radio Fortuna's upscaled icon measures 11 again.
            val a = c ushr 24 and 0xFF
            (luma * a + 255 * (255 - a)) / 255
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

    /** What a candidate picture is worth. */
    data class Rating(
        /** Decodable raster image at all, not blank, not an extreme strip — worth storing if nothing better turns up. */
        val acceptable: Boolean,
        /** Big, sharp: worth stopping the search for. */
        val good: Boolean,
        /** Roughly a square, as a logo tile wants it (0.8–1.25). */
        val squareish: Boolean,
        /** 0–270, higher is better. Only comparable between candidates for the same station. */
        val score: Int,
        val width: Int = 0,
        val height: Int = 0,
        val sharpness: Int = 0,
    ) {
        companion object { val NONE = Rating(acceptable = false, good = false, squareish = false, score = 0) }
    }

    /** Smallest edge we still store when nothing better exists — below this there is nothing to see. */
    const val FLOOR_PX = 48

    /**
     * Grades a candidate. The three properties a tile cares about — pixels, sharpness, proportion —
     * each give points, so a 300 px sharp logo beats a 512 px upscale and both beat a 64 px favicon,
     * without any of them being thrown away outright. Stations whose only picture is mediocre
     * (an upscaled icon, a wide press photo) then still get *a* logo instead of initials.
     */
    fun rate(bytes: ByteArray): Rating {
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: return Rating.NONE                                     // SVG, HTML error page, truncated file
        val w = bitmap.width
        val h = bitmap.height
        if (w < FLOOR_PX || h < FLOOR_PX) return Rating.NONE
        val ratio = w.toDouble() / h.toDouble()
        if (ratio < 0.25 || ratio > 4.0) return Rating.NONE           // a banner strip is not a logo
        val sharp = edgeSharpness(bitmap)
        if (sharp <= 0) return Rating.NONE                            // one flat colour: nothing on it
        val shortSide = minOf(w, h)
        val squareish = ratio >= 0.8 && ratio <= 1.25
        // Sharpness weighs heaviest: a small crisp logo looks right on a tile, a big mushy one never
        // does. Radio Fortuna's 512 px upscale (sharp 11) thus loses against its 315x212 page logo
        // (sharp 178) although it is bigger and square.
        val score = (minOf(shortSide, 512) * 80 / 512) +
            (minOf(sharp, 120) * 150 / 120) +
            when {
                squareish -> 40
                ratio in 0.5..2.0 -> 20
                else -> 0
            }
        return Rating(
            acceptable = true,
            good = shortSide >= MIN_PX && sharp >= MIN_EDGE,
            squareish = squareish,
            score = score,
            width = w, height = h, sharpness = sharp,
        )
    }
}
