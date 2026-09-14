package com.px6.radio.diag

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * One-shot reconnaissance to identify the "vehicle app" that shows the outside temperature, so it
 * can be pulled from the device and analysed.
 *
 * Writes to `klarwelle-carapps.txt` on the stick:
 *  - every app with a **manifest** receiver for `com.canbus.temperature` (the likely temperature
 *    consumers — though an app that registers the receiver at runtime won't show up here),
 *  - every installed Microntek/HCT/CAN/car-ish package with its APK path, so the right APK can be
 *    fetched with `adb pull <path>`.
 *
 * Pure lookup, no permissions beyond the normal package query — safe to run at start-up.
 */
object CarAppScout {

    private const val TAG = "CarAppScout"

    fun dump(context: Context) {
        runCatching {
            val pm = context.packageManager
            val sb = StringBuilder("=== Klarwelle car-app scout ===\n")

            sb.append("\n-- manifest receivers for com.canbus.temperature --\n")
            val recvs = pm.queryBroadcastReceivers(Intent("com.canbus.temperature"), 0)
            if (recvs.isEmpty()) sb.append("(none registered in a manifest)\n")
            recvs.forEach { ri ->
                sb.append(ri.activityInfo.packageName)
                    .append(" / ").append(ri.activityInfo.name).append("\n")
            }

            sb.append("\n-- installed Microntek/HCT/CAN/car packages --\n")
            val wanted = listOf("microntek", "hct", "canbus", "car", "mtc", "vehicle", "climate", "air")
            pm.getInstalledApplications(0)
                .filter { app -> wanted.any { app.packageName.lowercase().contains(it) } }
                .sortedBy { it.packageName }
                .forEach { app ->
                    sb.append(app.packageName).append("  ->  ")
                        .append(app.sourceDir).append("\n")   // APK path for `adb pull`
                }

            Diag.write(context, "klarwelle-carapps.txt", sb.toString())
            Log.i(TAG, "car-app scout written")
        }.onFailure { Log.w(TAG, "scout failed: ${it.message}") }
    }
}
