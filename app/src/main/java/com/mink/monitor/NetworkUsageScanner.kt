package com.mink.monitor

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads per-app data volumes from [NetworkStatsManager] over a window: how much
 * each app sent and received over WiFi and cellular, how much of the cellular
 * was while roaming, and how much was in the background.
 *
 * Honest limits: this is volumes only. Android does not tell a normal app which
 * servers or hosts an app talked to, so the scanner never claims a destination —
 * only how much moved, split by network and condition. Background here is the
 * platform's coarse per-uid state counter, not a claim about what the owner was
 * doing at the time.
 *
 * Failure model: every query runs off the main thread and the whole scan is
 * wrapped so it never throws. A SecurityException (usage access not granted) or a
 * RemoteException (the stats service died) yields an empty report for the window
 * rather than an error. Each network pass is best-effort: a null
 * [NetworkStatsManager.querySummary] for one network simply contributes nothing
 * from that pass, so a WiFi-only device still reports its WiFi usage.
 */
class NetworkUsageScanner(context: Context) {

    private val appContext = context.applicationContext

    /** Read per-app volumes over [startMs, endMs]. Never throws; empty on any failure. */
    suspend fun scan(startMs: Long, endMs: Long): DataUsageReport =
        withContext(Dispatchers.IO) {
            runCatching { collect(startMs, endMs) }
                .getOrDefault(DataUsageReport(startMs, endMs, emptyList(), 0L))
        }

    @Suppress("DEPRECATION") // TYPE_WIFI/TYPE_MOBILE are the querySummary(int, ...) contract.
    private fun collect(startMs: Long, endMs: Long): DataUsageReport {
        val nsm = appContext.getSystemService(NetworkStatsManager::class.java)
            ?: return DataUsageReport(startMs, endMs, emptyList(), 0L)

        val usageByUid = HashMap<Int, MutableAppUsage>()

        // subscriberId MUST be null: a non-null value is privileged and throws,
        // while null yields the all-networks aggregate for the type. Each pass is
        // best-effort: a null result contributes nothing rather than discarding
        // the other pass, so a WiFi-only device still shows its WiFi usage.
        nsm.querySummary(ConnectivityManager.TYPE_WIFI, null, startMs, endMs)
            ?.use { stats -> accumulate(stats, usageByUid, mobile = false) }
        nsm.querySummary(ConnectivityManager.TYPE_MOBILE, null, startMs, endMs)
            ?.use { stats -> accumulate(stats, usageByUid, mobile = true) }

        val pm = appContext.packageManager
        val apps = usageByUid.values
            .mapNotNull { resolve(pm, it) }
            .sortedWith(compareByDescending<AppDataUsage> { it.totalBytes }.thenBy { it.label })
        val deviceTotal = apps.sumOf { it.totalBytes }
        return DataUsageReport(startMs, endMs, apps, deviceTotal)
    }

    /**
     * Fold one query's buckets into [usageByUid]. One [NetworkStats.Bucket] is
     * reused across the whole iteration. The WiFi pass adds to wifiBytes; the
     * MOBILE pass adds to mobileBytes, plus roamingBytes for roaming buckets and
     * backgroundMobileBytes for background (STATE_DEFAULT) buckets. UID_ALL and
     * tagged rows are skipped so nothing is double counted.
     */
    private fun accumulate(
        stats: NetworkStats,
        usageByUid: HashMap<Int, MutableAppUsage>,
        mobile: Boolean,
    ) {
        val bucket = NetworkStats.Bucket()
        while (stats.hasNextBucket()) {
            stats.getNextBucket(bucket)
            val uid = bucket.uid
            if (uid == NetworkStats.Bucket.UID_ALL) continue
            if (bucket.tag != NetworkStats.Bucket.TAG_NONE) continue
            val bytes = bucket.rxBytes + bucket.txBytes
            val entry = usageByUid.getOrPut(uid) { MutableAppUsage(uid) }
            if (mobile) {
                entry.mobileBytes += bytes
                if (bucket.roaming == NetworkStats.Bucket.ROAMING_YES) entry.roamingBytes += bytes
                if (bucket.state == NetworkStats.Bucket.STATE_DEFAULT) entry.backgroundMobileBytes += bytes
            } else {
                entry.wifiBytes += bytes
            }
        }
    }

    /**
     * Resolve a uid to a labelled [AppDataUsage], or null to drop it. UID_ALL is
     * dropped; UID_REMOVED and UID_TETHERING get synthetic labels and count as
     * system. A real uid resolves through PackageManager: a single package yields
     * its application label and system flag; a uid shared by several packages is
     * not attributed to one app — it gets a neutral "N apps sharing a user ID"
     * label with a deterministic package ref so the cooldown key stays stable; a
     * uid with no package falls back to [PackageManager.getNameForUid].
     */
    private fun resolve(pm: PackageManager, usage: MutableAppUsage): AppDataUsage? {
        when (usage.uid) {
            NetworkStats.Bucket.UID_ALL -> return null
            NetworkStats.Bucket.UID_REMOVED -> return usage.toReport(null, "Removed apps", isSystem = true)
            NetworkStats.Bucket.UID_TETHERING -> return usage.toReport(null, "Tethering", isSystem = true)
        }
        val packages = runCatching { pm.getPackagesForUid(usage.uid) }.getOrNull()?.toList().orEmpty()
        return when {
            packages.isEmpty() -> {
                val fallback = runCatching { pm.getNameForUid(usage.uid) }.getOrNull()
                usage.toReport(null, fallback ?: "uid ${usage.uid}", isSystem = false)
            }
            packages.size == 1 -> {
                val packageName = packages.first()
                val info = runCatching { applicationInfo(pm, packageName) }.getOrNull()
                val label = info
                    ?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
                    ?: packageName
                usage.toReport(packageName, label, isSystem = info?.let { isSystemApp(it) } ?: false)
            }
            else -> {
                // Sorted so the (category, title) cooldown key is stable across sweeps.
                val ref = packages.sorted().first()
                usage.toReport(ref, "${packages.size} apps sharing a user ID", isSystem = false)
            }
        }
    }

    private fun applicationInfo(pm: PackageManager, packageName: String): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }

    private fun isSystemApp(info: ApplicationInfo): Boolean {
        val system = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return (info.flags and system) != 0
    }

    /** Per-uid accumulator, mutated across the two query passes before it is frozen. */
    private class MutableAppUsage(val uid: Int) {
        var wifiBytes = 0L
        var mobileBytes = 0L
        var roamingBytes = 0L
        var backgroundMobileBytes = 0L
    }

    private fun MutableAppUsage.toReport(packageName: String?, label: String, isSystem: Boolean) =
        AppDataUsage(
            uid = uid,
            packageName = packageName,
            label = label,
            isSystem = isSystem,
            wifiBytes = wifiBytes,
            mobileBytes = mobileBytes,
            roamingBytes = roamingBytes,
            backgroundMobileBytes = backgroundMobileBytes,
        )

    companion object {
        /** True when the user granted Mink usage access in system settings. */
        fun hasUsageAccess(context: Context): Boolean = runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return@runCatching false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }
}
