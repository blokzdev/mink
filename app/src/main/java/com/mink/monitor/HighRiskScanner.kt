package com.mink.monitor

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Reads the device's high-risk security surfaces — enabled accessibility
 * services and notification listeners, active device admins, user-added CA
 * certificates, the default-app roles, and whether a device-wide VPN is routing
 * traffic. Every read is permission-free or covered by permissions the app
 * already holds; no surface is ever logged.
 *
 * Failure model: each surface is read independently and, on a THROW, carries the
 * previous snapshot's value forward. A transient read failure therefore never
 * diffs as a removal, while a successful but empty read (a genuine removal) still
 * surfaces. `runCatching` is what distinguishes the two.
 *
 * Honest limits: a device-wide VPN is detectable but a normal app cannot learn
 * which app owns it, so the snapshot never claims one. A per-app VPN that
 * excludes Mink may be missed entirely.
 */
class HighRiskScanner(private val appContext: Context) {

    suspend fun scan(nowMs: Long, previous: HighRiskSnapshot?): HighRiskSnapshot =
        withContext(Dispatchers.IO) {
            val accessibilityServices =
                carrySurface(previous?.accessibilityServices, emptyList()) { readAccessibilityServices() }
            val notificationListeners =
                carrySurface(previous?.notificationListeners, emptyList()) { readNotificationListeners() }
            val deviceAdmins = carrySurface(previous?.deviceAdmins, emptyList()) { readDeviceAdmins() }
            val userCertificates =
                carrySurface(previous?.userCertificates, emptyList()) { readUserCertificates() }
            val defaultApps = carrySurface(previous?.defaultApps, emptyMap()) { readDefaultApps() }
            val vpnActive = carrySurface(previous?.vpnActive, false) { readVpnActive() }

            HighRiskSnapshot(
                schemaVersion = HIGH_RISK_SCHEMA_VERSION,
                generatedAtMs = nowMs,
                accessibilityServices = accessibilityServices,
                notificationListeners = notificationListeners,
                deviceAdmins = deviceAdmins,
                userCertificates = userCertificates,
                defaultApps = defaultApps,
                vpnActive = vpnActive,
            )
        }

    // ---- surface reads (each throws on failure so the scan carries forward) ----

    private fun readAccessibilityServices(): List<HighRiskComponent> =
        parseComponents(
            Settings.Secure.getString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ),
        )

    private fun readNotificationListeners(): List<HighRiskComponent> =
        // Settings.Secure.ENABLED_NOTIFICATION_LISTENERS is @hide, so the literal
        // key is hardcoded. NotificationManager's own-package check cannot see
        // other apps' listeners and is not usable here.
        parseComponents(
            Settings.Secure.getString(appContext.contentResolver, "enabled_notification_listeners"),
        )

    private fun readDeviceAdmins(): List<HighRiskAdmin> {
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return emptyList()
        val active = dpm.activeAdmins ?: return emptyList()
        return active
            .map { component ->
                val pkg = component.packageName
                HighRiskAdmin(
                    packageName = pkg,
                    label = appLabel(pkg),
                    isDeviceOwner = runCatching { dpm.isDeviceOwnerApp(pkg) }.getOrDefault(false),
                    isProfileOwner = runCatching { dpm.isProfileOwnerApp(pkg) }.getOrDefault(false),
                )
            }
            .sortedBy { it.packageName }
    }

    private fun readUserCertificates(): List<HighRiskCert> {
        val keyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
        val certs = mutableListOf<HighRiskCert>()
        val aliases = keyStore.aliases()
        while (aliases.hasMoreElements()) {
            // "user:" is the AOSP convention for user-added CAs; "system:" are
            // preinstalled and deliberately ignored.
            val alias = aliases.nextElement()
            if (!alias.startsWith("user:")) continue
            val label = runCatching {
                (keyStore.getCertificate(alias) as? X509Certificate)?.let { cert ->
                    commonNameFromDn(cert.subjectX500Principal.name)?.takeIf { it.isNotBlank() }
                }
            }.getOrNull() ?: "Certificate"
            certs += HighRiskCert(id = alias, label = label)
        }
        return certs.sortedBy { it.id }
    }

    private fun readDefaultApps(): Map<String, HighRiskDefaultApp> {
        val defaults = mutableMapOf<String, HighRiskDefaultApp>()
        val resolver = appContext.contentResolver
        val pm = appContext.packageManager

        putRole(defaults, "sms", Telephony.Sms.getDefaultSmsPackage(appContext))

        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))
        val browser = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(browserIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(browserIntent, 0)
        }
        // A null resolver, or the "android" disambiguation activity, means no
        // default browser is set.
        putRole(defaults, "browser", browser?.activityInfo?.packageName)

        // The IME setting is a flattened ComponentName; keep just the package.
        Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.let { ime ->
            putRole(defaults, "ime", ime.substringBefore('/', ime))
        }

        val telecom = appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        putRole(defaults, "dialer", telecom?.defaultDialerPackage)

        return defaults
    }

    private fun readVpnActive(): Boolean {
        // Mink's own DNS-flow monitor holds the single VPN slot while it runs.
        // A normal app cannot learn which app owns the active VPN, but since only
        // one VPN can be active at a time, if ours is running it IS the one on the
        // transport, so we must not flag it as a suspicious third-party VPN.
        if (DnsFlowHub.running.value) return false
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    // ---- helpers ----

    /** Split a colon-separated flattened-ComponentName list into stable components. */
    private fun parseComponents(raw: String?): List<HighRiskComponent> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { flattened ->
                val pkg = flattened.substringBefore('/', flattened)
                val label = if (pkg.isNotEmpty() && pkg != flattened) appLabel(pkg) else flattened
                HighRiskComponent(id = flattened, label = label)
            }
            .sortedBy { it.id }
    }

    /** Record a role only when it resolves to a real package (not null, blank, or the resolver). */
    private fun putRole(into: MutableMap<String, HighRiskDefaultApp>, role: String, pkg: String?) {
        if (pkg.isNullOrBlank() || pkg == "android") return
        into[role] = HighRiskDefaultApp(pkg, appLabel(pkg))
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = appContext.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
        }
        pm.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)
}
