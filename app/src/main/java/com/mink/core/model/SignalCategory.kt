package com.mink.core.model

/**
 * The canonical list of fingerprinting surfaces Mink exposes. Every provider
 * maps to exactly one case, and the UI iterates this enum to build the home
 * list. Declaration order is display order.
 *
 * This is the Android analogue of Loupe's iOS SignalCategory: passive readings
 * that any app can take with no prompt, permissioned readings gated behind a
 * runtime prompt, and advanced side-channel techniques.
 */
enum class SignalCategory(
    val id: String,
    val title: String,
    val subtitle: String,
    /** Material icon name; the UI layer maps this to an ImageVector. */
    val iconKey: String,
    val sensitivity: Sensitivity,
    val permission: PermissionKind? = null,
) {
    // ---- Passive: any app can read these with no prompt ----
    DEVICE_IDENTITY(
        "deviceIdentity", "Device Identity", "Hardware model and stable identifiers",
        "smartphone", Sensitivity.PASSIVE,
    ),
    SYSTEM_INFO(
        "systemInfo", "System Info", "Kernel, build fingerprint, and OS state",
        "memory", Sensitivity.PASSIVE,
    ),
    APP_INFO(
        "appInfo", "App & Package", "App build, installer, and signing info",
        "widgets", Sensitivity.PASSIVE,
    ),
    BATTERY(
        "battery", "Battery & Power", "Charge, power, and thermal state",
        "battery_charging_full", Sensitivity.PASSIVE,
    ),
    STORAGE(
        "storage", "Storage", "Volume capacity and metadata",
        "sd_storage", Sensitivity.PASSIVE,
    ),
    DISPLAY(
        "display", "Display", "Screen specs and rendering capabilities",
        "smartphone", Sensitivity.PASSIVE,
    ),
    AUDIO(
        "audio", "Audio", "Audio routes, devices, and capabilities",
        "volume_up", Sensitivity.PASSIVE,
    ),
    LOCALE(
        "locale", "Locale & Region", "Language, region, and time settings",
        "language", Sensitivity.PASSIVE,
    ),
    ACCESSIBILITY(
        "accessibility", "Accessibility", "System accessibility flags",
        "accessibility_new", Sensitivity.PASSIVE,
    ),
    CLIPBOARD(
        "clipboard", "Clipboard", "Clipboard content types and activity",
        "content_paste", Sensitivity.PASSIVE,
    ),
    SENSORS(
        "sensors", "Sensors", "The accelerometer, gyroscope, and other sensors",
        "sensors", Sensitivity.PASSIVE,
    ),
    NETWORK(
        "network", "Network", "Interfaces, addresses, and VPN signals",
        "lan", Sensitivity.PASSIVE,
    ),
    FONTS(
        "fonts", "Fonts", "Installed system fonts",
        "text_fields", Sensitivity.PASSIVE,
    ),
    VOICES(
        "voices", "Installed Voices", "Text-to-speech engines and voices",
        "record_voice_over", Sensitivity.PASSIVE,
    ),
    CPU(
        "cpu", "Processor", "CPU cores, ABI, and features",
        "developer_board", Sensitivity.PASSIVE,
    ),
    GPU(
        "gpu", "Graphics & GPU", "OpenGL renderer, vendor, and extensions",
        "view_in_ar", Sensitivity.PASSIVE,
    ),
    TELEPHONY(
        "telephony", "Telephony", "Carrier, SIM, and radio info",
        "cell_tower", Sensitivity.PASSIVE,
    ),
    SYSTEM_SETTINGS(
        "systemSettings", "System Settings", "Developer, animation, and secure flags",
        "settings", Sensitivity.PASSIVE,
    ),

    // ---- Needs Permission: an Android prompt gates these ----
    LOCATION(
        "location", "Location", "Coordinate and movement data",
        "location_on", Sensitivity.PERMISSIONED, PermissionKind.LOCATION,
    ),
    CAMERA(
        "camera", "Cameras", "Camera lineup and capabilities",
        "photo_camera", Sensitivity.PERMISSIONED, PermissionKind.CAMERA,
    ),
    BLUETOOTH(
        "bluetooth", "Bluetooth", "Adapter state and bonded devices",
        "bluetooth", Sensitivity.PERMISSIONED, PermissionKind.BLUETOOTH,
    ),
    NEARBY_WIFI(
        "nearbyWifi", "Nearby Wi-Fi", "Access points and network inventory",
        "wifi_find", Sensitivity.PERMISSIONED, PermissionKind.NEARBY_WIFI,
    ),
    CONTACTS(
        "contacts", "Contacts", "Address book metadata",
        "contacts", Sensitivity.PERMISSIONED, PermissionKind.CONTACTS,
    ),
    CALENDAR(
        "calendar", "Calendar", "Calendars, accounts, and events",
        "calendar_month", Sensitivity.PERMISSIONED, PermissionKind.CALENDAR,
    ),
    PHOTOS(
        "photos", "Photos", "Library counts, geotags, and locations",
        "photo_library", Sensitivity.PERMISSIONED, PermissionKind.MEDIA_IMAGES,
    ),
    MUSIC(
        "music", "Music", "Library counts and listening tastes",
        "library_music", Sensitivity.PERMISSIONED, PermissionKind.MEDIA_AUDIO,
    ),
    ACTIVITY(
        "activity", "Activity & Steps", "Step counts and detected activity",
        "directions_walk", Sensitivity.PERMISSIONED, PermissionKind.PHYSICAL_ACTIVITY,
    ),

    // ---- Advanced: side-channel uses of public APIs ----
    INSTALLED_APPS(
        "installedApps", "Installed Apps", "The other apps you have installed",
        "apps", Sensitivity.ADVANCED,
    ),
    ACCOUNTS(
        "accounts", "Accounts", "On-device account types and providers",
        "manage_accounts", Sensitivity.ADVANCED,
    ),
    WEB_VIEW_FINGERPRINT(
        "webViewFingerprint", "WebView Fingerprint", "Browser-style canvas and WebGL",
        "public", Sensitivity.ADVANCED,
    );

    companion object {
        fun fromId(id: String): SignalCategory? = entries.firstOrNull { it.id == id }
    }
}
