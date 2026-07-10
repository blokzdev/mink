package com.mink.signals

import android.content.ContentResolver
import android.provider.CalendarContract
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Calendars, the accounts behind them, and how busy your schedule looks, read
 * through [CalendarContract]. Mink counts events and reads the account types
 * and the time window they span. It never reads an event title, location, or
 * guest; only the shape of your routine.
 */
class CalendarProvider(
    private val ctx: ProviderContext,
) : SignalProvider {

    override val category: SignalCategory = SignalCategory.CALENDAR

    override suspend fun collect(): List<FingerprintSignal> {
        if (!PermissionedFormat.isGranted(ctx, permission)) {
            return PermissionedFormat.gateClosed(category)
        }

        val resolver = ctx.appContext.contentResolver
        val signals = mutableListOf<FingerprintSignal>()

        val calendars = calendars(resolver)
        signals += FingerprintSignal.make(
            key = "calendarCount",
            category = category,
            name = "Calendar count",
            value = calendars.size.toString(),
            rationale = "The number of calendars synced to this phone across all your accounts.",
        )

        val accountTypes = calendars.mapNotNull { it.accountType }.filter { it.isNotBlank() }.toSortedSet()
        if (accountTypes.isNotEmpty()) {
            signals += FingerprintSignal.make(
                key = "accounts",
                category = category,
                name = "Calendar accounts",
                value = accountTypes.joinToString(", "),
                rationale =
                    "The account types behind your calendars. The mix of providers reflects the " +
                        "services you rely on.",
                displayHint = DisplayHint.TAGS,
                entries = accountTypes.map { SignalEntry(it, "") },
            )
        }

        val ownerTypes = calendars.mapNotNull { it.ownerAccount?.substringAfter('@', "") }
            .filter { it.isNotBlank() }
            .toSortedSet()
        if (ownerTypes.isNotEmpty()) {
            signals += FingerprintSignal.make(
                key = "domains",
                category = category,
                name = "Account domains",
                value = ownerTypes.joinToString(", "),
                rationale =
                    "The email domains that own your calendars, with the local part dropped. A " +
                        "work domain hints at your employer.",
                displayHint = DisplayHint.TAGS,
                entries = ownerTypes.map { SignalEntry(it, "") },
            )
        }

        val window = eventWindow(resolver)
        signals += FingerprintSignal.make(
            key = "eventCount",
            category = category,
            name = "Event count",
            value = window.count.toString(),
            rationale =
                "How many events sit in your calendars. A dense schedule is itself a signal of " +
                    "how you spend your days.",
        )

        if (window.count > 0 && window.earliest != null && window.latest != null) {
            val earliest = formatDate(window.earliest)
            val latest = formatDate(window.latest)
            signals += FingerprintSignal.make(
                key = "window",
                category = category,
                name = "Event time span",
                value = "$earliest to $latest",
                rationale =
                    "The window your events cover. It shows how far back your history reaches and " +
                        "how far ahead you plan, with no titles read.",
                displayHint = DisplayHint.COMPOUND,
                entries = listOf(
                    SignalEntry("Earliest", earliest),
                    SignalEntry("Latest", latest),
                ),
            )
        }

        return signals
    }

    private data class CalendarRow(
        val accountType: String?,
        val ownerAccount: String?,
    )

    private data class EventWindow(
        val count: Int,
        val earliest: Long?,
        val latest: Long?,
    )

    private fun calendars(resolver: ContentResolver): List<CalendarRow> = runCatching {
        val rows = mutableListOf<CalendarRow>()
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.OWNER_ACCOUNT,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val typeCol = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)
            val ownerCol = cursor.getColumnIndex(CalendarContract.Calendars.OWNER_ACCOUNT)
            while (cursor.moveToNext()) {
                rows += CalendarRow(
                    accountType = if (typeCol >= 0) cursor.getString(typeCol) else null,
                    ownerAccount = if (ownerCol >= 0) cursor.getString(ownerCol) else null,
                )
            }
        }
        rows
    }.getOrDefault(emptyList())

    private fun eventWindow(resolver: ContentResolver): EventWindow = runCatching {
        var count = 0
        var earliest: Long? = null
        var latest: Long? = null
        resolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events.DTSTART),
            "${CalendarContract.Events.DTSTART} > 0",
            null,
            null,
        )?.use { cursor ->
            val startCol = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
            while (cursor.moveToNext()) {
                count++
                if (startCol < 0) continue
                val start = cursor.getLong(startCol)
                if (start <= 0) continue
                if (earliest == null || start < earliest!!) earliest = start
                if (latest == null || start > latest!!) latest = start
            }
        }
        EventWindow(count, earliest, latest)
    }.getOrDefault(EventWindow(0, null, null))

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
}
