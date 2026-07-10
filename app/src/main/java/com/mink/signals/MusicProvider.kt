package com.mink.signals

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import android.text.format.Formatter
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
 * The music library, read through [MediaStore.Audio]. Mink counts tracks, sums
 * their size, and tallies the distinct artists, albums, and genres. Your taste
 * is a signal ad and recommendation networks pay for. It reads no file paths and
 * plays nothing.
 */
class MusicProvider(
    private val ctx: ProviderContext,
) : SignalProvider {

    override val category: SignalCategory = SignalCategory.MUSIC

    override suspend fun collect(): List<FingerprintSignal> {
        if (!PermissionedFormat.isGranted(ctx, permission)) {
            return PermissionedFormat.gateClosed(category)
        }

        val resolver = ctx.appContext.contentResolver
        val signals = mutableListOf<FingerprintSignal>()

        val summary = summarize(resolver)

        signals += FingerprintSignal.make(
            key = "count",
            category = category,
            name = "Track count",
            value = summary.count.toString(),
            rationale =
                "How many music tracks sit on this phone. The size of a library hints at how much " +
                    "of a listener you are.",
        )

        if (summary.count == 0) return signals

        signals += FingerprintSignal.make(
            key = "size",
            category = category,
            name = "Total size",
            value = runCatching {
                Formatter.formatFileSize(ctx.appContext, summary.totalBytes)
            }.getOrDefault("${summary.totalBytes} bytes"),
            rationale = "The combined size of your music files on disk.",
        )

        signals += FingerprintSignal.make(
            key = "artists",
            category = category,
            name = "Distinct artists",
            value = summary.artists.size.toString(),
            rationale =
                "How many different artists you keep. The breadth of a collection is itself a " +
                    "taste signal.",
        )

        signals += FingerprintSignal.make(
            key = "albums",
            category = category,
            name = "Distinct albums",
            value = summary.albums.size.toString(),
            rationale = "How many different albums live in your library.",
        )

        val genres = genres(resolver)
        if (genres.isNotEmpty()) {
            val sorted = genres.toSortedSet()
            signals += FingerprintSignal.make(
                key = "genres",
                category = category,
                name = "Genres",
                value = sorted.joinToString(", "),
                rationale =
                    "The genres tagged across your tracks. This is the taste profile that ad and " +
                        "recommendation networks value.",
                displayHint = DisplayHint.TAGS,
                entries = sorted.map { SignalEntry(it, "") },
            )
        }

        if (summary.earliest != null && summary.latest != null) {
            val earliest = formatDate(summary.earliest)
            val latest = formatDate(summary.latest)
            signals += FingerprintSignal.make(
                key = "dateRange",
                category = category,
                name = "Added range",
                value = "$earliest to $latest",
                rationale =
                    "The span from when your oldest track was added to your newest. It sketches " +
                        "how your library grew.",
                displayHint = DisplayHint.COMPOUND,
                entries = listOf(
                    SignalEntry("Oldest", earliest),
                    SignalEntry("Newest", latest),
                ),
            )
        }

        return signals
    }

    private data class Summary(
        val count: Int,
        val totalBytes: Long,
        val artists: Set<String>,
        val albums: Set<String>,
        val earliest: Long?,
        val latest: Long?,
    )

    private fun summarize(resolver: ContentResolver): Summary = runCatching {
        var count = 0
        var totalBytes = 0L
        val artists = sortedSetOf<String>()
        val albums = sortedSetOf<String>()
        var earliest: Long? = null
        var latest: Long? = null
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DATE_ADDED,
            ),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null,
        )?.use { cursor ->
            val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
            val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val dateCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                count++
                if (sizeCol >= 0) totalBytes += cursor.getLong(sizeCol)
                if (artistCol >= 0) {
                    cursor.getString(artistCol)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?.let { artists += it }
                }
                if (albumCol >= 0) {
                    cursor.getString(albumCol)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?.let { albums += it }
                }
                if (dateCol >= 0) {
                    // DATE_ADDED is in seconds since the epoch.
                    val addedSeconds = cursor.getLong(dateCol)
                    if (addedSeconds > 0) {
                        val addedMs = addedSeconds * 1000L
                        if (earliest == null || addedMs < earliest!!) earliest = addedMs
                        if (latest == null || addedMs > latest!!) latest = addedMs
                    }
                }
            }
        }
        Summary(count, totalBytes, artists, albums, earliest, latest)
    }.getOrDefault(Summary(0, 0L, emptySet(), emptySet(), null, null))

    private fun genres(resolver: ContentResolver): Set<String> = runCatching {
        val names = sortedSetOf<String>()
        val uri: Uri = MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI
        resolver.query(
            uri,
            arrayOf(MediaStore.Audio.Genres.NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val nameCol = cursor.getColumnIndex(MediaStore.Audio.Genres.NAME)
            while (cursor.moveToNext()) {
                if (nameCol >= 0) cursor.getString(nameCol)?.takeIf { it.isNotBlank() }?.let { names += it }
            }
        }
        names
    }.getOrDefault(emptySet())

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
}
