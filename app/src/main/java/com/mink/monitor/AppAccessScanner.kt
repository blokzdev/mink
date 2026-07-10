package com.mink.monitor

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads granted runtime permissions per installed app from PackageManager. */
class AppAccessScanner(private val appContext: Context) {

    /** Scan all visible packages. Never throws; returns an empty report on failure. */
    suspend fun scan(nowMs: Long = System.currentTimeMillis()): AppAccessReport =
        withContext(Dispatchers.IO) {
            val pm = appContext.packageManager
            val packages = readInstalledPackages(pm)
            val records = packages.mapNotNull { info ->
                runCatching { toRecord(pm, info) }.getOrNull()
            }
            AppAccessReport.from(records, nowMs)
        }

    private fun toRecord(pm: PackageManager, info: PackageInfo): AppRecord {
        val requested = info.requestedPermissions
        val flags = info.requestedPermissionsFlags
        val granted = mutableSetOf<PermCapability>()
        val declared = mutableSetOf<PermCapability>()
        if (requested != null) {
            for (i in requested.indices) {
                val capability = PermCapability.of(requested[i]) ?: continue
                val isGranted = flags != null && i < flags.size &&
                    (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                if (isGranted) {
                    granted += capability
                } else {
                    declared += capability
                }
            }
        }
        // Granted wins: a capability granted via any permission must not also appear as declared-only.
        declared -= granted
        return AppRecord(
            packageName = info.packageName,
            label = readLabel(pm, info),
            isSystem = isSystemApp(info),
            firstInstallMs = info.firstInstallTime,
            lastUpdateMs = info.lastUpdateTime,
            granted = granted,
            declaredNotGranted = declared,
        )
    }

    private fun readInstalledPackages(pm: PackageManager): List<PackageInfo> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }
    }.getOrDefault(emptyList())

    private fun isSystemApp(info: PackageInfo): Boolean {
        val flags = info.applicationInfo?.flags ?: return false
        val system = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return (flags and system) != 0
    }

    private fun readLabel(pm: PackageManager, info: PackageInfo): String = runCatching {
        val appInfo = info.applicationInfo ?: return@runCatching info.packageName
        pm.getApplicationLabel(appInfo).toString()
    }.getOrDefault(info.packageName)
}
