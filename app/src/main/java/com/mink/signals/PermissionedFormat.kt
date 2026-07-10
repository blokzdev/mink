package com.mink.signals

import com.mink.core.model.FingerprintSignal
import com.mink.core.model.PermissionKind
import com.mink.core.model.SignalCategory
import com.mink.core.provider.ProviderContext
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Small helpers shared by the permissioned providers: the granted-state check,
 * the single "gate closed" signal every provider returns when its permission is
 * missing, and pure coordinate coarsening. Kept free of framework state so the
 * branching can be unit tested off device.
 */
internal object PermissionedFormat {

    /**
     * Mirrors [com.mink.data.PermissionController.isGranted]: a kind with no
     * manifest permissions on this OS level is treated as open, otherwise any
     * one of its permissions being held opens the gate.
     */
    fun isGranted(ctx: ProviderContext, permission: PermissionKind?): Boolean {
        val perms = permission?.manifestPermissions ?: return true
        return perms.isEmpty() || perms.any { ctx.hasPermission(it) }
    }

    /**
     * The one signal a provider returns when its permission has not been
     * granted. It never throws and reads nothing; it only explains the gate.
     */
    fun gateClosed(category: SignalCategory): List<FingerprintSignal> {
        val permission = category.permission
        val what = (permission?.title ?: category.title).lowercase()
        val rationale = buildString {
            append("Mink reads nothing here until you grant ")
            append(what)
            append(" access. ")
            if (permission != null) append(permission.rationale)
        }.trim()
        return listOf(
            FingerprintSignal.make(
                key = "gate",
                category = category,
                name = "Permission needed",
                value = "Gate closed",
                rationale = rationale,
            ),
        )
    }

    /**
     * Rounds a coordinate to a coarse decimal grid so the detail screen never
     * shows your exact position. Two decimals is roughly a one kilometre grid.
     */
    fun coarseCoordinate(value: Double, decimals: Int = 2): String {
        // Round on the decimal value, not the binary one: BigDecimal.valueOf
        // uses the canonical string form, so 37.425 rounds half-up to 37.43
        // instead of being dragged down by floating-point error.
        val rounded = BigDecimal.valueOf(value)
            .setScale(decimals.coerceAtLeast(0), RoundingMode.HALF_UP)
            .toDouble()
        return rounded.toString()
    }
}
