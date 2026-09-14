package com.px6.radio.audio

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the audio mutex. The one invariant that matters is that DAB and the analog tuner are never
 * audible at the same time — the bug this router exists to prevent.
 *
 * A fake models the real hardware just enough to catch that: DAB is audible when its volume is
 * above zero, the analog tuner is audible between an enter and the next exit. After every grant we
 * assert the two are never audible together.
 */
class AudioRouterTest {

    /** Records what the router did to the hardware and knows what is currently audible. */
    private class FakeAudio {
        var dabVolume = 0f
        var analogRouted = false
        val log = mutableListOf<String>()

        val dabAudible get() = dabVolume > 0f
        val bothAudible get() = dabAudible && analogRouted

        fun router() = AudioRouter(
            setDabVolume = { v -> dabVolume = v; log += "dab=$v" },
            enterAnalog = { analogRouted = true; log += "analog+" },
            exitAnalog = { analogRouted = false; log += "analog-" },
        )
    }

    /**
     * "Deliberately silenced" and "nothing has claimed the amplifier yet" must be distinguishable.
     *
     * silenceAll() used to set `current = null`, and null is exactly the sentinel the ViewModel reads
     * as "nobody owns the amp — grab it for DAB". On DAB, stop therefore un-stopped itself: the next
     * DabState emission (a DLS line, a slideshow image, a reception report — sub-second on air)
     * re-granted DAB and the sound came back while the UI still showed stopped.
     */
    @Test
    fun `silenceAll is distinguishable from never having routed`() = runTest {
        val hw = FakeAudio()
        val router = hw.router()

        assertNull("a fresh router has genuinely not routed anything yet", router.current)

        router.toDab { }
        router.silenceAll()

        assertFalse("nothing may be audible after silenceAll", hw.dabAudible)
        assertEquals(AudioSource.SILENCED, router.current)
    }

    @Test
    fun `dab grant silences the analog tuner`() = runTest {
        val hw = FakeAudio()
        val router = hw.router()

        router.toAnalog { }
        assertTrue("analog should be routed", hw.analogRouted)

        router.toDab { }
        assertFalse("analog must be off once DAB plays", hw.analogRouted)
        assertTrue("DAB must be audible", hw.dabAudible)
        assertEquals(AudioSource.DAB, router.current)
        assertFalse(hw.bothAudible)
    }

    @Test
    fun `analog grant silences DAB`() = runTest {
        val hw = FakeAudio()
        val router = hw.router()

        router.toDab { }
        assertTrue(hw.dabAudible)

        router.toAnalog { }
        assertFalse("DAB must be silenced once the analog tuner plays", hw.dabAudible)
        assertTrue(hw.analogRouted)
        assertEquals(AudioSource.ANALOG, router.current)
        assertFalse(hw.bothAudible)
    }

    @Test
    fun `repeated dab grant does not re-route`() = runTest {
        val hw = FakeAudio()
        val router = hw.router()

        router.toDab { }
        val exitsAfterFirst = hw.log.count { it == "analog-" }
        router.toDab { }
        router.toDab { }

        // Idempotent: the amplifier is taken off the analog tuner once, not on every tap.
        assertEquals(exitsAfterFirst, hw.log.count { it == "analog-" })
        assertEquals(AudioSource.DAB, router.current)
    }

    @Test
    fun `tune runs inside the grant`() = runTest {
        val hw = FakeAudio()
        val router = hw.router()
        var tuned = false

        router.toDab { tuned = true }
        assertTrue("the tune lambda must run", tuned)
    }

    @Test
    fun `a throwing tune does not break the mutex`() = runTest {
        val hw = FakeAudio()
        val router = hw.router()

        router.toDab { throw RuntimeException("tuner hiccup") }
        // The source is still granted, and the next switch still works.
        assertEquals(AudioSource.DAB, router.current)
        router.toAnalog { }
        assertEquals(AudioSource.ANALOG, router.current)
        assertFalse(hw.bothAudible)
    }

    @Test
    fun `setDabVolumeIfActive only affects DAB`() = runTest {
        val hw = FakeAudio()
        val router = hw.router()

        router.toAnalog { }
        router.setDabVolumeIfActive(1f)
        // On the analog tuner, a DAB fade command must not raise the DAB output.
        assertFalse("DAB must stay silent while the analog tuner plays", hw.dabAudible)

        router.toDab { }
        router.setDabVolumeIfActive(0.5f)
        assertEquals(0.5f, hw.dabVolume)
    }

    @Test
    fun `silenceAll leaves nothing audible`() = runTest {
        val hw = FakeAudio()
        val router = hw.router()

        router.toDab { }
        router.silenceAll()
        assertFalse(hw.dabAudible)
        assertFalse(hw.analogRouted)
        // Was assertNull — which pinned down the very bug it looked like it was guarding: null means
        // "not routed yet", and the ViewModel treats that as an invitation to grab the amp for DAB.
        assertEquals(AudioSource.SILENCED, router.current)
    }

    @Test
    fun `concurrent grants never leave both audible`() = runTest {
        val hw = FakeAudio()
        val router = hw.router()

        // Fire a burst of competing grants. The mutex must serialise them so the two sources are
        // never audible together at any settled point, and the final state is internally consistent.
        val jobs = (0 until 50).map { i ->
            async {
                if (i % 2 == 0) router.toDab { } else router.toAnalog { }
                assertFalse("both audible after grant #$i", hw.bothAudible)
            }
        }
        jobs.awaitAll()

        assertFalse(hw.bothAudible)
        when (router.current) {
            AudioSource.DAB -> assertTrue(hw.dabAudible)
            AudioSource.ANALOG -> assertTrue(hw.analogRouted)
            AudioSource.IP -> {}   // this test only grants DAB/ANALOG
            AudioSource.SILENCED -> {}   // silenceAll is not exercised here
            null -> {}
        }
    }
}
