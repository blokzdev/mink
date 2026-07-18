package com.mink.core.model

/**
 * The Android runtime permission gate a category has to pass before it can
 * produce any signals. Each kind carries the educational rationale shown on
 * the permission gate; the concrete manifest permission strings live in the
 * app layer ([manifestPermissions] extension) so this model stays pure.
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
