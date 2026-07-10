package com.mink.signals

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What this app knows about its own install. The install source and the first
 * install time are the oldest fresh timestamps a third party app can see, and
 * the signing digest is a stable per-developer identifier. Nothing here needs a
 * permission.
 */
class AppInfoProvider(
    private val ctx: ProviderContext,
) : SignalProvider {

    override val category: SignalCategory = SignalCategory.APP_INFO

    override suspend fun collect(): List<FingerprintSignal> {
        val pm = ctx.appContext.packageManager
        val packageName = ctx.appContext.packageName
        val signals = mutableListOf<FingerprintSignal>()

        val info: PackageInfo? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }
        }.getOrNull()

        signals += FingerprintSignal.make(
            key = "package",
            category = category,
            name = "Package name",
            value = packageName,
            rationale = "The identifier this app registers under on your phone.",
        )

        val versionName = info?.versionName ?: "unknown"
        val versionCode = if (info != null) longVersionCode(info) else -1L
        signals += FingerprintSignal.make(
            key = "version",
            category = category,
            name = "Version",
            value = "$versionName ($versionCode)",
            rationale = "The build of this app you are running.",
            displayHint = DisplayHint.COMPOUND,
            entries = listOf(
                SignalEntry("Name", versionName),
                SignalEntry("Code", versionCode.toString()),
            ),
        )

        signals += FingerprintSignal.make(
            key = "installer",
            category = category,
            name = "Installer",
            value = readInstaller(pm, packageName),
            rationale =
                "Which store or app installed this one. It hints at how you set up your phone. " +
                    "Read from PackageManager install source.",
        )

        if (info != null) {
            signals += FingerprintSignal.make(
                key = "firstInstall",
                category = category,
                name = "First installed",
                value = formatTimestamp(info.firstInstallTime),
                rationale =
                    "When you first installed this app. It is a fresh timestamp that helps " +
                        "distinguish you from users who installed at another time.",
            )
            signals += FingerprintSignal.make(
                key = "lastUpdate",
                category = category,
                name = "Last updated",
                value = formatTimestamp(info.lastUpdateTime),
                rationale = "When this app was last updated on your phone.",
            )
        }

        val targetSdk = ctx.appContext.applicationInfo.targetSdkVersion
        signals += FingerprintSignal.make(
            key = "targetSdk",
            category = category,
            name = "Target SDK",
            value = targetSdk.toString(),
            rationale = "The Android level this app targets, which shapes how the OS treats it.",
        )

        val debuggable =
            (ctx.appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        signals += FingerprintSignal.make(
            key = "debuggable",
            category = category,
            name = "Debuggable",
            value = if (debuggable) "yes" else "no",
            rationale = "Whether this build allows a debugger to attach. Release builds do not.",
        )

        val digest = info?.let { signingDigest(it) }
        if (digest != null) {
            signals += FingerprintSignal.make(
                key = "signing",
                category = category,
                name = "Signing certificate",
                value = digest,
                rationale =
                    "A SHA-256 digest of the certificate this app is signed with. It is stable " +
                        "across updates and shared by every app from the same developer. Read " +
                        "from the package signing info.",
            )
        }

        return signals
    }

    @Suppress("DEPRECATION")
    private fun longVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }

    private fun readInstaller(pm: PackageManager, packageName: String): String {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(packageName)
            }
        }.getOrNull() ?: "sideloaded or unknown"
    }

    private fun signingDigest(info: PackageInfo): String? = runCatching {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return@runCatching null
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val first = signatures?.firstOrNull() ?: return@runCatching null
        val md = MessageDigest.getInstance("SHA-256")
        PassiveFormat.hexDigest(md.digest(first.toByteArray()))
    }.getOrNull()

    private fun formatTimestamp(millis: Long): String = runCatching {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fmt.format(Date(millis))
    }.getOrDefault(millis.toString())
}
