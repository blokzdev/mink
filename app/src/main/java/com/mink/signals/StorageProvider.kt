package com.mink.signals

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import android.text.format.Formatter
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider

/**
 * Volume capacity and metadata. Total capacity is device specific and free
 * space is user specific, so together they read as a slowly changing
 * identifier. Nothing here needs a permission.
 */
class StorageProvider(
    private val ctx: ProviderContext,
) : SignalProvider {

    override val category: SignalCategory = SignalCategory.STORAGE

    override suspend fun collect(): List<FingerprintSignal> {
        val signals = mutableListOf<FingerprintSignal>()
        val appContext = ctx.appContext

        val dataPath = appContext.filesDir?.absolutePath ?: appContext.dataDir?.absolutePath
        val dataStat = dataPath?.let { statFor(it) }
        if (dataStat != null) {
            val total = dataStat.blockSizeLong * dataStat.blockCountLong
            val free = dataStat.blockSizeLong * dataStat.availableBlocksLong
            val used = total - free
            signals += FingerprintSignal.make(
                key = "internalCapacity",
                category = category,
                name = "Internal storage",
                value = "${format(used)} used of ${format(total)}",
                rationale =
                    "The total size of internal storage names your hardware tier, and the free " +
                        "space is user specific. Both change slowly, so they correlate across " +
                        "sessions. Read from StatFs.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = listOf(
                    SignalEntry("Total", format(total)),
                    SignalEntry("Free", format(free)),
                    SignalEntry("Used", format(used)),
                    SignalEntry("Block size", "${dataStat.blockSizeLong} bytes"),
                ),
            )
        }

        val externalDir = runCatching { appContext.getExternalFilesDir(null) }.getOrNull()
        val externalStat = externalDir?.absolutePath?.let { statFor(it) }
        if (externalStat != null) {
            val total = externalStat.blockSizeLong * externalStat.blockCountLong
            val free = externalStat.blockSizeLong * externalStat.availableBlocksLong
            signals += FingerprintSignal.make(
                key = "externalCapacity",
                category = category,
                name = "Shared storage",
                value = "${format(free)} free of ${format(total)}",
                rationale =
                    "The shared storage volume backing photos and downloads. Read from StatFs.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = listOf(
                    SignalEntry("Total", format(total)),
                    SignalEntry("Free", format(free)),
                ),
            )
        }

        val storageManager = runCatching {
            appContext.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        }.getOrNull()

        if (storageManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && dataPath != null) {
            val uuid = runCatching {
                storageManager.getUuidForPath(java.io.File(dataPath)).toString()
            }.getOrNull()
            if (!uuid.isNullOrBlank()) {
                signals += FingerprintSignal.make(
                    key = "uuid",
                    category = category,
                    name = "Volume UUID",
                    value = uuid,
                    rationale =
                        "The identifier of the volume holding this app. Read from StorageManager.",
                )
            }
        }

        if (storageManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val primary = runCatching { storageManager.primaryStorageVolume }.getOrNull()
            if (primary != null) {
                val emulated = runCatching { primary.isEmulated }.getOrDefault(true)
                val removable = runCatching { primary.isRemovable }.getOrDefault(false)
                signals += FingerprintSignal.make(
                    key = "primaryVolume",
                    category = category,
                    name = "Primary volume",
                    value = buildString {
                        append(if (emulated) "emulated" else "physical")
                        append(", ")
                        append(if (removable) "removable" else "built in")
                    },
                    rationale =
                        "Whether your primary storage is emulated or a physical card, and whether " +
                            "it is adoptable. Read from StorageManager.",
                )
            }
        }

        val encrypted = readEncryptionState()
        if (encrypted != null) {
            signals += FingerprintSignal.make(
                key = "encryption",
                category = category,
                name = "Encryption",
                value = encrypted,
                rationale = "Whether device storage is encrypted.",
            )
        }

        if (signals.isEmpty()) {
            signals += FingerprintSignal.make(
                key = "unavailable",
                category = category,
                name = "Storage",
                value = "unavailable",
                rationale = "The system did not return readable storage volumes.",
            )
        }

        return signals
    }

    private fun statFor(path: String): StatFs? = runCatching { StatFs(path) }.getOrNull()

    private fun readEncryptionState(): String? {
        // Modern Android (7+) is always encrypted; report that plainly.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) "encrypted" else null
    }

    private fun format(bytes: Long): String =
        runCatching { Formatter.formatFileSize(ctx.appContext, bytes) }
            .getOrDefault("$bytes bytes")
}
