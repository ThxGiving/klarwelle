package com.px6.radio.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.px6.radio.model.Band
import com.px6.radio.model.FallbackOrder
import com.px6.radio.model.PresetSlot
import com.px6.radio.model.Settings
import com.px6.radio.model.SortMode
import com.px6.radio.model.Station
import com.px6.radio.model.StepMode
import com.px6.radio.model.ThemeMode
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore("radio")

/** Field separator for packed string-set rows (fixed names, analog stations). */
private const val SEP = '\u0001'

/**
 * Minimal analog (FM/AM) station record. FM/AM stations exist only in memory otherwise (there is no
 * service list to fetch — the band is walked and what answers is written down), so a saved FM tile
 * would be dead after a restart until a rescan re-created its station. Persisting freq → name/PI —
 * exactly what the factory radio does (name keyed by frequency) — makes saved tiles work at once.
 */
data class PersistedAnalog(val band: Band, val khz: Int, val name: String, val pi: Int?)

/** Persisted user data (survives app/ignition restart). */
data class Persisted(
    val settings: Settings,
    val presets: Map<Int, String>,
    val sortMode: SortMode,
    val fixedNames: Map<String, String>,
    /** Learned FM frequency→name memory (survives a rescan; HCT4Radio's saveFrequencyPsn). */
    val fmNames: Map<Int, String>,
    val analogStations: List<PersistedAnalog>,
    /**
     * The scanned DAB+ services (full display record) — so the list and DAB favourite tiles are
     * there the instant the app opens, without waiting for a fresh ensemble scan. omri persists its
     * own service DB too (for tuning), but that only surfaces once the tuner reports in; this keeps
     * the UI populated immediately and independent of that timing.
     */
    val dabStations: List<Station>,
    /** The user's internet-radio stations (band=IP), full record incl. stream URL + homepage. */
    val ipStations: List<Station>,
    /** Learned per-stream loudness boost (stream URL -> LoudnessEnhancer target mB; 0 = loud/natural). */
    val ipGains: Map<String, Int>,
    /**
     * RadioDNS IP simulcast addresses (station id -> stream URL) for the DAB/FM->internet fallback.
     * Persisted because the lookup needs a working network at exactly the moment it runs: without
     * this the map started empty on every boot and the fallback was silently unavailable until some
     * later run happened to succeed. A stale URL costs nothing — the player falls back on error.
     */
    val radioDnsStreams: Map<String, String>,
    /** Whether the example internet seeds were already applied once — so a user who deletes them all
     *  doesn't get them re-seeded on the next start. False for stores written before the IP feature. */
    val ipSeeded: Boolean,
    /** The seed set version last applied; a newer app version merges in seeds added since. 0 = unknown. */
    val ipSeedVersion: Int,
    /** Station played when the app was last closed — auto-selected + played again on next start. */
    val lastPlayedId: String?,
    /** Last-heard station per band — restored when switching bands, across restarts. */
    val lastStationPerBand: Map<Band, String>,
)

/**
 * Preferences-DataStore persistence for settings, favorites, presets and list mode.
 *
 * [read] returns null until the store has been seeded once, so the ViewModel can keep its initial
 * (demo) data on first run and seed from it. Every change writes the full small state back.
 */
class RadioStore(context: Context) {

    private val ds = context.applicationContext.dataStore

    /** 18 station buttons, shown in three groups of six (Discover Pro behaviour). */
    val presetIndices = 1..18

    /** Whether we already harvested RadioVIS bearers from broadcasters' SI once (so the SI is not
     *  re-fetched on every start). Set only after a successful harvest. */
    suspend fun visHarvested(): Boolean = ds.data.first()[K.IP_VIS_HARVESTED] ?: false
    suspend fun markVisHarvested() { ds.edit { it[K.IP_VIS_HARVESTED] = true } }
    /** Reset so the next IP play re-harvests (manual "RadioDNS aktualisieren"). */
    suspend fun clearVisHarvested() { ds.edit { it[K.IP_VIS_HARVESTED] = false } }

    /**
     * EIds (hex) of DAB ensembles observed to carry EWS/ASA signalling (ETSI TS 104 089 §7.2.3).
     * Persisted so the "ASA" station badge and the idle-tuner parking work from the first second
     * after a restart instead of only after another ensemble scan. Rebuilt from scratch by each scan
     * (see DabController.tunerScanStarted), so a mux that stops carrying EWS drops out again.
     */
    suspend fun ewsEnsembleIds(): Set<Int> =
        (ds.data.first()[K.EWS_ENSEMBLES] ?: emptySet()).mapNotNull { it.toIntOrNull(16) }.toSet()

    suspend fun saveEwsEnsembleIds(ids: Set<Int>) {
        ds.edit { e -> e[K.EWS_ENSEMBLES] = ids.map { Integer.toHexString(it) }.toSet() }
    }

    suspend fun read(): Persisted? {
        val p = ds.data.first()
        if (p[K.SEEDED] != true) return null
        val settings = Settings(
            preferDab = p[K.PREFER_DAB] ?: true,
            serviceFollowing = p[K.SERVICE_FOLLOWING] ?: true,
            handoverThreshold = p[K.HANDOVER] ?: 2,
            softFade = p[K.SOFT_FADE] ?: true,
            diagnostics = p[K.DIAGNOSTICS] ?: false,
            steeringWheelKeys = p[K.SWC] ?: true,
            fmRegion = p[K.FM_REGION] ?: "Europa",
            themeMode = runCatching { ThemeMode.valueOf(p[K.THEME_MODE] ?: "") }
                .getOrDefault(ThemeMode.DARK),
            frontendId = p[K.FRONTEND] ?: "split",
            autoRotate = p[K.AUTO_ROTATE] ?: false,
            skinDarkId = p[K.SKIN_DARK] ?: "modern-dark",
            skinLightId = p[K.SKIN_LIGHT] ?: "modern-light",
            accentId = p[K.ACCENT] ?: "red",
            showSignalStrength = p[K.SHOW_SIGNAL] ?: false,
            stepMode = runCatching { StepMode.valueOf(p[K.STEP_MODE] ?: "") }
                .getOrDefault(StepMode.RECEIVABLE),
            dabDabFollowing = p[K.DAB_DAB] ?: true,
            autoAssignLogos = p[K.AUTO_LOGOS] ?: true,
            radioDnsEnabled = p[K.RADIO_DNS] ?: true,
            radioVisEnabled = p[K.RADIOVIS] ?: true,
            radioVisSlideshow = p[K.RADIOVIS_SLIDES] ?: true,
            fallbackOrder = if (p[K.FALLBACK_IP_FIRST] == true) FallbackOrder.IP_FIRST
                else FallbackOrder.FM_FIRST,
            ipFallbackEnabled = p[K.IP_FALLBACK] ?: true,
            ipFallbackWifiOnly = p[K.IP_FALLBACK_WIFI] ?: false,
            autoFetchLogos = p[K.AUTO_FETCH_LOGOS] ?: true,
            mediaBroadcastLogos = p[K.MEDIA_BROADCAST_LOGOS] ?: true,
            normalizeStreamLoudness = p[K.NORMALIZE_LOUDNESS] ?: true,
            uiSounds = p[K.UI_SOUNDS] ?: false,
            asaAttentionTone = p[K.ASA_ATTENTION_TONE] ?: true,
            miniPlayerOverlay = p[K.MINI_PLAYER_OVERLAY] ?: true,
            asaEnabled = p[K.ASA_ENABLED] ?: true,
            asaTestAlerts = p[K.ASA_TEST_ALERTS] ?: false,
            // Migration: before v2.7.40 there was exactly one code under ASA_LOCATION_CODE. Carry it
            // over as the first entry so nobody has to re-enter their home address.
            asaLocationCodes = (p[K.ASA_LOCATION_CODES]
                ?: p[K.ASA_LOCATION_CODE].orEmpty())
                .split(',').map { it.trim() }.filter { it.isNotEmpty() },
            asaFollowGps = p[K.ASA_FOLLOW_GPS] ?: false,
        )
        val presets = presetIndices.mapNotNull { i -> p[K.preset(i)]?.let { i to it } }.toMap()
        val sortMode = runCatching { SortMode.valueOf(p[K.SORT_MODE] ?: "") }.getOrDefault(SortMode.GROUP)
        // Frozen names are stored as "id\u0001name" rows in a string set.
        val fixedNames = (p[K.FIXED_NAMES] ?: emptySet()).mapNotNull { row ->
            row.split('\u0001', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        // Learned FM frequency→name memory ("khz{SEP}name" rows).
        val fmNames = (p[K.FM_NAMES] ?: emptySet()).mapNotNull { row ->
            val f = row.split(SEP, limit = 2)
            val khz = f.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val name = f.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            khz to name
        }.toMap()
        // Analog stations as "band{SEP}khz{SEP}name{SEP}pi" rows (pi may be empty).
        val analogStations = (p[K.ANALOG] ?: emptySet()).mapNotNull { row ->
            val f = row.split(SEP)
            if (f.size < 3) return@mapNotNull null
            val band = runCatching { Band.valueOf(f[0]) }.getOrNull() ?: return@mapNotNull null
            val khz = f[1].toIntOrNull() ?: return@mapNotNull null
            PersistedAnalog(band, khz, f[2], f.getOrNull(3)?.toIntOrNull())
        }
        val dabStations = decodeDab(p[K.DAB_STATIONS])
        val ipStations = decodeIp(p[K.IP_STATIONS])
        val ipGains = (p[K.IP_GAINS] ?: emptySet()).mapNotNull { row ->
            val f = row.split(SEP, limit = 2)
            val g = f.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            f[0] to g
        }.toMap()
        // Same SEP-joined "key<sep>value" shape as ipGains; a URL never contains the separator.
        val radioDnsStreams = (p[K.RADIODNS_STREAMS] ?: emptySet()).mapNotNull { row ->
            val k = row.substringBefore(SEP, "")
            val v = row.substringAfter(SEP, "")
            if (k.isEmpty() || v.isEmpty()) null else k to v
        }.toMap()
        val ipSeeded = p[K.IP_SEEDED] ?: false
        val ipSeedVersion = p[K.IP_SEED_VERSION] ?: 0
        val lastPlayedId = p[K.LAST_PLAYED]
        val lastPerBand = (p[K.LAST_PER_BAND] ?: emptySet()).mapNotNull { row ->
            val f = row.split(SEP, limit = 2)
            if (f.size < 2) return@mapNotNull null
            val band = runCatching { Band.valueOf(f[0]) }.getOrNull() ?: return@mapNotNull null
            band to f[1]
        }.toMap()
        return Persisted(
            settings, presets, sortMode, fixedNames, fmNames, analogStations, dabStations,
            ipStations, ipGains, radioDnsStreams, ipSeeded, ipSeedVersion, lastPlayedId, lastPerBand,
        )
    }

    suspend fun write(
        settings: Settings,
        presets: List<PresetSlot>,
        sortMode: SortMode,
        fixedNames: Map<String, String>,
        fmNames: Map<Int, String>,
        analogStations: List<PersistedAnalog>,
        dabStations: List<Station>,
        ipStations: List<Station>,
        ipGains: Map<String, Int>,
        radioDnsStreams: Map<String, String>,
        ipSeedVersion: Int,
        lastPlayedId: String?,
        lastStationPerBand: Map<Band, String>,
    ) {
        ds.edit { e ->
            e[K.SEEDED] = true
            e[K.PREFER_DAB] = settings.preferDab
            e[K.SERVICE_FOLLOWING] = settings.serviceFollowing
            e[K.HANDOVER] = settings.handoverThreshold
            e[K.SOFT_FADE] = settings.softFade
            e[K.DIAGNOSTICS] = settings.diagnostics
            e[K.SWC] = settings.steeringWheelKeys
            e[K.FM_REGION] = settings.fmRegion
            e[K.THEME_MODE] = settings.themeMode.name
            e[K.FRONTEND] = settings.frontendId
            e[K.AUTO_ROTATE] = settings.autoRotate
            e[K.SKIN_DARK] = settings.skinDarkId
            e[K.SKIN_LIGHT] = settings.skinLightId
            e[K.ACCENT] = settings.accentId
            e[K.SHOW_SIGNAL] = settings.showSignalStrength
            e[K.STEP_MODE] = settings.stepMode.name
            e[K.DAB_DAB] = settings.dabDabFollowing
            e[K.AUTO_LOGOS] = settings.autoAssignLogos
            e[K.RADIO_DNS] = settings.radioDnsEnabled
            e[K.RADIOVIS] = settings.radioVisEnabled
            e[K.RADIOVIS_SLIDES] = settings.radioVisSlideshow
            e[K.FALLBACK_IP_FIRST] = settings.fallbackOrder == FallbackOrder.IP_FIRST
            e[K.IP_FALLBACK] = settings.ipFallbackEnabled
            e[K.IP_FALLBACK_WIFI] = settings.ipFallbackWifiOnly
            e[K.AUTO_FETCH_LOGOS] = settings.autoFetchLogos
            e[K.MEDIA_BROADCAST_LOGOS] = settings.mediaBroadcastLogos
            e[K.NORMALIZE_LOUDNESS] = settings.normalizeStreamLoudness
            e[K.UI_SOUNDS] = settings.uiSounds
            e[K.ASA_ATTENTION_TONE] = settings.asaAttentionTone
            e[K.MINI_PLAYER_OVERLAY] = settings.miniPlayerOverlay
            e[K.ASA_ENABLED] = settings.asaEnabled
            e[K.ASA_TEST_ALERTS] = settings.asaTestAlerts
            e[K.ASA_LOCATION_CODES] = settings.asaLocationCodes.joinToString(",")
            e[K.ASA_FOLLOW_GPS] = settings.asaFollowGps
            e[K.SORT_MODE] = sortMode.name
            e[K.FIXED_NAMES] = fixedNames.entries.map { "${it.key}\u0001${it.value}" }.toSet()
            // Learned FM frequency→name memory (HCT4Radio's saveFrequencyPsn) — survives a rescan.
            e[K.FM_NAMES] = fmNames.entries.map { "${it.key}$SEP${it.value}" }.toSet()
            e[K.ANALOG] = analogStations
                .map { "${it.band.name}$SEP${it.khz}$SEP${it.name}$SEP${it.pi ?: ""}" }.toSet()
            e[K.DAB_STATIONS] = encodeDab(dabStations)
            e[K.IP_STATIONS] = encodeIp(ipStations)
            e[K.IP_GAINS] = ipGains.entries.map { "${it.key}$SEP${it.value}" }.toSet()
            e[K.RADIODNS_STREAMS] = radioDnsStreams.entries.map { "${it.key}$SEP${it.value}" }.toSet()
            e[K.IP_SEEDED] = true          // any write means the seeds have been resolved at least once
            e[K.IP_SEED_VERSION] = ipSeedVersion
            if (lastPlayedId != null) e[K.LAST_PLAYED] = lastPlayedId else e.remove(K.LAST_PLAYED)
            e[K.LAST_PER_BAND] = lastStationPerBand.entries.map { "${it.key.name}$SEP${it.value}" }.toSet()
            presetIndices.forEach { i ->
                val id = presets.firstOrNull { it.index == i }?.stationId
                if (id != null) e[K.preset(i)] = id else e.remove(K.preset(i))
            }
        }
    }

    private object K {
        val SEEDED = booleanPreferencesKey("seeded")
        val PREFER_DAB = booleanPreferencesKey("prefer_dab")
        val SERVICE_FOLLOWING = booleanPreferencesKey("service_following")
        val HANDOVER = intPreferencesKey("handover_threshold")
        val SOFT_FADE = booleanPreferencesKey("soft_fade")
        val DIAGNOSTICS = booleanPreferencesKey("diagnostics")
        val SWC = booleanPreferencesKey("swc_keys")
        val FM_REGION = stringPreferencesKey("fm_region")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val FRONTEND = stringPreferencesKey("frontend_id")
        val AUTO_ROTATE = booleanPreferencesKey("auto_rotate")
        val SKIN_DARK = stringPreferencesKey("skin_dark_id")
        val SKIN_LIGHT = stringPreferencesKey("skin_light_id")
        val ACCENT = stringPreferencesKey("accent_id")
        val SHOW_SIGNAL = booleanPreferencesKey("show_signal")
        val STEP_MODE = stringPreferencesKey("step_mode")
        val DAB_DAB = booleanPreferencesKey("dab_dab_following")
        val AUTO_LOGOS = booleanPreferencesKey("auto_assign_logos")
        val RADIO_DNS = booleanPreferencesKey("radio_dns_enabled")
        val RADIOVIS = booleanPreferencesKey("radiovis_enabled")
        val RADIOVIS_SLIDES = booleanPreferencesKey("radiovis_slideshow")
        val FALLBACK_IP_FIRST = booleanPreferencesKey("fallback_ip_first")
        val IP_FALLBACK = booleanPreferencesKey("ip_fallback_enabled")
        val IP_FALLBACK_WIFI = booleanPreferencesKey("ip_fallback_wifi_only")
        val AUTO_FETCH_LOGOS = booleanPreferencesKey("auto_fetch_logos")
        val MEDIA_BROADCAST_LOGOS = booleanPreferencesKey("media_broadcast_logos")
        val NORMALIZE_LOUDNESS = booleanPreferencesKey("normalize_loudness")
        val UI_SOUNDS = booleanPreferencesKey("ui_sounds")
        val ASA_ATTENTION_TONE = booleanPreferencesKey("asa_attention_tone")
        val MINI_PLAYER_OVERLAY = booleanPreferencesKey("mini_player_overlay")
        val ASA_ENABLED = booleanPreferencesKey("asa_enabled")
        val ASA_TEST_ALERTS = booleanPreferencesKey("asa_test_alerts")
        val ASA_LOCATION_CODE = stringPreferencesKey("asa_location_code")   // pre-2.7.40, read-only
        val ASA_LOCATION_CODES = stringPreferencesKey("asa_location_codes")
        val ASA_FOLLOW_GPS = booleanPreferencesKey("asa_follow_gps")
        val EWS_ENSEMBLES = stringSetPreferencesKey("ews_ensembles")
        val LAST_PLAYED = stringPreferencesKey("last_played")
        val LAST_PER_BAND = stringSetPreferencesKey("last_per_band")
        val SORT_MODE = stringPreferencesKey("sort_mode")
        val FIXED_NAMES = stringSetPreferencesKey("fixed_names")
        val FM_NAMES = stringSetPreferencesKey("fm_names")
        val ANALOG = stringSetPreferencesKey("analog_stations")
        val DAB_STATIONS = stringPreferencesKey("dab_stations")
        val IP_STATIONS = stringPreferencesKey("ip_stations")
        val IP_GAINS = stringSetPreferencesKey("ip_gains")
        val RADIODNS_STREAMS = stringSetPreferencesKey("radiodns_streams")
        val IP_SEEDED = booleanPreferencesKey("ip_seeded")
        val IP_VIS_HARVESTED = booleanPreferencesKey("ip_vis_harvested")
        val IP_SEED_VERSION = intPreferencesKey("ip_seed_version")
        fun preset(i: Int) = stringPreferencesKey("preset_$i")
    }

    private companion object {
        /** DAB services as a JSON array — a compact display record per station (keys kept short). */
        fun encodeDab(stations: List<Station>): String {
            val arr = JSONArray()
            stations.filter { it.band == Band.DAB }.forEach { st ->
                val o = JSONObject()
                o.put("id", st.id); o.put("name", st.name); o.put("sub", st.subtitle)
                o.put("ini", st.logoInitials); o.put("ls", st.logoStart); o.put("le", st.logoEnd)
                st.ensemble?.let { o.put("ens", it) }
                st.ecc?.let { o.put("ecc", it) }
                st.bitrateKbps?.let { o.put("br", it) }
                st.frequencyKhz?.let { o.put("fq", it) }
                st.piCode?.let { o.put("pi", it) }
                st.linkedFmFrequencyKhz?.let { o.put("lfq", it) }
                st.linkedFmPi?.let { o.put("lpi", it) }
                if (st.secondaryLabels.isNotEmpty()) o.put("sec", JSONArray(st.secondaryLabels))
                arr.put(o)
            }
            return arr.toString()
        }

        /** Internet stations as a JSON array — id/name/subtitle/stream URL/homepage/logo colours. */
        fun encodeIp(stations: List<Station>): String {
            val arr = JSONArray()
            stations.filter { it.band == Band.IP }.forEach { st ->
                val o = JSONObject()
                o.put("id", st.id); o.put("name", st.name); o.put("sub", st.subtitle)
                o.put("ini", st.logoInitials); o.put("ls", st.logoStart); o.put("le", st.logoEnd)
                st.ensemble?.let { o.put("ens", it) }
                st.streamUrl?.let { o.put("url", it) }
                st.homepage?.let { o.put("home", it) }
                st.radioDnsBearer?.let { o.put("rdb", it) }
                arr.put(o)
            }
            return arr.toString()
        }

        fun decodeIp(json: String?): List<Station> {
            if (json.isNullOrEmpty()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id").ifEmpty { return@mapNotNull null }
                    val url = o.optString("url").ifEmpty { return@mapNotNull null }
                    Station(
                        id = id,
                        name = o.optString("name", "Internet"),
                        band = Band.IP,
                        subtitle = o.optString("sub", "Internet"),
                        logoInitials = o.optString("ini", "IN"),
                        logoStart = o.optLong("ls"),
                        logoEnd = o.optLong("le"),
                        ensemble = o.optString("ens").ifEmpty { null },
                        streamUrl = url,
                        homepage = o.optString("home").ifEmpty { null },
                        radioDnsBearer = o.optString("rdb").ifEmpty { null },
                    )
                }
            }.getOrDefault(emptyList())
        }

        fun decodeDab(json: String?): List<Station> {
            if (json.isNullOrEmpty()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id").ifEmpty { return@mapNotNull null }
                    Station(
                        id = id,
                        name = o.optString("name", "DAB"),
                        band = Band.DAB,
                        subtitle = o.optString("sub", ""),
                        logoInitials = o.optString("ini", "DA"),
                        logoStart = o.optLong("ls"),
                        logoEnd = o.optLong("le"),
                        ensemble = o.optString("ens").ifEmpty { null },
                        ecc = if (o.has("ecc")) o.optInt("ecc") else null,
                        bitrateKbps = if (o.has("br")) o.optInt("br") else null,
                        frequencyKhz = if (o.has("fq")) o.optInt("fq") else null,
                        piCode = if (o.has("pi")) o.optInt("pi") else null,
                        linkedFmFrequencyKhz = if (o.has("lfq")) o.optInt("lfq") else null,
                        linkedFmPi = if (o.has("lpi")) o.optInt("lpi") else null,
                        secondaryLabels = o.optJSONArray("sec")?.let { a ->
                            (0 until a.length()).map { j -> a.optString(j) }.filter { it.isNotEmpty() }
                        } ?: emptyList(),
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
}
