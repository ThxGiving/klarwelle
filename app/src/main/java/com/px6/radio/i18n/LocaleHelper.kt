package com.px6.radio.i18n

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * App language selection for API 30 (no per-app-language platform API before Android 13, and the app
 * uses ComponentActivity, not AppCompat). The chosen language is stored in a small synchronous
 * SharedPreferences so it can be read in [android.app.Activity.attachBaseContext] — before any
 * DataStore/ViewModel exists — and applied by wrapping the base context with the locale.
 *
 * ADDING A LANGUAGE is two steps:
 *   1) add one [AppLanguage] entry to [LANGUAGES] (tag, flag emoji, native name);
 *   2) add `app/src/main/res/values-<tag>/strings.xml` with the translations.
 * Everything else (the settings picker, the flags, the locale wrapping) is driven off this list.
 * Strings themselves use the standard Android resource system (stringResource / getString) — never
 * hardcoded — so a new locale file is all a translation needs.
 */
object LocaleHelper {

    private const val PREFS = "klarwelle_locale"
    private const val KEY = "lang"
    const val SYSTEM = "system"

    /** A selectable app language. [tag] is "system" or a BCP-47 tag ("de", "en", …). */
    data class AppLanguage(val tag: String, val flag: String, val nativeName: String) {
        /** "🇩🇪  Deutsch" — flag + native name, for the picker. */
        val label: String get() = "$flag  $nativeName".trim()
    }

    /** The single source of truth. Add a row here (+ a values-<tag>/strings.xml) to add a language. */
    val LANGUAGES = listOf(
        AppLanguage(SYSTEM, "🌐", "System"),
        AppLanguage("de", "🇩🇪", "Deutsch"),
        AppLanguage("en", "🇬🇧", "English"),
        AppLanguage("fr", "🇫🇷", "Français"),
        AppLanguage("es", "🇪🇸", "Español"),
        AppLanguage("it", "🇮🇹", "Italiano"),
        AppLanguage("nl", "🇳🇱", "Nederlands"),
        AppLanguage("pl", "🇵🇱", "Polski"),
        AppLanguage("ka", "🇬🇪", "ქართული"),
    )

    fun language(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    fun setLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang).apply()
    }

    fun forTag(tag: String): AppLanguage = LANGUAGES.firstOrNull { it.tag == tag } ?: LANGUAGES.first()

    /**
     * The device's own locale, captured before this object ever overrides it. [wrap] runs from
     * Activity.attachBaseContext, so the first call happens before any setDefault of ours.
     */
    private val systemLocale: Locale = Locale.getDefault()

    /** Wrap [base] so the app resolves resources in the chosen language. No-op for SYSTEM. */
    fun wrap(base: Context): Context {
        val lang = language(base)
        if (lang == SYSTEM) {
            // Put the process default back. Choosing a language calls setDefault, and returning to
            // SYSTEM used to just skip out — leaving the JVM default on the old language, so every
            // locale-sensitive String.format kept using it. The UI text went back to English while
            // frequencies still read "98,5 MHz" until the process was killed.
            if (Locale.getDefault() != systemLocale) Locale.setDefault(systemLocale)
            return base
        }
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
