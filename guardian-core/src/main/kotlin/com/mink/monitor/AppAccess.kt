package com.mink.monitor

/**
 * A user-legible capability that a runtime (dangerous) permission grants. Groups
 * the raw Android permission strings a person actually cares about ("can this app
 * hear me?") rather than showing 60 individual permission constants.
 */
enum class PermCapability(val label: String, val permissions: Set<String>) {
    LOCATION(
        "Location",
        setOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
        ),
    ),
    CAMERA("Camera", setOf("android.permission.CAMERA")),
    MICROPHONE("Microphone", setOf("android.permission.RECORD_AUDIO")),
    CONTACTS(
        "Contacts",
        setOf(
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
            "android.permission.GET_ACCOUNTS",
        ),
    ),
    CALENDAR(
        "Calendar",
        setOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"),
    ),
    PHONE(
        "Phone",
        setOf(
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_PHONE_NUMBERS",
            "android.permission.CALL_PHONE",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
            "android.permission.PROCESS_OUTGOING_CALLS",
        ),
    ),
    SMS(
        "SMS",
        setOf(
            "android.permission.READ_SMS",
            "android.permission.SEND_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.RECEIVE_MMS",
            "android.permission.RECEIVE_WAP_PUSH",
        ),
    ),
    STORAGE(
        "Files & media",
        setOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO",
        ),
    ),
    BODY_SENSORS(
        "Body sensors",
        setOf(
            "android.permission.BODY_SENSORS",
            "android.permission.BODY_SENSORS_BACKGROUND",
        ),
    ),
    ACTIVITY("Physical activity", setOf("android.permission.ACTIVITY_RECOGNITION")),
    NEARBY_DEVICES(
        "Nearby devices",
        setOf(
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.UWB_RANGING",
        ),
    ),
    NOTIFICATIONS("Notifications", setOf("android.permission.POST_NOTIFICATIONS")),
    ;

    companion object {
        private val byPermission: Map<String, PermCapability> =
            entries.flatMap { cap -> cap.permissions.map { it to cap } }.toMap()

        /** The capability a permission belongs to, or null for non-dangerous/uncatalogued. */
        fun of(permission: String): PermCapability? = byPermission[permission]
    }
}

/** One installed app and the capabilities it currently holds (granted) vs merely declares. */
data class AppRecord(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val firstInstallMs: Long,
    val lastUpdateMs: Long,
    val granted: Set<PermCapability>,
    val declaredNotGranted: Set<PermCapability>,
)

/**
 * One capability and the apps that hold it granted. [apps] lists the apps the
 * user installed first (newest first), then system apps, so the accounts a
 * person can actually act on lead.
 */
data class CapabilityHolders(
    val capability: PermCapability,
    val apps: List<AppRecord>,
) {
    /** Apps the user installed that hold this capability. */
    val userAppCount: Int get() = apps.count { !it.isSystem }

    /** System/OS components that hold this capability. */
    val systemAppCount: Int get() = apps.count { it.isSystem }
}

/**
 * The whole app-access picture: every scanned app, and the inverted index of
 * capability -> apps that hold it.
 *
 * [byCapability] is ordered by *sensitivity* — the order the capabilities are
 * declared in [PermCapability], most alarming first (location, camera,
 * microphone) down to the least (notifications) — not by how many apps hold each.
 * A privacy view should open with what can watch or locate you, not with the
 * longest but least sensitive list.
 */
data class AppAccessReport(
    val apps: List<AppRecord>,
    val byCapability: List<CapabilityHolders>,
    val generatedAtMs: Long,
) {
    companion object {
        /** Build the report from raw records. Pure; sorts deterministically. */
        fun from(records: List<AppRecord>, nowMs: Long): AppAccessReport {
            val apps = records.sortedWith(
                compareBy({ it.label.lowercase() }, { it.packageName }),
            )

            val byCapability = PermCapability.entries
                .map { capability ->
                    val holders = records
                        .filter { capability in it.granted }
                        .sortedWith(
                            // User-installed apps first, then newest install, then name.
                            compareBy<AppRecord> { it.isSystem }
                                .thenByDescending { it.firstInstallMs }
                                .thenBy { it.label.lowercase() },
                        )
                    CapabilityHolders(capability, holders)
                }
                .filter { it.apps.isNotEmpty() }

            return AppAccessReport(apps = apps, byCapability = byCapability, generatedAtMs = nowMs)
        }
    }
}
