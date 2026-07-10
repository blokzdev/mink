package com.mink.data

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.mink.core.model.PermissionKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The authorization state of a single permission kind. */
enum class PermissionStatus { GRANTED, DENIED, UNKNOWN }

/**
 * Tracks runtime permission state for every [PermissionKind]. The Activity
 * drives the actual request flow (via the Activity Result API) and reports
 * results back through [refresh]; the rest of the app observes [statuses].
 */
class PermissionController(private val appContext: Context) {

    private val _statuses = MutableStateFlow(
        PermissionKind.entries.associateWith { PermissionStatus.UNKNOWN },
    )
    val statuses: StateFlow<Map<PermissionKind, PermissionStatus>> = _statuses.asStateFlow()

    fun status(kind: PermissionKind): PermissionStatus =
        _statuses.value[kind] ?: PermissionStatus.UNKNOWN

    fun isGranted(kind: PermissionKind): Boolean =
        kind.manifestPermissions.isEmpty() ||
            kind.manifestPermissions.any { perm ->
                ContextCompat.checkSelfPermission(appContext, perm) ==
                    PackageManager.PERMISSION_GRANTED
            }

    /** Re-reads the OS state for every kind. Call from the Activity onResume. */
    fun refresh() {
        _statuses.value = PermissionKind.entries.associateWith { kind ->
            if (isGranted(kind)) PermissionStatus.GRANTED else {
                // Keep DENIED sticky once seen; UNKNOWN means never asked.
                when (_statuses.value[kind]) {
                    PermissionStatus.DENIED -> PermissionStatus.DENIED
                    else -> PermissionStatus.UNKNOWN
                }
            }
        }
    }

    /** Records the outcome of a permission request initiated by the Activity. */
    fun record(kind: PermissionKind, granted: Boolean) {
        _statuses.value = _statuses.value.toMutableMap().apply {
            this[kind] = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
        }
    }
}
