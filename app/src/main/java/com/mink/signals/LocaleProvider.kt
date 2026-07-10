package com.mink.signals

import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.PermissionKind
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Reads your language, region, and time settings. Locale is famously
 * identifying: the combination of language, country, calendar, and time zone
 * narrows the crowd quickly, and your ordered list of preferred languages
 * often makes it unique.
 */
class LocaleProvider(ctx: ProviderContext) : SignalProvider {

    private val appContext: Context = ctx.appContext

    override val category: SignalCategory = SignalCategory.LOCALE
    override val permission: PermissionKind? = null

    override suspend fun collect(): List<FingerprintSignal> {
        val signals = mutableListOf<FingerprintSignal>()

        val locale = runCatching { Locale.getDefault() }.getOrDefault(Locale.US)

        signals += FingerprintSignal.make(
            key = "identifier",
            category = category,
            name = "Locale",
            value = locale.toString(),
            rationale = "Your locale string combines language, region, and script into one " +
                "identifier that ad networks read directly.",
        )

        signals += FingerprintSignal.make(
            key = "components",
            category = category,
            name = "Language and region",
            value = buildString {
                append(locale.language.ifBlank { "unknown" })
                if (locale.country.isNotBlank()) append(", ").append(locale.country)
                if (locale.script.isNotBlank()) append(", ").append(locale.script)
                if (locale.variant.isNotBlank()) append(", ").append(locale.variant)
            },
            rationale = "Read from your default locale. Each part narrows the crowd you blend into.",
            displayHint = DisplayHint.KEY_VALUE,
            entries = buildList {
                add(SignalEntry("Language", locale.language.ifBlank { "unknown" }))
                if (locale.country.isNotBlank()) add(SignalEntry("Country", locale.country))
                if (locale.script.isNotBlank()) add(SignalEntry("Script", locale.script))
                if (locale.variant.isNotBlank()) add(SignalEntry("Variant", locale.variant))
            },
        )

        addPreferredLocales(signals)
        addTimeZone(signals)
        addFormatting(locale, signals)
        addFontScale(signals)

        return signals
    }

    private fun addPreferredLocales(signals: MutableList<FingerprintSignal>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching {
            val list = appContext.resources.configuration.locales
            val tags = (0 until list.size()).mapNotNull { list[it]?.toLanguageTag() }
            if (tags.isNotEmpty()) {
                signals += FingerprintSignal.make(
                    key = "preferredLocales",
                    category = category,
                    name = "Preferred locales",
                    value = tags.joinToString(", "),
                    rationale = "Your ordered list of preferred locales. The order alone is often " +
                        "unique to you.",
                    displayHint = DisplayHint.TAGS,
                    entries = tags.map { SignalEntry(it, "") },
                )
            }
        }
    }

    private fun addTimeZone(signals: MutableList<FingerprintSignal>) {
        runCatching {
            val tz = TimeZone.getDefault()
            val now = System.currentTimeMillis()
            val offset = tz.getOffset(now)
            signals += FingerprintSignal.make(
                key = "timeZone",
                category = category,
                name = "Time zone",
                value = tz.id,
                rationale = "Your time zone identifier. It points at your part of the world and, " +
                    "combined with locale, narrows things further.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = listOf(
                    SignalEntry("Identifier", tz.id),
                    SignalEntry("UTC offset", formatUtcOffset(offset)),
                    SignalEntry("Daylight saving", tz.inDaylightTime(java.util.Date(now)).toString()),
                ),
            )
        }
    }

    private fun addFormatting(locale: Locale, signals: MutableList<FingerprintSignal>) {
        runCatching {
            val is24 = DateFormat.is24HourFormat(appContext)
            signals += FingerprintSignal.make(
                key = "clock",
                category = category,
                name = "Clock format",
                value = if (is24) "24-hour" else "12-hour",
                rationale = "Whether you prefer a 24-hour or 12-hour clock. A small but stable " +
                    "preference.",
            )
        }
        runCatching {
            val calendar = Calendar.getInstance(locale)
            signals += FingerprintSignal.make(
                key = "firstDayOfWeek",
                category = category,
                name = "First day of week",
                value = dayName(calendar.firstDayOfWeek),
                rationale = "The day your week starts on. It follows your region and adds a " +
                    "distinguishing detail.",
            )
        }
    }

    private fun addFontScale(signals: MutableList<FingerprintSignal>) {
        runCatching {
            val scale = appContext.resources.configuration.fontScale
            signals += FingerprintSignal.make(
                key = "fontScale",
                category = category,
                name = "Font scale",
                value = scale.toString(),
                rationale = "Your text size preference. Most people leave it at 1.0, so any other " +
                    "value stands out.",
            )
        }
    }

    companion object {
        /** Formats a UTC offset in milliseconds as "+HH:MM". Pure and testable. */
        fun formatUtcOffset(offsetMillis: Int): String {
            val sign = if (offsetMillis < 0) "-" else "+"
            val totalMinutes = Math.abs(offsetMillis) / 60000
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return "UTC%s%02d:%02d".format(sign, hours, minutes)
        }

        /** Maps a [Calendar] day-of-week constant to its name. Pure and testable. */
        fun dayName(day: Int): String = when (day) {
            Calendar.SUNDAY -> "Sunday"
            Calendar.MONDAY -> "Monday"
            Calendar.TUESDAY -> "Tuesday"
            Calendar.WEDNESDAY -> "Wednesday"
            Calendar.THURSDAY -> "Thursday"
            Calendar.FRIDAY -> "Friday"
            Calendar.SATURDAY -> "Saturday"
            else -> "day $day"
        }
    }
}
