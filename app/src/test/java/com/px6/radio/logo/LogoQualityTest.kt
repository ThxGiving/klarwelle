package com.px6.radio.logo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The picture gate that decides whether a downloaded file may be shown as a station logo. Needs
 * Robolectric because BitmapFactory is Android's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogoQualityTest {

    /** A crisp logo: hard black/white edges, like real artwork. */
    private fun sharp(w: Int, h: Int = w): ByteArray = png(w, h) { x, y ->
        if ((x / 8 + y / 8) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF101010.toInt()
    }

    /**
     * The Fortuna case: a small icon blown up. Modelled honestly — a coarse grid of values with
     * bilinear interpolation between them, which is what a scaler leaves behind: every step between
     * neighbouring pixels is small, however many pixels the file has.
     */
    private fun upscaled(size: Int, cell: Int = 16, alpha: (Int, Int) -> Int = { _, _ -> 255 }): ByteArray =
        png(size, size) { x, y ->
            val v = ramp(x, y, cell)
            (alpha(x, y) shl 24) or (v shl 16) or (v shl 8) or v
        }

    /** Bilinear value over a coarse grid: the signature of an upscale. */
    private fun ramp(x: Int, y: Int, cell: Int): Int {
        fun corner(cx: Int, cy: Int) = ((cx * 7 + cy * 13) * 61 % 256)
        val gx = x / cell; val gy = y / cell
        val fx = (x % cell) / cell.toDouble(); val fy = (y % cell) / cell.toDouble()
        val top = corner(gx, gy) * (1 - fx) + corner(gx + 1, gy) * fx
        val bottom = corner(gx, gy + 1) * (1 - fx) + corner(gx + 1, gy + 1) * fx
        return (top * (1 - fy) + bottom * fy).toInt().coerceIn(0, 255)
    }

    private fun png(w: Int, h: Int, pixel: (Int, Int) -> Int): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) bmp.setPixel(x, y, pixel(x, y))
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** Big and sharp enough to end the search — the bar the old yes/no gate used. */
    private fun good(bytes: ByteArray): Boolean = LogoQuality.rate(bytes).let { it.good && it.squareish }

    @Test fun `a sharp square logo passes`() {
        assertTrue(good(sharp(256)))
    }

    @Test fun `a tiny favicon is rejected`() {
        assertFalse(good(sharp(48)))
    }

    @Test fun `a wide press photo is rejected when a square is required`() {
        val wide = LogoQuality.rate(sharp(560, 292))
        assertFalse("not what a tile wants", wide.squareish)
        assertTrue("but sharp and big enough to be stored for want of better", wide.good && wide.acceptable)
    }

    @Test fun `an upscaled icon is rejected although it is large`() {
        assertFalse(good(upscaled(512)))
    }

    @Test fun `html or svg served as a favicon is rejected`() {
        assertFalse(LogoQuality.rate("<!doctype html><html><body>404</body></html>".toByteArray()).acceptable)
        assertFalse(LogoQuality.rate("<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray()).acceptable)
    }

    @Test fun `a blank picture is no logo`() {
        assertFalse(LogoQuality.rate(png(256, 256) { _, _ -> 0xFF808080.toInt() }).acceptable)
    }

    @Test fun `sharpness separates artwork from upscale`() {
        val a = android.graphics.BitmapFactory.decodeByteArray(sharp(256), 0, sharp(256).size)
        val up = upscaled(512)
        val b = android.graphics.BitmapFactory.decodeByteArray(up, 0, up.size)
        assertTrue(LogoQuality.edgeSharpness(a) >= LogoQuality.MIN_EDGE)
        assertTrue(LogoQuality.edgeSharpness(b) < LogoQuality.MIN_EDGE)
        assertEquals(40, LogoQuality.MIN_EDGE)
    }
    /** A cut-out logo: ink on a transparent ground, with junk colour under the transparent pixels. */
    private fun cutOut(size: Int, sharpInk: Boolean): ByteArray {
        val soft = { x: Int, y: Int -> ramp(x, y, 16) }          // a soft, scaled alpha edge
        return if (sharpInk) png(size, size) { x, y ->
            if (soft(x, y) < 128) 0x00123456
            else if ((x / 8 + y / 8) % 2 == 0) 0xFF101010.toInt() else 0xFFFFFFFF.toInt()
        } else upscaled(size, alpha = soft).let { it }
    }

    @Test fun `transparency does not fake sharp edges`() {
        // Before: the junk colour under transparent pixels made every cut-out logo measure as sharp,
        // so the check never fired and mushy upscales were stored (Radio Fortuna).
        assertFalse(good(cutOut(512, sharpInk = false)))
        assertTrue(good(cutOut(512, sharpInk = true)))
    }

    @Test fun `a mediocre picture is still better than no logo`() {
        val upscale = LogoQuality.rate(upscaled(512))
        val photo = LogoQuality.rate(sharp(560, 292))
        assertTrue("stored when nothing better exists", upscale.acceptable && photo.acceptable)
        assertFalse(upscale.good)
        // ... but a real logo always wins the comparison, big and square as the upscale may be.
        assertTrue(LogoQuality.rate(sharp(256)).score > upscale.score)
        assertTrue(LogoQuality.rate(sharp(315, 212)).score > upscale.score)
    }

    @Test fun `what is no picture is never stored`() {
        for (junk in listOf(
            "<!doctype html><html></html>".toByteArray(),
            png(32, 32) { _, _ -> 0xFF000000.toInt() },          // too small to show anything
            png(256, 256) { _, _ -> 0xFF808080.toInt() },        // one flat colour
            png(900, 100) { x, _ -> if (x % 8 == 0) 0 else -1 }, // a banner strip, not a logo
        )) assertFalse(LogoQuality.rate(junk).acceptable)
    }
}
