package com.px6.radio.ews

/**
 * Receiver-side alert matching for the DAB Emergency Warning System (ASA), per ETSI TS 104 089
 * clause 7.5 and annexes A/F. Pure Kotlin, no Android/omri types, so it is fully JVM-unit-tested
 * against the worked examples in the standard — this is safety-relevant logic and must be exact.
 *
 * Scope: location matching (§7.5.4) and stage matching (§7.5.3, Table 1, "audio mode" — our head
 * unit is always awake). Receivability (§7.5.2) needs the ensemble/MCI context and is handled by the
 * caller. Sub-codes (a finer subdivision than the zone+digits the §7.5.4 algorithm compares) are not
 * used for matching — ignoring them can only widen the matched area, i.e. err toward playing an
 * emergency alert, never toward suppressing one.
 */
object EwsMatcher {

    // Stage values mirror DabEwsAlert / the native Stage enum. -1 = no Status field.
    const val STAGE_NONE = -1
    const val STAGE_TEST = 7

    /** A DAB location code as zone (0..41) + a list of 4-bit digits (most significant first). The
     *  receiver code carries the full six digits; alert codes carry 1..6. */
    data class Code(val zone: Int, val digits: List<Int>)

    /**
     * Parse the 12-symbol presentation code the user enters (annex A) into zone + six digits, and
     * verify its modulo-61 checksum. Hyphens and spaces are ignored. Returns null if it is not 12
     * symbols from '1'..'8', or the checksum fails.
     *
     * Reverse of annex A.2: symbol-1 → octal digit; twelve octal digits → a 36-bit integer whose top
     * 30 bits are the location code and bottom 6 bits the checksum (= locationCode mod 61); the top 6
     * bits of the 30 are the zone and the low 24 are six 4-bit digits.
     */
    fun parseReceiverCode(presentation: String): Code? {
        val symbols = presentation.filter { it in '1'..'8' }
        if (symbols.length != 12) return null
        var value36 = 0L
        for (c in symbols) value36 = value36 * 8 + (c - '1')   // each symbol-1 is an octal digit 0..7
        val value30 = value36 shr 6
        val checksum = (value36 and 0x3F).toInt()
        if ((value30 % 61).toInt() != checksum) return null
        val zone = ((value30 shr 24) and 0x3F).toInt()
        val digits = (0 until 6).map { i -> ((value30 shr (20 - 4 * i)) and 0xF).toInt() }
        return Code(zone, digits)
    }

    /**
     * Parse an alert location code produced by the native decoder in the form "zone:digithex", e.g.
     * "1:92c" for Z1 digits 9,2,12 (the spec's Z1:92C). Returns null on malformed input.
     */
    fun parseAlertCode(s: String): Code? {
        val colon = s.indexOf(':')
        if (colon <= 0) return null
        val zone = s.substring(0, colon).toIntOrNull() ?: return null
        val hex = s.substring(colon + 1)
        val digits = hex.map { c -> Character.digit(c, 16).also { if (it < 0) return null } }
        return Code(zone, digits)
    }

    /**
     * Location match (§7.5.4): the same zone, and the alert's digits equal the receiver's digits
     * truncated to the alert's digit count (left-aligned). An alert code is always at least one digit.
     */
    fun locationMatches(receiver: Code, alert: Code): Boolean {
        if (alert.zone != receiver.zone) return false
        if (alert.digits.isEmpty() || alert.digits.size > receiver.digits.size) return false
        return receiver.digits.subList(0, alert.digits.size) == alert.digits
    }

    /**
     * Stage match (§7.5.3, Table 1, audio mode, no dismiss settings engaged). Every Level 1/2 stage
     * is positive; the Test stage is positive only when the user has enabled test alerts (otherwise a
     * normal receiver ignores it); no Status field is never a match.
     */
    fun stageMatches(stage: Int, testAlertsEnabled: Boolean): Boolean = when (stage) {
        STAGE_NONE -> false
        STAGE_TEST -> testAlertsEnabled
        else -> true
    }

    /**
     * Whole matching decision for a Trigger-phase alert (§7.5), except receivability which the caller
     * has already confirmed. [alertLocationCodes] are the native "zone:hex" strings; empty means the
     * whole ensemble coverage area (§7.5.4 → automatic positive). [receiverCode] is the user's parsed
     * location code, or null if none entered — in which case only whole-ensemble alerts match (§7.2.3).
     */
    fun shouldPlay(
        stage: Int,
        testAlertsEnabled: Boolean,
        alertLocationCodes: List<String>,
        receiverCode: Code?,
    ): Boolean = shouldPlay(stage, testAlertsEnabled, alertLocationCodes, listOfNotNull(receiverCode))

    /**
     * Same decision for a receiver that has SEVERAL location codes (home, work, a second address).
     * An alert plays if any alert area covers any of them. Widening the receiver's area can only
     * cause an extra alert to be presented, never suppress one — the safe direction for §7.5.4.
     */
    fun shouldPlay(
        stage: Int,
        testAlertsEnabled: Boolean,
        alertLocationCodes: List<String>,
        receiverCodes: List<Code>,
    ): Boolean {
        if (!stageMatches(stage, testAlertsEnabled)) return false
        if (alertLocationCodes.isEmpty()) return true            // whole-ensemble alert
        if (receiverCodes.isEmpty()) return false                // §7.2.3: no code → whole-ensemble only
        return alertLocationCodes.any { s ->
            parseAlertCode(s)?.let { a -> receiverCodes.any { locationMatches(it, a) } } == true
        }
    }

    /**
     * The DAB location code for a WGS84 position, per annex F — the receiver can work out its own
     * code instead of the user typing one in. In a car this is the only correct answer: a fixed home
     * code stops describing where you are the moment you drive off.
     *
     * Annex F builds a hierarchy of spherical rectangles. Coordinates are first mapped to positive
     * numbers: Southerly Extent SE = 90 - latitude (0 at the north pole, 180 at the south), and
     * Easterly Extent EE = longitude, +360 if negative. The globe is 42 zones: 40 banded ones of
     * 36 deg x 36 deg between 72N and 72S, plus a polar cap at each end.
     *
     * Within a banded zone the position inside the cell is expressed as two 12-bit fractions — south
     * and east — and those are INTERLEAVED two bits at a time (south first) into a 24-bit value whose
     * six nibbles are the six digits. Each digit therefore narrows the area by a factor of 16, which
     * is exactly what the left-aligned prefix match in [locationMatches] compares.
     *
     * Returns null inside the polar zones: their first digit uses a different, circular division
     * (annex F.5) that is not implemented here. Refusing is the safe answer — a wrong code would
     * silently mismatch real alerts. Europe is far from that boundary.
     */
    fun codeFromCoordinates(latitude: Double, longitude: Double): Code? {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        val se = 90.0 - latitude
        val ee = if (longitude < 0) longitude + 360.0 else longitude
        if (se < 18.0 || se >= 162.0) return null            // polar zones — see F.5
        val band = (se - 18.0) / 36.0
        val col = ee / 36.0
        val zone = 10 * band.toInt() + col.toInt() + 1
        // 12-bit position within the zone, south and east.
        val sc = ((band - band.toInt()) * 4096).toInt().coerceIn(0, 4095)
        val ec = ((col - col.toInt()) * 4096).toInt().coerceIn(0, 4095)
        // Interleave 2 bits at a time, most significant pair from SC.
        var cc = 0
        for (pair in 5 downTo 0) {
            cc = (cc shl 2) or ((sc shr (pair * 2)) and 0b11)
            cc = (cc shl 2) or ((ec shr (pair * 2)) and 0b11)
        }
        val digits = (0 until 6).map { i -> (cc shr (20 - 4 * i)) and 0xF }
        return Code(zone, digits)
    }

    /**
     * The 12-symbol presentation code (annex A.3) for a [Code] — the exact inverse of
     * [parseReceiverCode], and the only form a person ever sees or types. The internal "Z1:91BB82"
     * notation is what the broadcast carries; showing that to the user next to codes they entered
     * as "2366-7443-8484" mixed two notations for one thing.
     *
     * Symbols are the digits 1..8: each octal digit (0..7) is rendered by adding one, so a 9 or a 0
     * cannot occur.
     */
    fun presentationOf(code: Code): String {
        var value30 = code.zone.toLong() and 0x3F
        for (d in code.digits) value30 = (value30 shl 4) or (d.toLong() and 0xF)
        val value36 = (value30 shl 6) or (value30 % 61)
        return (11 downTo 0)
            .map { i -> ('1' + ((value36 shr (i * 3)) and 0x7).toInt()) }
            .joinToString("")
    }

    /**
     * A presentation code as annex A.3 prints it: "three groups of four symbols, separated with the
     * hyphen character" — 2366-7443-8484 rather than 236674438484. For display only; the parser
     * ignores hyphens and spaces, so stored values need not carry them. Anything that is not twelve
     * symbols long is passed through untouched rather than mangled.
     */
    fun grouped(presentation: String): String {
        val bare = presentation.filter { it in '1'..'8' }
        if (bare.length != 12) return presentation
        return bare.chunked(4).joinToString("-")
    }

    /** Parse a whole list of entered presentation codes, dropping any that fail their checksum. */
    fun parseReceiverCodes(presentations: List<String>): List<Code> =
        presentations.mapNotNull { parseReceiverCode(it) }
}
