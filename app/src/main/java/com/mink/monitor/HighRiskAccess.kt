package com.mink.monitor

import kotlinx.serialization.Serializable

/** Current on-disk schema of [HighRiskSnapshot]; discard on mismatch (see GuardianStore). */
const val HIGH_RISK_SCHEMA_VERSION = 1

/**
 * The fixed set of default-app roles the watcher tracks, in a stable order.
 * Each is a classic compromise surface: the keyboard sees everything typed, the
 * SMS app reads texts (including one-time codes), the browser sees every link
 * opened, and the phone app sees calls.
 */
val DEFAULT_ROLES: List<String> = listOf("sms", "browser", "ime", "dialer")

/** Human label for a default-app [role]; the raw role name for anything unknown. */
fun defaultRoleLabel(role: String): String = when (role) {
    "sms" -> "default SMS app"
    "browser" -> "default browser"
    "ime" -> "keyboard"
    "dialer" -> "default phone app"
    else -> role
}

/**
 * One enabled component (accessibility service or notification listener),
 * reduced to the minimal fields a cross-sweep diff needs. Persisted inside
 * [HighRiskSnapshot].
 */
@Serializable
data class HighRiskComponent(val id: String, val label: String)      // id = flattened component

/**
 * One active device administrator, with its ownership classification. Persisted
 * inside [HighRiskSnapshot].
 */
@Serializable
data class HighRiskAdmin(
    val packageName: String,
    val label: String,
    val isDeviceOwner: Boolean,
    val isProfileOwner: Boolean,
)

/**
 * One user-added CA certificate, reduced to its store alias and a best-effort
 * subject CN label. Persisted inside [HighRiskSnapshot]; never the raw bytes.
 */
@Serializable
data class HighRiskCert(val id: String, val label: String)           // id = "user:" alias; label = subject CN best-effort

/**
 * One default-app assignment: the package that holds a role and a best-effort
 * app label for it. Persisted inside [HighRiskSnapshot].
 */
@Serializable
data class HighRiskDefaultApp(val packageName: String, val label: String)

/**
 * The persisted per-sweep picture of the device's high-risk security surfaces.
 * Persisted through `GuardianStore` (encrypted at rest with a Keystore AES-GCM
 * key, excluded from backup and device transfer); keeps only the minimal fields
 * a diff needs — component ids and labels, admin ownership flags, cert aliases
 * and subject CNs, the default-app role map, and the VPN flag. No raw
 * certificate bytes, no full distinguished names, no other personal data, and
 * the raw values are never logged.
 */
@Serializable
data class HighRiskSnapshot(
    val schemaVersion: Int = 0,          // 0 = legacy/unversioned -> discarded on load
    val generatedAtMs: Long = 0L,
    val accessibilityServices: List<HighRiskComponent> = emptyList(),
    val notificationListeners: List<HighRiskComponent> = emptyList(),
    val deviceAdmins: List<HighRiskAdmin> = emptyList(),
    val userCertificates: List<HighRiskCert> = emptyList(),
    val defaultApps: Map<String, HighRiskDefaultApp> = emptyMap(),   // role -> app; roles: sms, browser, ime, dialer
    val vpnActive: Boolean = false,
)

/** One change the high-risk watcher noticed between two sweeps. */
sealed interface HighRiskFinding {
    data class AccessibilityEnabled(val component: HighRiskComponent) : HighRiskFinding
    data class AccessibilityDisabled(val component: HighRiskComponent) : HighRiskFinding
    data class NotificationListenerEnabled(val component: HighRiskComponent) : HighRiskFinding
    data class NotificationListenerDisabled(val component: HighRiskComponent) : HighRiskFinding
    data class DeviceAdminAdded(val admin: HighRiskAdmin) : HighRiskFinding
    data class DeviceAdminRemoved(val admin: HighRiskAdmin) : HighRiskFinding
    data class UserCertificateAdded(val cert: HighRiskCert) : HighRiskFinding
    data class UserCertificateRemoved(val cert: HighRiskCert) : HighRiskFinding
    data class DefaultAppChanged(
        val role: String,
        val fromPackage: String?,
        val toPackage: String?,
        val fromLabel: String?,
        val toLabel: String?,
    ) : HighRiskFinding
    data class VpnActivated(val unit: Unit = Unit) : HighRiskFinding
    data class VpnDeactivated(val unit: Unit = Unit) : HighRiskFinding
}

/**
 * Diff two snapshots. Pure and total.
 * - previous == null -> emptyList (the first watch sweep just records the state,
 *   exactly like [diffAppAccess]; otherwise enabling the guardian would alert on
 *   every service the device already had).
 * - accessibility / notification / device-admin / user-cert: set difference by id
 *   (packageName for admins). Additions emit the *Enabled/Added finding, removals
 *   the *Disabled/Removed finding.
 * - defaultApps: for each role in [DEFAULT_ROLES] compare previous vs current; if
 *   they differ and at least one side is non-null, emit DefaultAppChanged. A role
 *   absent on both sides is no change.
 * - vpn: false->true VpnActivated; true->false VpnDeactivated.
 * - Deterministic order: a stable type rank (accessibility, notif, admin, cert,
 *   default, vpn), then id/role.
 */
fun diffHighRisk(previous: HighRiskSnapshot?, current: HighRiskSnapshot): List<HighRiskFinding> {
    if (previous == null) return emptyList()

    val findings = mutableListOf<HighRiskFinding>()

    val prevAccessibility = previous.accessibilityServices.associateBy { it.id }
    val curAccessibility = current.accessibilityServices.associateBy { it.id }
    for (component in current.accessibilityServices) {
        if (component.id !in prevAccessibility) findings += HighRiskFinding.AccessibilityEnabled(component)
    }
    for (component in previous.accessibilityServices) {
        if (component.id !in curAccessibility) findings += HighRiskFinding.AccessibilityDisabled(component)
    }

    val prevListeners = previous.notificationListeners.associateBy { it.id }
    val curListeners = current.notificationListeners.associateBy { it.id }
    for (component in current.notificationListeners) {
        if (component.id !in prevListeners) findings += HighRiskFinding.NotificationListenerEnabled(component)
    }
    for (component in previous.notificationListeners) {
        if (component.id !in curListeners) findings += HighRiskFinding.NotificationListenerDisabled(component)
    }

    val prevAdmins = previous.deviceAdmins.associateBy { it.packageName }
    val curAdmins = current.deviceAdmins.associateBy { it.packageName }
    for (admin in current.deviceAdmins) {
        if (admin.packageName !in prevAdmins) findings += HighRiskFinding.DeviceAdminAdded(admin)
    }
    for (admin in previous.deviceAdmins) {
        if (admin.packageName !in curAdmins) findings += HighRiskFinding.DeviceAdminRemoved(admin)
    }

    val prevCerts = previous.userCertificates.associateBy { it.id }
    val curCerts = current.userCertificates.associateBy { it.id }
    for (cert in current.userCertificates) {
        if (cert.id !in prevCerts) findings += HighRiskFinding.UserCertificateAdded(cert)
    }
    for (cert in previous.userCertificates) {
        if (cert.id !in curCerts) findings += HighRiskFinding.UserCertificateRemoved(cert)
    }

    for (role in DEFAULT_ROLES) {
        val from = previous.defaultApps[role]
        val to = current.defaultApps[role]
        if (from?.packageName != to?.packageName && (from != null || to != null)) {
            findings += HighRiskFinding.DefaultAppChanged(
                role,
                from?.packageName,
                to?.packageName,
                from?.label,
                to?.label,
            )
        }
    }

    if (!previous.vpnActive && current.vpnActive) findings += HighRiskFinding.VpnActivated()
    if (previous.vpnActive && !current.vpnActive) findings += HighRiskFinding.VpnDeactivated()

    return findings.sortedWith(compareBy({ it.typeRank() }, { it.sortKey() }))
}

private fun HighRiskFinding.typeRank(): Int = when (this) {
    is HighRiskFinding.AccessibilityEnabled -> 0
    is HighRiskFinding.AccessibilityDisabled -> 0
    is HighRiskFinding.NotificationListenerEnabled -> 1
    is HighRiskFinding.NotificationListenerDisabled -> 1
    is HighRiskFinding.DeviceAdminAdded -> 2
    is HighRiskFinding.DeviceAdminRemoved -> 2
    is HighRiskFinding.UserCertificateAdded -> 3
    is HighRiskFinding.UserCertificateRemoved -> 3
    is HighRiskFinding.DefaultAppChanged -> 4
    is HighRiskFinding.VpnActivated -> 5
    is HighRiskFinding.VpnDeactivated -> 5
}

private fun HighRiskFinding.sortKey(): String = when (this) {
    is HighRiskFinding.AccessibilityEnabled -> component.id
    is HighRiskFinding.AccessibilityDisabled -> component.id
    is HighRiskFinding.NotificationListenerEnabled -> component.id
    is HighRiskFinding.NotificationListenerDisabled -> component.id
    is HighRiskFinding.DeviceAdminAdded -> admin.packageName
    is HighRiskFinding.DeviceAdminRemoved -> admin.packageName
    is HighRiskFinding.UserCertificateAdded -> cert.id
    is HighRiskFinding.UserCertificateRemoved -> cert.id
    is HighRiskFinding.DefaultAppChanged -> role
    is HighRiskFinding.VpnActivated -> ""
    is HighRiskFinding.VpnDeactivated -> ""
}

/**
 * Best-effort Common Name from an RFC2253 distinguished name, or null. Splits on
 * UNescaped commas and plus signs so an escaped comma inside a CN is preserved and
 * a multi-valued RDN never leaks a non-CN attribute into the label; unescapes the
 * result. Deliberately conservative — the guardian stores only the CN, never the
 * rest of the DN.
 */
fun commonNameFromDn(dn: String): String? {
    for (rdn in splitUnescaped(dn, ',')) {
        for (piece in splitUnescaped(rdn, '+')) {
            val attribute = piece.trimStart()
            if (attribute.startsWith("CN=", ignoreCase = true)) {
                val value = unescapeRfc2253(attribute.substring(3)).trim()
                return value.ifEmpty { null }
            }
        }
    }
    return null
}

/**
 * Split [text] on each [delimiter] not preceded by an odd run of backslashes.
 * Backslashes are kept in the pieces so [unescapeRfc2253] can resolve them.
 */
private fun splitUnescaped(text: String, delimiter: Char): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    for (c in text) {
        when {
            escaped -> {
                current.append(c)
                escaped = false
            }
            c == '\\' -> {
                current.append(c)
                escaped = true
            }
            c == delimiter -> {
                parts += current.toString()
                current.clear()
            }
            else -> current.append(c)
        }
    }
    parts += current.toString()
    return parts
}

/** Resolve RFC2253 backslash escapes: a backslash makes the next character literal. */
private fun unescapeRfc2253(value: String): String {
    val out = StringBuilder(value.length)
    var escaped = false
    for (c in value) {
        when {
            escaped -> {
                out.append(c)
                escaped = false
            }
            c == '\\' -> escaped = true
            else -> out.append(c)
        }
    }
    if (escaped) out.append('\\')
    return out.toString()
}

/**
 * Read a surface, carrying [previousValue] forward on a THROW; a successful (even
 * empty) read replaces it. [empty] is used when there is no previous value.
 */
internal inline fun <T> carrySurface(previousValue: T?, empty: T, read: () -> T): T =
    runCatching(read).getOrElse { previousValue ?: empty }
