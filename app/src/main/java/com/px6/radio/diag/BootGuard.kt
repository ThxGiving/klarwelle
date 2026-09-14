package com.px6.radio.diag

import android.content.Context
import android.os.Build

/**
 * Survives native crashes (SIGSEGV in omri or ROM code) that Java can't catch.
 *
 * Before a risky init stage we write its name synchronously ([begin]); after success we clear it
 * ([end]). If the process is killed mid-stage, the name is still on disk at the next launch —
 * [crashedStage] reports it and the stage is added to the disabled set, so we **skip it and show
 * an error** instead of crash-looping. [reset] re-enables everything.
 */
class BootGuard(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("boot_guard", Context.MODE_PRIVATE)

    /** The stage in flight when the process last died, or null if the last run was clean. */
    val crashedStage: String? = sp.getString(PENDING, null)

    private val disabled: MutableSet<String> =
        HashSet(sp.getStringSet(DISABLED, emptySet()) ?: emptySet())

    init {
        // On a new app version, clear safe-mode disables so fixes get a fresh try.
        val current = versionCode(context)
        if (sp.getLong(VERSION, -1L) != current) {
            disabled.clear()
            sp.edit().putLong(VERSION, current).remove(PENDING).remove(DISABLED).commit()
        } else {
            // A stage left pending across a restart crashed the process natively -> disable it.
            crashedStage?.let { disabled.add(it) }
            sp.edit().remove(PENDING).putStringSet(DISABLED, disabled).commit()
        }
    }

    private fun versionCode(context: Context): Long = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (t: Throwable) {
        -1L
    }

    fun isDisabled(stage: String): Boolean = stage in disabled
    fun disabledStages(): Set<String> = disabled.toSet()

    /** Must be synchronous so the breadcrumb is on disk before the risky call runs. */
    fun begin(stage: String) {
        sp.edit().putString(PENDING, stage).commit()
    }

    fun end() {
        sp.edit().remove(PENDING).commit()
    }

    fun reset() {
        disabled.clear()
        sp.edit().clear().commit()
    }

    private companion object {
        const val PENDING = "pending"
        const val DISABLED = "disabled"
        const val VERSION = "version"
    }
}
