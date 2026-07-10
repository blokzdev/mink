package com.mink.signals

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider

/**
 * The other apps installed on this phone. Any app that can enumerate packages
 * reads a lifestyle profile from the list alone: your banking apps, your dating
 * apps, your VPN, your crypto wallet. Installed apps hint at your work, travel,
 * finances, and hobbies, and none of it needs a permission prompt.
 *
 * Mink reports only counts, an inferred profile, and a small sample. It never
 * uploads the list or reads inside any other app.
 */
class InstalledAppsProvider(
    private val ctx: ProviderContext,
) : SignalProvider {

    override val category: SignalCategory = SignalCategory.INSTALLED_APPS

    /** Permissions that make an app notably capable of watching or locating you. */
    private val sensitivePermissions: Set<String> = setOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.READ_PHONE_STATE,
        "android.permission.READ_SMS",
    )

    override suspend fun collect(): List<FingerprintSignal> {
        val pm = ctx.appContext.packageManager
        val packages = readInstalledPackages(pm)

        if (packages.isEmpty()) {
            return listOf(
                FingerprintSignal.make(
                    key = "unavailable",
                    category = category,
                    name = "Installed apps",
                    value = "Unavailable",
                    rationale =
                        "This build of Android would not return the package list. On most " +
                            "phones any app can read it without a prompt.",
                ),
            )
        }

        val signals = mutableListOf<FingerprintSignal>()

        val userApps = packages.filter { !isSystemApp(it) }
        val systemApps = packages.size - userApps.size

        signals += FingerprintSignal.make(
            key = "total",
            category = category,
            name = "Installed packages",
            value = packages.size.toString(),
            rationale =
                "How many packages live on this phone. The exact number is itself a weak " +
                    "identifier, and the list behind it is a strong one.",
        )

        signals += FingerprintSignal.make(
            key = "userVsSystem",
            category = category,
            name = "User vs system",
            value = "${userApps.size} installed by you, $systemApps from the system",
            rationale =
                "Apps you chose to install carry the most signal. The system set stays " +
                    "fairly constant for a given phone model.",
            displayHint = DisplayHint.COMPOUND,
            entries = listOf(
                SignalEntry("Your apps", userApps.size.toString()),
                SignalEntry("System apps", systemApps.toString()),
            ),
        )

        val profile = AppTaxonomy.profile(userApps.map { it.packageName })
        if (profile.isNotEmpty()) {
            val entries = profile.entries.map { SignalEntry(it.key.label, it.value.toString()) }
            signals += FingerprintSignal.make(
                key = "profile",
                category = category,
                name = "Inferred profile",
                value = profile.entries.joinToString(", ") { "${it.key.label} (${it.value})" },
                rationale =
                    "The categories your apps fall into. This is the lifestyle read a tracker " +
                        "takes from the list: the mix of finance, dating, health, VPN, and crypto " +
                        "apps sketches your habits without any of them being opened.",
                displayHint = DisplayHint.TAGS,
                entries = entries,
            )
        }

        val sensitiveCount = packages.count { requestsSensitivePermission(it) }
        signals += FingerprintSignal.make(
            key = "sensitive",
            category = category,
            name = "Apps with sensitive permissions",
            value = sensitiveCount.toString(),
            rationale =
                "How many installed apps declare access to location, camera, microphone, " +
                    "contacts, or the phone state. Each one is another party that can watch " +
                    "part of your day.",
        )

        val sample = userApps
            .map { readLabel(pm, it) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .take(SAMPLE_LIMIT)
        if (sample.isNotEmpty()) {
            signals += FingerprintSignal.make(
                key = "sample",
                category = category,
                name = "Sample of your apps",
                value = sample.joinToString(", "),
                rationale =
                    "A short sample of the apps you installed, sorted by name. The full list " +
                        "stays on your phone. Read from PackageManager.",
                displayHint = DisplayHint.TAGS,
                entries = sample.map { SignalEntry(it, "") },
            )
        }

        return signals
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

    private fun requestsSensitivePermission(info: PackageInfo): Boolean {
        val requested = info.requestedPermissions ?: return false
        return requested.any { it in sensitivePermissions }
    }

    private fun readLabel(pm: PackageManager, info: PackageInfo): String = runCatching {
        val appInfo = info.applicationInfo ?: return@runCatching info.packageName
        pm.getApplicationLabel(appInfo).toString()
    }.getOrDefault(info.packageName)

    private companion object {
        const val SAMPLE_LIMIT = 15
    }
}
