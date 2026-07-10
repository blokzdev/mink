package com.mink.signals

import android.content.ContentResolver
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
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
 * The photo library, read through [MediaStore]. Mink counts images, sums their
 * size, reads the date range and album names, and checks a bounded sample for
 * embedded GPS tags and camera makes. It never opens or shows a picture; the
 * geotags alone can reveal where you have been.
 */
class PhotosProvider(
    private val ctx: ProviderContext,
) : SignalProvider {

    override val category: SignalCategory = SignalCategory.PHOTOS

    override suspend fun collect(): List<FingerprintSignal> {
        if (!PermissionedFormat.isGranted(ctx, permission)) {
            return PermissionedFormat.gateClosed(category)
        }

        val resolver = ctx.appContext.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val signals = mutableListOf<FingerprintSignal>()

        val summary = summarize(resolver, collection)

        signals += FingerprintSignal.make(
            key = "count",
            category = category,
            name = "Image count",
            value = summary.count.toString(),
            rationale =
                "How many images live on this phone. The size of a library is a rough measure of " +
                    "how long you have owned the device.",
        )

        if (summary.count == 0) return signals

        signals += FingerprintSignal.make(
            key = "size",
            category = category,
            name = "Total size",
            value = runCatching {
                Formatter.formatFileSize(ctx.appContext, summary.totalBytes)
            }.getOrDefault("${summary.totalBytes} bytes"),
            rationale = "The combined size of your images on disk.",
        )

        if (summary.earliest != null && summary.latest != null) {
            val earliest = formatDate(summary.earliest)
            val latest = formatDate(summary.latest)
            signals += FingerprintSignal.make(
                key = "dateRange",
                category = category,
                name = "Date range",
                value = "$earliest to $latest",
                rationale =
                    "The span from your oldest to newest image. It sketches how far back your " +
                        "history on this phone reaches.",
                displayHint = DisplayHint.COMPOUND,
                entries = listOf(
                    SignalEntry("Oldest", earliest),
                    SignalEntry("Newest", latest),
                ),
            )
        }

        if (summary.buckets.isNotEmpty()) {
            val entries = summary.buckets.entries
                .sortedByDescending { it.value }
                .take(TOP_BUCKETS)
                .map { SignalEntry(it.key, it.value.toString()) }
            signals += FingerprintSignal.make(
                key = "buckets",
                category = category,
                name = "Top albums",
                value = entries.joinToString("\n") { "${it.label}: ${it.value}" },
                rationale =
                    "The album folders that hold the most images. Names like screenshots, camera, " +
                        "or an app name hint at how you use the phone.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = entries,
            )
        }

        val exif = sampleExif(resolver, collection)
        signals += FingerprintSignal.make(
            key = "geotagged",
            category = category,
            name = "Geotagged in sample",
            value = "${exif.geotagged} of ${exif.sampled}",
            rationale =
                "Of the newest images Mink checked, how many carry GPS coordinates in their EXIF " +
                    "data. Any app with photo access can read those locations without opening a " +
                    "single picture.",
        )

        if (exif.cameraMakes.isNotEmpty()) {
            val makes = exif.cameraMakes.toSortedSet()
            signals += FingerprintSignal.make(
                key = "cameraMakes",
                category = category,
                name = "Camera makes in sample",
                value = makes.joinToString(", "),
                rationale =
                    "The camera makers recorded in your images' EXIF data. It hints at which " +
                        "phones and cameras you have shot with.",
                displayHint = DisplayHint.TAGS,
                entries = makes.map { SignalEntry(it, "") },
            )
        }

        return signals
    }

    private data class Summary(
        val count: Int,
        val totalBytes: Long,
        val earliest: Long?,
        val latest: Long?,
        val buckets: Map<String, Int>,
    )

    private data class ExifSample(
        val sampled: Int,
        val geotagged: Int,
        val cameraMakes: List<String>,
    )

    private fun summarize(resolver: ContentResolver, collection: Uri): Summary = runCatching {
        var count = 0
        var totalBytes = 0L
        var earliest: Long? = null
        var latest: Long? = null
        val buckets = linkedMapOf<String, Int>()
        resolver.query(
            collection,
            arrayOf(
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
            val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                count++
                if (sizeCol >= 0) totalBytes += cursor.getLong(sizeCol)
                if (dateCol >= 0) {
                    val taken = cursor.getLong(dateCol)
                    if (taken > 0) {
                        if (earliest == null || taken < earliest!!) earliest = taken
                        if (latest == null || taken > latest!!) latest = taken
                    }
                }
                if (bucketCol >= 0) {
                    cursor.getString(bucketCol)?.takeIf { it.isNotBlank() }?.let { name ->
                        buckets[name] = (buckets[name] ?: 0) + 1
                    }
                }
            }
        }
        Summary(count, totalBytes, earliest, latest, buckets)
    }.getOrDefault(Summary(0, 0L, null, null, emptyMap()))

    private fun sampleExif(resolver: ContentResolver, collection: Uri): ExifSample = runCatching {
        var sampled = 0
        var geotagged = 0
        val makes = mutableListOf<String>()
        val sortOrder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            "${MediaStore.Images.Media.DATE_TAKEN} DESC LIMIT $SAMPLE_SIZE"
        } else {
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        }
        resolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
            while (cursor.moveToNext() && sampled < SAMPLE_SIZE) {
                if (idCol < 0) continue
                val id = cursor.getLong(idCol)
                val uri = Uri.withAppendedPath(collection, id.toString())
                sampled++
                runCatching {
                    resolver.openInputStream(uri)?.use { stream ->
                        val exif = ExifInterface(stream)
                        if (hasLatLong(exif)) geotagged++
                        exif.getAttribute(ExifInterface.TAG_MAKE)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { makes += it.trim() }
                    }
                }
            }
        }
        ExifSample(sampled, geotagged, makes)
    }.getOrDefault(ExifSample(0, 0, emptyList()))

    private fun hasLatLong(exif: ExifInterface): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val out = FloatArray(2)
            if (runCatching { exif.getLatLong(out) }.getOrDefault(false)) return true
        }
        val lat = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
        val lon = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
        return !lat.isNullOrBlank() && !lon.isNullOrBlank()
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))

    private companion object {
        const val SAMPLE_SIZE = 40
        const val TOP_BUCKETS = 8
    }
}
