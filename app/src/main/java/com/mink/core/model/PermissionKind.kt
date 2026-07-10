package com.mink.core.model

import android.Manifest
import android.os.Build

/**
 * The Android runtime permission gate a category has to pass before it can
 * produce any signals. Each kind carries the concrete manifest permission(s)
 * to request and the educational rationale shown on the permission gate.
 */
enum class PermissionKind {
    LOCATION,
    CAMERA,
    BLUETOOTH,
    NEARBY_WIFI,
    CONTACTS,
    CALENDAR,
    MEDIA_IMAGES,
    MEDIA_AUDIO,
    PHYSICAL_ACTIVITY,
    MICROPHONE,
    NOTIFICATIONS;

    val title: String
        get() = when (this) {
            LOCATION -> "Location"
            CAMERA -> "Camera"
            BLUETOOTH -> "Bluetooth"
            NEARBY_WIFI -> "Nearby Wi-Fi"
            CONTACTS -> "Contacts"
            CALENDAR -> "Calendar"
            MEDIA_IMAGES -> "Photos & Media"
            MEDIA_AUDIO -> "Music & Audio"
            PHYSICAL_ACTIVITY -> "Physical Activity"
            MICROPHONE -> "Microphone"
            NOTIFICATIONS -> "Notifications"
        }

    /**
     * The manifest permission strings to request for this kind, resolved for
     * the running OS version (media and nearby-device permissions were split
     * out in newer Android releases).
     */
    val manifestPermissions: List<String>
        get() = when (this) {
            LOCATION -> listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            CAMERA -> listOf(Manifest.permission.CAMERA)
            BLUETOOTH -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                )
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            NEARBY_WIFI -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            CONTACTS -> listOf(Manifest.permission.READ_CONTACTS)
            CALENDAR -> listOf(Manifest.permission.READ_CALENDAR)
            MEDIA_IMAGES -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            MEDIA_AUDIO -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            PHYSICAL_ACTIVITY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                listOf(Manifest.permission.ACTIVITY_RECOGNITION)
            } else {
                emptyList()
            }
            MICROPHONE -> listOf(Manifest.permission.RECORD_AUDIO)
            NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyList()
            }
        }

    val rationale: String
        get() = when (this) {
            LOCATION ->
                "A single coordinate reading can pin you to within a few meters. Altitude " +
                    "narrows it further, often down to a specific floor of a building."
            CAMERA ->
                "The list of cameras on your phone, with their focal lengths and sensor " +
                    "sizes, often pinpoints the exact model."
            BLUETOOTH ->
                "Scanning for nearby Bluetooth devices reveals the speakers, headphones, " +
                    "watches, and other gear around you, often including their owners' names."
            NEARBY_WIFI ->
                "Scanning nearby Wi-Fi lists the access points around you. That set of " +
                    "networks is often unique to your home or office."
            CONTACTS ->
                "The number of contacts and the labels you use hint at your social circle " +
                    "and relationships."
            CALENDAR ->
                "The number of events and the accounts you sync reflect your routine and " +
                    "the services you use."
            MEDIA_IMAGES ->
                "Photo counts and the geotags embedded in your images can reveal where " +
                    "you've been, without an app needing to open a single picture."
            MEDIA_AUDIO ->
                "Music library counts and your most-played artists are taste signals that " +
                    "ad and recommendation networks pay for."
            PHYSICAL_ACTIVITY ->
                "Step counts and detected activities stream a picture of your day: when " +
                    "you move, rest, drive, or walk."
            MICROPHONE ->
                "Microphone access lets an app sample your surroundings. Even ambient " +
                    "noise carries clues about where you are."
            NOTIFICATIONS ->
                "The companion posts alerts through the notification channel. This grants " +
                    "nothing about you; it only lets Mink speak up."
        }
}
