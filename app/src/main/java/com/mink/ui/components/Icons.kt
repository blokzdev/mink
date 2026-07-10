package com.mink.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.ui.graphics.vector.ImageVector
import com.mink.core.model.Sensitivity
import com.mink.core.model.SignalCategory

/**
 * Maps the snake_case Material symbol names carried by [SignalCategory.iconKey]
 * and [Sensitivity.iconKey] to concrete Compose [ImageVector]s. Unknown keys
 * fall back to a neutral category glyph so a new provider never renders blank.
 */
object MinkIcons {

    fun forKey(iconKey: String): ImageVector = when (iconKey) {
        "smartphone" -> Icons.Filled.Smartphone
        "memory" -> Icons.Filled.Memory
        "widgets" -> Icons.Filled.Widgets
        "battery_charging_full" -> Icons.Filled.BatteryChargingFull
        "sd_storage" -> Icons.Filled.SdStorage
        "volume_up" -> Icons.Filled.VolumeUp
        "language" -> Icons.Filled.Language
        "accessibility_new" -> Icons.Filled.AccessibilityNew
        "content_paste" -> Icons.Filled.ContentPaste
        "sensors" -> Icons.Filled.Sensors
        "lan" -> Icons.Filled.Lan
        "text_fields" -> Icons.Filled.TextFields
        "record_voice_over" -> Icons.Filled.RecordVoiceOver
        "developer_board" -> Icons.Filled.DeveloperBoard
        "view_in_ar" -> Icons.Filled.ViewInAr
        "cell_tower" -> Icons.Filled.CellTower
        "settings" -> Icons.Filled.Settings
        "location_on" -> Icons.Filled.LocationOn
        "photo_camera" -> Icons.Filled.PhotoCamera
        "bluetooth" -> Icons.Filled.Bluetooth
        "wifi_find" -> Icons.Filled.WifiFind
        "contacts" -> Icons.Filled.Contacts
        "calendar_month" -> Icons.Filled.CalendarMonth
        "photo_library" -> Icons.Filled.PhotoLibrary
        "library_music" -> Icons.Filled.LibraryMusic
        "directions_walk" -> Icons.Filled.DirectionsWalk
        "apps" -> Icons.Filled.Apps
        "manage_accounts" -> Icons.Filled.ManageAccounts
        "public" -> Icons.Filled.Public
        // Sensitivity tier icons.
        "visibility" -> Icons.Filled.Visibility
        "shield_lock" -> Icons.Filled.Shield
        "science" -> Icons.Filled.Science
        else -> Icons.Filled.Category
    }

    fun forCategory(category: SignalCategory): ImageVector = forKey(category.iconKey)

    fun forSensitivity(sensitivity: Sensitivity): ImageVector = forKey(sensitivity.iconKey)
}
