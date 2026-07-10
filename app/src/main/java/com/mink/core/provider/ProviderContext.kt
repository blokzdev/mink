package com.mink.core.provider

import android.content.Context

/**
 * The dependency bundle handed to every provider at construction. Wrapping the
 * Android [Context] keeps provider constructors uniform, which lets the
 * registry build them all with one lambda, and gives us a single seam to add
 * shared collaborators (permission checker, consent store) later without
 * touching 30 call sites.
 */
class ProviderContext(
    val appContext: Context,
) {
    /** Convenience: whether a manifest permission is currently granted. */
    fun hasPermission(permission: String): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(appContext, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}
