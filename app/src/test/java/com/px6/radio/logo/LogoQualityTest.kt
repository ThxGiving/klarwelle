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

    /** The Fortuna case: a tiny icon blown up — only soft ramps, no real edge. */
    private fun upscaled(size: Int): ByteArray = png(size, size) { x, y ->
        val v = (128 + 120.0 * kotlin.math.sin(x * 2.0 * Math.PI / size) *
            kotlin.math.sin(y * 2.0 * Math.PI / size)).toInt().coerceIn(0, 255)
        0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
    }

    private fun png(w: Int, h: Int, pixel: (Int, Int) -> Int): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) bmp.setPixel(x, y, pixel(x, y))
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    @Test fun `a sharp square logo passes`() {
        assertTrue(LogoQuality.isUsable(sharp(256)))
    }

    @Test fun `a tiny favicon is rejected`() {
        assertFalse(LogoQuality.isUsable(sharp(48)))
    }

    @Test fun `a wide press photo is rejected when a square is required`() {
        val wide = sharp(560, 292)
        assertFalse(LogoQuality.isUsable(wide))
        assertTrue("hand-checked artwork may be wide", LogoQuality.isUsable(wide, requireSquare = false))
    }

    @Test fun `an upscaled icon is rejected although it is large`() {
        assertFalse(LogoQuality.isUsable(upscaled(512)))
    }

    @Test fun `html or svg served as a favicon is rejected`() {
        assertFalse(LogoQuality.isUsable("<!doctype html><html><body>404</body></html>".toByteArray()))
        assertFalse(LogoQuality.isUsable("<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray()))
    }

    @Test fun `a blank picture is no logo`() {
        assertFalse(LogoQuality.isUsable(png(256, 256) { _, _ -> 0xFF808080.toInt() }))
    }

    @Test fun `sharpness separates artwork from upscale`() {
        val a = android.graphics.BitmapFactory.decodeByteArray(sharp(256), 0, sharp(256).size)
        val up = upscaled(512)
        val b = android.graphics.BitmapFactory.decodeByteArray(up, 0, up.size)
        assertTrue(LogoQuality.edgeSharpness(a) >= LogoQuality.MIN_EDGE)
        assertTrue(LogoQuality.edgeSharpness(b) < LogoQuality.MIN_EDGE)
        assertEquals(40, LogoQuality.MIN_EDGE)
    }
}
