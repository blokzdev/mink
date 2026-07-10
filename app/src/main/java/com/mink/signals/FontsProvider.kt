package com.mink.signals

import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.PermissionKind
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider
import java.io.File

/**
 * Enumerates the font files shipped in the system image. The exact set of
 * installed fonts is a classic browser fingerprint, and on Android it is just as
 * telling: a Samsung build, a Pixel build, and a carrier build each carry a
 * distinct lineup, and any app can read the directory with no prompt. Mink shows
 * the file names and the family count, never your documents.
 */
class FontsProvider(ctx: ProviderContext) : SignalProvider {

    @Suppress("unused")
    private val providerContext = ctx

    override val category: SignalCategory = SignalCategory.FONTS
    override val permission: PermissionKind? = null

    override suspend fun collect(): List<FingerprintSignal> {
        val files = readFontFiles()
        val signals = mutableListOf<FingerprintSignal>()

        signals += FingerprintSignal.make(
            key = "count",
            category = category,
            name = "Installed fonts",
            value = if (files.isEmpty()) "unavailable" else "${files.size} files",
            rationale = "How many font files your system carries. The lineup differs by maker, " +
                "model, and region, so the count alone starts to narrow you down. Read from " +
                "/system/fonts.",
        )

        if (files.isNotEmpty()) {
            val families = familyNames(files)
            signals += FingerprintSignal.make(
                key = "families",
                category = category,
                name = "Font families",
                value = "${families.size} families",
                rationale = "The distinct font families present. Together they read almost like a " +
                    "hash of your build.",
                displayHint = DisplayHint.TAGS,
                entries = families.take(MAX_SHOWN).map { SignalEntry(it, "") },
            )

            signals += FingerprintSignal.make(
                key = "files",
                category = category,
                name = "Font files",
                value = "${files.size} files present",
                rationale = "A sample of the font file names any app can enumerate. The full set " +
                    "rarely matches across different builds.",
                displayHint = DisplayHint.TAGS,
                entries = files.take(MAX_SHOWN).map { SignalEntry(it, "") },
            )
        }

        return signals
    }

    private fun readFontFiles(): List<String> {
        val names = sortedSetOf<String>()
        for (dir in FONT_DIRS) {
            runCatching {
                val listing = File(dir).takeIf { it.isDirectory }?.listFiles() ?: return@runCatching
                for (file in listing) {
                    if (file.isFile && isFontFile(file.name)) {
                        names += file.name
                    }
                }
            }
        }
        return names.toList()
    }

    companion object {
        private const val MAX_SHOWN = 60
        private val FONT_DIRS = listOf("/system/fonts", "/system/font", "/product/fonts")

        /** Whether a file name looks like a font file. Pure and testable. */
        fun isFontFile(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".ttf") || lower.endsWith(".otf") ||
                lower.endsWith(".ttc") || lower.endsWith(".otc")
        }

        /**
         * Derives family-style names from font file names by stripping the
         * extension and any style suffix after a hyphen (Roboto-Bold -> Roboto).
         * Pure and testable.
         */
        fun familyNames(files: List<String>): List<String> {
            val families = sortedSetOf<String>()
            for (file in files) {
                val base = file.substringBeforeLast('.')
                val family = base.substringBefore('-').trim()
                if (family.isNotEmpty()) families += family
            }
            return families.toList()
        }
    }
}
