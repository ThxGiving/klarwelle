package com.px6.radio.following

/**
 * The pure decision half of DAB→FM service following: signal readings in, one action out. No
 * tuners, no coroutines, no Android — so the tricky part (hysteresis and debounce against
 * ping-pong at the threshold) can be exercised exhaustively in a unit test.
 *
 * [ServiceFollowingEngine] owns an instance of this and carries out whatever it returns; the
 * engine keeps the hardware, this keeps the logic.
 */
class FollowingDecider(private val debounce: Int = 2) {

    /**
     * Which fallback to reach for first once DAB itself is too weak. Another DAB ensemble always
     * comes before either — staying digital on the tuner costs nothing and needs no data.
     */
    var ipBeforeFm: Boolean = false

    enum class Mode { ON_DAB, ON_FM, ON_IP }

    /** What the engine should do in response to a reading. */
    enum class Decision {
        /** Nothing changes. */
        STAY,

        /** DAB is too weak; follow the same programme to another ensemble, staying on DAB. */
        SWITCH_DAB,

        /** DAB is too weak and a linked FM station exists — hand over to FM. */
        HANDOVER,

        /** DAB too weak, no usable FM, but an IP simulcast exists — hand over to the internet stream. */
        HANDOVER_IP,

        /** DAB has recovered — switch back. */
        RETURN_TO_DAB,

        /** DAB is too weak and nothing can take over — go silent. */
        MUTE,

        /** Reception is back after a mute — sound again. */
        UNMUTE,
    }

    var mode = Mode.ON_DAB
        private set

    var muted = false
        private set

    private var belowCount = 0
    private var aboveCount = 0

    /**
     * Feed one reading.
     *
     * @param bars reception strength, 0..4.
     * @param threshold switch to FM at or below this many bars (1..4).
     * @param hasDabAlternative whether the same programme is available in another ensemble that
     *   has not been tried yet in this weak spell — tried first, so we stay on digital.
     * @param hasLinkedFm whether a linked FM station is available to hand over to.
     */
    fun onSignal(
        bars: Int,
        threshold: Int,
        hasDabAlternative: Boolean,
        hasLinkedFm: Boolean,
        hasIpStream: Boolean = false,
        fmPoor: Boolean = false,
    ): Decision {
        val t = threshold.coerceIn(1, 4)
        when (mode) {
            Mode.ON_DAB -> {
                if (bars <= t) {
                    // Weak for `debounce` readings in a row before acting — a single dip is noise.
                    if (++belowCount >= debounce) {
                        belowCount = 0
                        // Prefer another DAB ensemble (stay digital), then FM, then the IP simulcast,
                        // then silence. FM before IP: it is free, instant and needs no data.
                        // Another DAB ensemble first either way — same programme, still digital, no
                        // data. After that the driver's choice decides: FM is free and instant, the
                        // stream stays digital but costs data and takes a moment to start.
                        val toFm = {
                            mode = Mode.ON_FM; aboveCount = 0
                            if (muted) muted = false
                            Decision.HANDOVER
                        }
                        val toIp = {
                            mode = Mode.ON_IP; aboveCount = 0
                            if (muted) muted = false
                            Decision.HANDOVER_IP
                        }
                        val first = if (ipBeforeFm) hasIpStream else hasLinkedFm
                        val second = if (ipBeforeFm) hasLinkedFm else hasIpStream
                        return when {
                            hasDabAlternative -> {
                                if (muted) muted = false
                                Decision.SWITCH_DAB   // mode stays ON_DAB
                            }
                            first -> if (ipBeforeFm) toIp() else toFm()
                            second -> if (ipBeforeFm) toFm() else toIp()
                            else -> if (!muted) {
                                muted = true
                                Decision.MUTE
                            } else Decision.STAY
                        }
                    }
                } else {
                    belowCount = 0
                    if (muted) {
                        muted = false
                        return Decision.UNMUTE
                    }
                }
            }
            Mode.ON_FM -> {
                // Hysteresis: come back to DAB only once it is a notch above the switch threshold,
                // so a signal hovering at the line does not bounce.
                val upper = (t + 1).coerceAtMost(4)
                if (bars >= upper) {
                    if (++aboveCount >= debounce) {
                        aboveCount = 0
                        belowCount = 0
                        mode = Mode.ON_DAB
                        return Decision.RETURN_TO_DAB
                    }
                } else {
                    aboveCount = 0
                    // DAB still weak, and the FM we handed to turned out poor (no RDS lock / weak
                    // field) while an IP simulcast exists: escalate FM->IP for constant digital
                    // quality instead of sitting on noisy FM. One-way (no downgrade back to FM) to
                    // avoid ping-pong; we only leave IP when DAB itself recovers.
                    if (fmPoor && hasIpStream) {
                        mode = Mode.ON_IP
                        return Decision.HANDOVER_IP
                    }
                }
            }
            Mode.ON_IP -> {
                val upper = (t + 1).coerceAtMost(4)
                if (bars >= upper) {
                    if (++aboveCount >= debounce) {
                        aboveCount = 0
                        belowCount = 0
                        mode = Mode.ON_DAB
                        return Decision.RETURN_TO_DAB
                    }
                } else {
                    aboveCount = 0
                }
            }
        }
        return Decision.STAY
    }

    /** Back to the initial state (following switched off, or FM lost). */
    fun reset() {
        mode = Mode.ON_DAB
        muted = false
        belowCount = 0
        aboveCount = 0
    }
}
