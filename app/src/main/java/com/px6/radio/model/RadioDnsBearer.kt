package com.px6.radio.model

/**
 * The RadioDNS **gcc** (global country code) for a bearer: the country-id nibble of the service
 * identifier plus the Extended Country Code, as one hex string (Germany: "de0", "1e0", …).
 *
 * The ECC alone does not identify a country — 0xE0 covers Germany as well as Italy and Ireland —
 * only the pair is unique, which is why RadioDNS keys on both. Every bearer this app builds goes
 * through here; nothing hardcodes "de0".
 */
object RadioDnsBearer {

    /** Germany's ECC — the fallback when no ensemble has told us where we are. */
    const val DEFAULT_ECC = 0xE0

    /** gcc for a DAB service: top nibble of the SId + ECC. */
    fun dabGcc(sid: Int, ecc: Int?): String = gcc(sid ushr 12, ecc)

    /** gcc for an FM station: top nibble of the RDS PI + ECC. RDS carries the ECC only in group 1A,
     *  which this tuner does not expose — callers pass the ECC learned from DAB (see [Station.ecc])
     *  or the country the app is otherwise sure of. */
    fun fmGcc(pi: Int, ecc: Int?): String = gcc(pi ushr 12, ecc)

    private fun gcc(countryNibble: Int, ecc: Int?): String =
        Integer.toHexString(countryNibble and 0xF) + "%02x".format((ecc ?: DEFAULT_ECC) and 0xFF)
}
