package com.mink.signals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the group C passive providers (fonts, voices, cpu, gpu,
 * telephony, system settings). Only the helpers that avoid the Android framework
 * are exercised here, so they run on the plain JVM under testDebugUnitTest.
 */
class PassiveGroupCFormatTest {

    // ---- FontsProvider ----

    @Test
    fun isFontFile_acceptsCommonExtensions() {
        assertTrue(FontsProvider.isFontFile("Roboto-Regular.ttf"))
        assertTrue(FontsProvider.isFontFile("NotoSans.OTF"))
        assertTrue(FontsProvider.isFontFile("DroidSans.ttc"))
        assertFalse(FontsProvider.isFontFile("readme.txt"))
        assertFalse(FontsProvider.isFontFile("fonts.xml"))
    }

    @Test
    fun familyNames_stripsStyleAndExtensionAndDedupes() {
        val families = FontsProvider.familyNames(
            listOf("Roboto-Regular.ttf", "Roboto-Bold.ttf", "NotoSans-Italic.otf"),
        )
        assertEquals(listOf("NotoSans", "Roboto"), families)
    }

    // ---- CpuProvider ----

    @Test
    fun formatMhz_convertsKhz() {
        assertEquals("1800 MHz", CpuProvider.formatMhz(1_800_000))
        assertEquals("unknown", CpuProvider.formatMhz(0))
        assertEquals("unknown", CpuProvider.formatMhz(-5))
    }

    @Test
    fun parseCpuInfo_extractsHardwareFeaturesAndDedupesCores() {
        val raw = """
            processor	: 0
            Features	: fp asimd aes
            CPU implementer	: 0x41
            CPU part	: 0xd05

            processor	: 1
            CPU implementer	: 0x41
            CPU part	: 0xd05

            processor	: 2
            CPU implementer	: 0x41
            CPU part	: 0xd0a

            Hardware	: Qualcomm Technologies, Inc SDM845
        """.trimIndent()

        val info = CpuProvider.parseCpuInfo(raw)
        assertEquals("Qualcomm Technologies, Inc SDM845", info.hardware)
        assertEquals(listOf("fp", "asimd", "aes"), info.features)
        assertEquals(2, info.implementerParts.size)
        assertEquals("impl 0x41 part 0xd05" to 2, info.implementerParts[0])
        assertEquals("impl 0x41 part 0xd0a" to 1, info.implementerParts[1])
    }

    @Test
    fun parseCpuInfo_blankInputIsEmpty() {
        val info = CpuProvider.parseCpuInfo("")
        assertEquals(null, info.hardware)
        assertTrue(info.features.isEmpty())
        assertTrue(info.implementerParts.isEmpty())
    }

    // ---- GpuProvider ----

    @Test
    fun parseExtensions_splitsSortsAndDedupes() {
        val exts = GpuProvider.parseExtensions("GL_OES_texture GL_EXT_debug  GL_OES_texture")
        assertEquals(listOf("GL_EXT_debug", "GL_OES_texture"), exts)
    }

    @Test
    fun parseExtensions_nullOrBlankIsEmpty() {
        assertTrue(GpuProvider.parseExtensions(null).isEmpty())
        assertTrue(GpuProvider.parseExtensions("   ").isEmpty())
    }

    // ---- SystemSettingsProvider ----

    @Test
    fun formatScale_rendersWholeAndFractional() {
        assertEquals("1x", SystemSettingsProvider.formatScale(1.0f))
        assertEquals("0x", SystemSettingsProvider.formatScale(0.0f))
        assertEquals("0.5x", SystemSettingsProvider.formatScale(0.5f))
    }

    @Test
    fun shortenComponent_keepsPackage() {
        assertEquals(
            "com.example.keyboard",
            SystemSettingsProvider.shortenComponent("com.example.keyboard/.InputService"),
        )
        assertEquals("plain", SystemSettingsProvider.shortenComponent("plain"))
    }
}
