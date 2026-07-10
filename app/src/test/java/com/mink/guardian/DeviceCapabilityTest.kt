package com.mink.guardian

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceCapabilityTest {

    private val gib = DeviceCapability.GIB

    @Test
    fun fullTier_needsRamAnd64BitAndNative() {
        assertEquals(
            GuardianTier.FULL,
            DeviceCapability.selectTier(8 * gib, is64Bit = true, nativeAvailable = true),
        )
    }

    @Test
    fun sevenGigExactlyIsFull() {
        assertEquals(
            GuardianTier.FULL,
            DeviceCapability.selectTier(7 * gib, is64Bit = true, nativeAvailable = true),
        )
    }

    @Test
    fun liteTierBetweenFourAndSeven() {
        assertEquals(
            GuardianTier.LITE,
            DeviceCapability.selectTier(4 * gib, is64Bit = true, nativeAvailable = true),
        )
        assertEquals(
            GuardianTier.LITE,
            DeviceCapability.selectTier(6 * gib, is64Bit = true, nativeAvailable = true),
        )
    }

    @Test
    fun minimalTierBetweenThreeAndFour() {
        assertEquals(
            GuardianTier.MINIMAL,
            DeviceCapability.selectTier(3 * gib, is64Bit = true, nativeAvailable = true),
        )
    }

    @Test
    fun belowThreeGigIsRulesOnly() {
        assertEquals(
            GuardianTier.RULES_ONLY,
            DeviceCapability.selectTier(2 * gib, is64Bit = true, nativeAvailable = true),
        )
    }

    @Test
    fun missingNativeAlwaysRulesOnly() {
        assertEquals(
            GuardianTier.RULES_ONLY,
            DeviceCapability.selectTier(16 * gib, is64Bit = true, nativeAvailable = false),
        )
    }

    @Test
    fun thirtyTwoBitAlwaysRulesOnly() {
        assertEquals(
            GuardianTier.RULES_ONLY,
            DeviceCapability.selectTier(16 * gib, is64Bit = false, nativeAvailable = true),
        )
    }
}
