package com.mink.core.model

import android.Manifest
import android.os.Build
import androidx.compose.ui.graphics.Color

/**
 * The Android-facing halves of the pure model enums, kept in the app layer so
 * [PermissionKind] and [Sensitivity] themselves stay platform-free (they move
 * to the pure guardian-core module). Same package as the enums, so call sites
 * only add an import of the extension.
 */

/**
 * The manifest permission strings to request for this kind, resolved for
 * the running OS version (media and nearby-device permissions were split
 * out in newer Android releases).
 */
val PermissionKind.manifestPermissions: List<String>
    get() = when (this) {
        PermissionKind.LOCATION -> listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        PermissionKind.CAMERA -> listOf(Manifest.permission.CAMERA)
        PermissionKind.BLUETOOTH -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        PermissionKind.NEARBY_WIFI -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        PermissionKind.CONTACTS -> listOf(Manifest.permission.READ_CONTACTS)
        PermissionKind.CALENDAR -> listOf(Manifest.permission.READ_CALENDAR)
        PermissionKind.MEDIA_IMAGES -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        PermissionKind.MEDIA_AUDIO -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        PermissionKind.PHYSICAL_ACTIVITY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            emptyList()
        }
        PermissionKind.MICROPHONE -> listOf(Manifest.permission.RECORD_AUDIO)
        PermissionKind.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }
    }

/** Accent colour for a sensitivity tier, resolved by the UI layer. */
val Sensitivity.tint: Color
    get() = when (this) {
        Sensitivity.PASSIVE -> Color(0xFF34C759)
        Sensitivity.PERMISSIONED -> Color(0xFFFF9500)
        Sensitivity.ADVANCED -> Color(0xFFFF2D95)
    }
