package com.px6.radio.model

import java.util.Locale

/**
 * Comparison form of a station name: lower-case, letters and digits only, so "WDR 2", "wdr2" and
 * "WDR-2" collapse onto the same key. The one place this rule lives — logo lookup, RadioDNS SI
 * matching, radio-browser simulcast search and DAB<->FM name matching all compare through it.
 */
fun String.stationNameKey(): String = lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
