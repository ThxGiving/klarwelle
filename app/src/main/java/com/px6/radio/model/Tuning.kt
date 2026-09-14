package com.px6.radio.model

/**
 * How a frequency band is actually tuned.
 *
 * The three bands are not variations of one another — they differ in range, in raster, and DAB
 * differs in kind: it has no continuum at all but a fixed table of channels. A single evenly
 * divided scale would misrepresent it, so the manual view asks this profile what to draw and what
 * a step means.
 *
 * FM and AM rasters are also region-dependent, which is why [forBand] takes the region setting.
 */
sealed interface TuningProfile {

    /** Lowest and highest tunable frequency in kHz. */
    val minKhz: Int
    val maxKhz: Int

    /** Human-readable value for a frequency, e.g. "98,5 MHz" or "1422 kHz". */
    fun format(khz: Int): String

    /**
     * A continuous band: every multiple of [stepKhz] between the limits is tunable, so the scale
     * can be drawn evenly and dragged freely.
     */
    data class Continuous(
        override val minKhz: Int,
        override val maxKhz: Int,
        val stepKhz: Int,
        private val megahertz: Boolean,
    ) : TuningProfile {

        /** Snaps a raw frequency onto the raster — 98,537 MHz is not a station, 98,5 is. */
        fun snap(khz: Int): Int {
            val steps = Math.round((khz - minKhz).toFloat() / stepKhz)
            return (minKhz + steps * stepKhz).coerceIn(minKhz, maxKhz)
        }

        override fun format(khz: Int): String =
            if (megahertz) "%.1f MHz".format(khz / 1000f) else "$khz kHz"
    }

    /**
     * A table of named channels. DAB Band III has 38 of them at uneven spacing, so positions on
     * the scale come from the table rather than from arithmetic.
     */
    data class Channels(val channels: List<DabChannel>) : TuningProfile {

        override val minKhz: Int get() = channels.first().khz
        override val maxKhz: Int get() = channels.last().khz

        fun nearest(khz: Int): DabChannel = channels.minBy { kotlin.math.abs(it.khz - khz) }

        override fun format(khz: Int): String = nearest(khz).let { "${it.name} · %.3f MHz".format(it.khz / 1000f) }
    }

    companion object {

        /**
         * [region] is the region setting ("Europa", "OIRT", "US").
         *
         * The FM and AM figures are the ones the head unit's own tuner uses, taken from the ROM
         * radio service (`freqInfo = {{87500000, 108000000, 50000}, {522000, 1620000, 9000}}`,
         * in hertz) and from the factory frequency groups, which always pair an FM range with an
         * AM one. Guessing the textbook values would have put the raster at 100 kHz — the tuner
         * really steps in 50.
         */
        fun forBand(band: Band, region: String): TuningProfile = when (band) {
            Band.DAB -> Channels(DAB_BAND_III)
            Band.FM -> when (region) {
                "OIRT" -> Continuous(65_000, 74_000, 50, megahertz = true)
                "US" -> Continuous(87_500, 107_900, 200, megahertz = true)
                else -> Continuous(87_500, 108_000, 50, megahertz = true)
            }
            // Medium wave: 9 kHz spacing in ITU regions 1 and 3, 10 kHz in the Americas.
            Band.AM -> when (region) {
                "US" -> Continuous(530, 1_710, 10, megahertz = false)
                else -> Continuous(522, 1_620, 9, megahertz = false)
            }
            // Internet radio isn't tuned — it has no frequency scale. Return the DAB channel table as
            // an inert placeholder: it is not [Continuous], so the analog stepping/scale logic (which
            // casts to Continuous) simply no-ops for IP. No IP UI ever reads this.
            Band.IP -> Channels(DAB_BAND_III)
        }
    }
}

/** One DAB channel: its short name and centre frequency in kHz. */
data class DabChannel(val name: String, val khz: Int)

/**
 * DAB Band III as used across Europe (ETSI EN 300 401): 38 channels from 5A to 13F. The spacing is
 * 1712 kHz within a block but jumps between blocks, and 13D–13F break the pattern again — which is
 * exactly why this is a table and not a formula.
 */
val DAB_BAND_III: List<DabChannel> = listOf(
    DabChannel("5A", 174_928), DabChannel("5B", 176_640),
    DabChannel("5C", 178_352), DabChannel("5D", 180_064),
    DabChannel("6A", 181_936), DabChannel("6B", 183_648),
    DabChannel("6C", 185_360), DabChannel("6D", 187_072),
    DabChannel("7A", 188_928), DabChannel("7B", 190_640),
    DabChannel("7C", 192_352), DabChannel("7D", 194_064),
    DabChannel("8A", 195_936), DabChannel("8B", 197_648),
    DabChannel("8C", 199_360), DabChannel("8D", 201_072),
    DabChannel("9A", 202_928), DabChannel("9B", 204_640),
    DabChannel("9C", 206_352), DabChannel("9D", 208_064),
    DabChannel("10A", 209_936), DabChannel("10B", 211_648),
    DabChannel("10C", 213_360), DabChannel("10D", 215_072),
    DabChannel("11A", 216_928), DabChannel("11B", 218_640),
    DabChannel("11C", 220_352), DabChannel("11D", 222_064),
    DabChannel("12A", 223_936), DabChannel("12B", 225_648),
    DabChannel("12C", 227_360), DabChannel("12D", 229_072),
    DabChannel("13A", 230_784), DabChannel("13B", 232_496),
    DabChannel("13C", 234_208), DabChannel("13D", 235_776),
    DabChannel("13E", 237_488), DabChannel("13F", 239_200),
)
