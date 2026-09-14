package com.px6.radio.ews

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The location code is recomputed on every GPS fix, so it has to be genuinely cheap — this pins that
 * down with a number instead of an assumption. The algorithm is a handful of double operations plus
 * six shift/mask rounds; there is no allocation beyond the six-element digit list.
 */
class EwsLocationCostTest {

    @Test
    fun derivingACodeIsNegligible() {
        // Warm up the JIT so the measurement is of the code, not of the interpreter.
        repeat(50_000) { EwsMatcher.codeFromCoordinates(51.5187412, -0.1434571) }

        val n = 1_000_000
        val start = System.nanoTime()
        var sink = 0
        for (i in 0 until n) {
            // Vary the input so nothing can be hoisted out of the loop.
            val c = EwsMatcher.codeFromCoordinates(50.0 + i % 1000 * 1e-5, 7.0 + i % 997 * 1e-5)
            sink += c!!.zone + c.digits[0]
        }
        val perCallNs = (System.nanoTime() - start).toDouble() / n
        println("codeFromCoordinates: %.0f ns per call (sink=%d)".format(perCallNs, sink))

        // Generous bound — the point is the order of magnitude, not a micro-benchmark. At one fix
        // every 20 s this is billions of times cheaper than the GPS fix that triggers it.
        assertTrue("expected well under 10 us per call, got $perCallNs ns", perCallNs < 10_000)
    }
}
