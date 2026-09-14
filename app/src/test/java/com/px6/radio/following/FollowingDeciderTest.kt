package com.px6.radio.following

import com.px6.radio.following.FollowingDecider.Decision
import com.px6.radio.following.FollowingDecider.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the service-following decision logic: when to hand over to FM, when to come back, and the
 * debounce and hysteresis that stop it bouncing at the threshold.
 */
class FollowingDeciderTest {

    /** debounce = 2: two readings in a row before acting. threshold = 2 bars. */
    private fun decider() = FollowingDecider(debounce = 2)

    @Test
    fun `a single weak reading does not hand over`() {
        val d = decider()
        // One dip below threshold is noise — nothing happens yet.
        assertEquals(Decision.STAY, d.onSignal(bars = 1, threshold = 2, hasDabAlternative = false, hasLinkedFm = true))
        assertEquals(Mode.ON_DAB, d.mode)
    }

    @Test
    fun `two weak readings in a row hand over to FM`() {
        val d = decider()
        d.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true)
        assertEquals(Decision.HANDOVER, d.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true))
        assertEquals(Mode.ON_FM, d.mode)
    }

    @Test
    fun `a good reading between two weak ones resets the debounce`() {
        val d = decider()
        d.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true)   // weak
        d.onSignal(4, 2, hasDabAlternative = false, hasLinkedFm = true)   // good — resets
        assertEquals(Decision.STAY, d.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true)) // weak again, but only once
        assertEquals(Mode.ON_DAB, d.mode)
    }

    @Test
    fun `without a linked FM station a weak signal mutes instead of handing over`() {
        val d = decider()
        d.onSignal(0, 2, hasDabAlternative = false, hasLinkedFm = false)
        assertEquals(Decision.MUTE, d.onSignal(0, 2, hasDabAlternative = false, hasLinkedFm = false))
        assertEquals(Mode.ON_DAB, d.mode)  // stays on DAB, just silent
        assertEquals(true, d.muted)
    }

    @Test
    fun `recovering from a mute unmutes`() {
        val d = decider()
        d.onSignal(0, 2, hasDabAlternative = false, hasLinkedFm = false)
        d.onSignal(0, 2, hasDabAlternative = false, hasLinkedFm = false)   // MUTE
        assertEquals(Decision.UNMUTE, d.onSignal(4, 2, hasDabAlternative = false, hasLinkedFm = false))
        assertEquals(false, d.muted)
    }

    @Test
    fun `hysteresis - on FM, a signal exactly at threshold does not return`() {
        val d = decider()
        // Get onto FM.
        d.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true)
        d.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true)
        assertEquals(Mode.ON_FM, d.mode)

        // threshold is 2, so return needs 3+. Two readings *at* 2 must not switch back.
        assertEquals(Decision.STAY, d.onSignal(2, 2, hasDabAlternative = false, hasLinkedFm = true))
        assertEquals(Decision.STAY, d.onSignal(2, 2, hasDabAlternative = false, hasLinkedFm = true))
        assertEquals(Mode.ON_FM, d.mode)
    }

    @Test
    fun `on FM, two readings above the threshold return to DAB`() {
        val d = decider()
        d.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true)
        d.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true)   // -> FM

        assertEquals(Decision.STAY, d.onSignal(3, 2, hasDabAlternative = false, hasLinkedFm = true))
        assertEquals(Decision.RETURN_TO_DAB, d.onSignal(3, 2, hasDabAlternative = false, hasLinkedFm = true))
        assertEquals(Mode.ON_DAB, d.mode)
    }

    @Test
    fun `a dip below the return threshold resets the come-back debounce`() {
        val d = decider()
        d.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true)
        d.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true)   // -> FM

        d.onSignal(3, 2, hasDabAlternative = false, hasLinkedFm = true)   // one good
        d.onSignal(2, 2, hasDabAlternative = false, hasLinkedFm = true)   // dip — resets
        assertEquals(Decision.STAY, d.onSignal(3, 2, hasDabAlternative = false, hasLinkedFm = true)) // only one good again
        assertEquals(Mode.ON_FM, d.mode)
    }

    @Test
    fun `no ping-pong when the signal sits right at the threshold`() {
        val d = decider()
        // A signal that hovers at exactly the threshold, reading after reading, must settle — it
        // hands over once and then stays, never oscillating.
        var handovers = 0
        var returns = 0
        repeat(20) {
            when (d.onSignal(2, 2, hasDabAlternative = false, hasLinkedFm = true)) {
                Decision.HANDOVER -> handovers++
                Decision.RETURN_TO_DAB -> returns++
                else -> {}
            }
        }
        assertEquals(1, handovers)
        assertEquals(0, returns)
        assertEquals(Mode.ON_FM, d.mode)
    }

    @Test
    fun `reset returns to the initial state`() {
        val d = decider()
        d.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true)
        d.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true)   // -> FM
        d.reset()
        assertEquals(Mode.ON_DAB, d.mode)
        assertEquals(false, d.muted)
        // And it behaves fresh again.
        assertEquals(Decision.STAY, d.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true))
    }

    @Test
    fun `threshold is clamped into 1 to 4`() {
        val d = decider()
        // threshold 0 would mean "never weak"; it is clamped to 1, so 1 bar counts as weak.
        d.onSignal(1, 0, hasDabAlternative = false, hasLinkedFm = true)
        assertEquals(Decision.HANDOVER, d.onSignal(1, 0, hasDabAlternative = false, hasLinkedFm = true))
    }

    @Test
    fun `a DAB alternative is preferred over FM`() {
        val d = decider()
        d.onSignal(1, 2, hasDabAlternative = true, hasLinkedFm = true)
        // Weak, with both an alternative ensemble and an FM link: stay digital.
        assertEquals(Decision.SWITCH_DAB, d.onSignal(1, 2, hasDabAlternative = true, hasLinkedFm = true))
        assertEquals(Mode.ON_DAB, d.mode)   // never left DAB
    }

    @Test
    fun `switching DAB keeps watching and falls through to FM when alternatives run out`() {
        val d = decider()
        // First weak spell: an alternative exists -> switch, staying on DAB.
        d.onSignal(1, 2, hasDabAlternative = true, hasLinkedFm = true)
        assertEquals(Decision.SWITCH_DAB, d.onSignal(1, 2, hasDabAlternative = true, hasLinkedFm = true))
        assertEquals(Mode.ON_DAB, d.mode)

        // Still weak and now no untried alternative (engine exhausted them) -> hand to FM.
        d.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true)
        assertEquals(Decision.HANDOVER, d.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true))
        assertEquals(Mode.ON_FM, d.mode)
    }

    @Test
    fun `on a pure DAB device an alternative is still used before muting`() {
        val d = decider()
        d.onSignal(0, 2, hasDabAlternative = true, hasLinkedFm = false)
        assertEquals(Decision.SWITCH_DAB, d.onSignal(0, 2, hasDabAlternative = true, hasLinkedFm = false))

        // No alternative and no FM -> mute.
        d.onSignal(0, 2, hasDabAlternative = false, hasLinkedFm = false)
        assertEquals(Decision.MUTE, d.onSignal(0, 2, hasDabAlternative = false, hasLinkedFm = false))
    }

    // ---- the driver's choice of which fallback comes first -------------------------------------

    /** Default: FM before the stream. It is free, instant and needs no data. */
    @Test
    fun `by default a weak DAB hands over to FM even when a stream exists`() {
        val d = decider()
        d.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true, hasIpStream = true)
        assertEquals(
            Decision.HANDOVER,
            d.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true, hasIpStream = true),
        )
    }

    /** Chosen the other way round: the stream wins, for whoever would rather stay digital. */
    @Test
    fun `with the stream preferred a weak DAB hands over to IP even when FM is linked`() {
        val d = decider().apply { ipBeforeFm = true }
        d.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true, hasIpStream = true)
        assertEquals(
            Decision.HANDOVER_IP,
            d.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true, hasIpStream = true),
        )
    }

    /** Either way the preferred tier is skipped when it is not actually available. */
    @Test
    fun `the order falls through to whatever exists`() {
        val fmPreferred = decider()
        fmPreferred.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = false, hasIpStream = true)
        assertEquals(
            Decision.HANDOVER_IP,
            fmPreferred.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = false, hasIpStream = true),
        )

        val ipPreferred = decider().apply { ipBeforeFm = true }
        ipPreferred.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true, hasIpStream = false)
        assertEquals(
            Decision.HANDOVER,
            ipPreferred.onSignal(1, 2, hasDabAlternative = false, hasLinkedFm = true, hasIpStream = false),
        )
    }

    /** Another DAB ensemble outranks both orders — same programme, still digital, no data. */
    @Test
    fun `another DAB ensemble is tried before either fallback in both orders`() {
        listOf(false, true).forEach { ipFirst ->
            val d = decider().apply { ipBeforeFm = ipFirst }
            d.onSignal(1, 2, hasDabAlternative = true, hasLinkedFm = true, hasIpStream = true)
            assertEquals(
                "ipBeforeFm=$ipFirst",
                Decision.SWITCH_DAB,
                d.onSignal(1, 2, hasDabAlternative = true, hasLinkedFm = true, hasIpStream = true),
            )
        }
    }
}
