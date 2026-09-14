package com.px6.radio.diag

import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Android 9+ blocks *reflective* access to non-SDK ("hidden") members — which includes vendor
 * framework classes such as `android.microntek.CarManager`. The symptom is exactly what we saw on
 * the device: `Class.forName()` succeeds, but `getConstructor()` throws NoSuchMethodException even
 * though the member exists (the ROM's own apps are system apps and therefore exempt).
 *
 * [unseal] lifts the restriction process-wide via `VMRuntime.setHiddenApiExemptions`, reached by
 * meta-reflection so the runtime attributes the caller to the framework (which is exempt).
 * [constructor] / [method] additionally look members up *through* `java.lang.Class`, which keeps
 * working per-call even if [unseal] failed.
 */
object HiddenApi {

    /** Human-readable result, surfaced in klarwelle-diag.txt. */
    @Volatile
    var status: String = "nicht versucht"
        private set

    private val classArrayType: Class<*> = emptyArray<Class<*>>().javaClass
    private val stringArrayType: Class<*> = emptyArray<String>().javaClass

    fun unseal(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            status = "nicht nötig (API ${Build.VERSION.SDK_INT})"
            return true
        }
        // AndroidHiddenApiBypass sets VMRuntime.setHiddenApiExemptions via Unsafe (no telltale
        // reflection frame), which is the only reliable path from Android 11 on. Exemptions
        // override even BLOCKED members, so the Microntek vendor namespace becomes reachable.
        return try {
            val ok = HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/microntek/", "Lcom/microntek/", "Ldalvik/system/",
            )
            // Belt-and-suspenders: also exempt everything, in case a member sits outside those.
            HiddenApiBypass.addHiddenApiExemptions("L")
            status = if (ok) "ok (API ${Build.VERSION.SDK_INT}, via HiddenApiBypass)"
                     else "HiddenApiBypass meldete false (API ${Build.VERSION.SDK_INT})"
            Log.i(TAG, "hidden API unseal: $status")
            ok
        } catch (t: Throwable) {
            val real = rootCause(t)
            status = "fehlgeschlagen: ${real.javaClass.name}: ${real.message ?: "(keine Meldung)"}"
            Log.w(TAG, "unseal failed: $status")
            false
        }
    }

    /** InvocationTargetException hides the real error — unwrap it for a useful diagnosis. */
    private fun rootCause(t: Throwable): Throwable {
        var e = t
        var guard = 0
        while (guard++ < 8) {
            val next = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e.cause
            if (next == null || next === e) break
            e = next
        }
        return e
    }

    // ---- per-call fallback: look members up *through* java.lang.Class ----

    private val metaGetCtor: Method? by lazy {
        runCatching {
            Class::class.java.getDeclaredMethod("getDeclaredConstructor", classArrayType)
        }.getOrNull()
    }

    private val metaGetMethod: Method? by lazy {
        runCatching {
            Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, classArrayType)
        }.getOrNull()
    }

    /** Constructor lookup that survives hidden-API enforcement (null if truly absent). */
    fun constructor(cls: Class<*>, vararg params: Class<*>): Constructor<*>? {
        val direct = runCatching {
            cls.getDeclaredConstructor(*params).also { it.isAccessible = true }
        }.getOrNull()
        if (direct != null) return direct
        return runCatching {
            val args = arrayOfNulls<Any>(1)
            args[0] = arrayOf<Class<*>>(*params)
            (metaGetCtor?.invoke(cls, *args) as? Constructor<*>)?.also { it.isAccessible = true }
        }.getOrNull()
    }

    /** Method lookup that survives hidden-API enforcement (null if truly absent). */
    fun method(cls: Class<*>, name: String, vararg params: Class<*>): Method? {
        val direct = runCatching {
            cls.getDeclaredMethod(name, *params).also { it.isAccessible = true }
        }.getOrNull()
        if (direct != null) return direct
        return runCatching {
            val args = arrayOfNulls<Any>(2)
            args[0] = name
            args[1] = arrayOf<Class<*>>(*params)
            (metaGetMethod?.invoke(cls, *args) as? Method)?.also { it.isAccessible = true }
        }.getOrNull()
    }

    private const val TAG = "HiddenApi"
}
