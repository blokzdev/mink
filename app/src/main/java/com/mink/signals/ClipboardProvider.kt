package com.mink.signals

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.PermissionKind
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads the clipboard's metadata. Newer Android releases block reading the
 * actual content unless the app is in the foreground, so Mink reports only the
 * description: whether a clip is present, its content types, its label, and
 * when it was set. Mink never reads what you copied.
 */
class ClipboardProvider(ctx: ProviderContext) : SignalProvider {

    private val appContext: Context = ctx.appContext

    override val category: SignalCategory = SignalCategory.CLIPBOARD
    override val permission: PermissionKind? = null

    override suspend fun collect(): List<FingerprintSignal> {
        val signals = mutableListOf<FingerprintSignal>()

        val clipboard = runCatching {
            appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        }.getOrNull()

        if (clipboard == null) {
            signals += FingerprintSignal.make(
                key = "unavailable",
                category = category,
                name = "Clipboard service",
                value = "unavailable",
                rationale = "The clipboard service could not be read on this device.",
            )
            return signals
        }

        val hasClip = runCatching { clipboard.hasPrimaryClip() }.getOrDefault(false)
        signals += FingerprintSignal.make(
            key = "hasClip",
            category = category,
            name = "Clipboard has content",
            value = hasClip.toString(),
            rationale = "Whether something is on the clipboard right now. Mink checks only that a " +
                "clip exists, not what it holds.",
        )

        if (!hasClip) return signals

        val description = runCatching { clipboard.primaryClipDescription }.getOrNull()
        if (description != null) {
            val mimeTypes = (0 until description.mimeTypeCount).map { description.getMimeType(it) }
            if (mimeTypes.isNotEmpty()) {
                signals += FingerprintSignal.make(
                    key = "mimeTypes",
                    category = category,
                    name = "Content types",
                    value = mimeTypes.joinToString(", "),
                    rationale = "The kinds of content on the clipboard, such as plain text or a " +
                        "URL. This is metadata only.",
                    displayHint = DisplayHint.TAGS,
                    entries = mimeTypes.map { SignalEntry(it, "") },
                )
            }

            val label = description.label?.toString().orEmpty()
            if (label.isNotBlank()) {
                signals += FingerprintSignal.make(
                    key = "label",
                    category = category,
                    name = "Clip label",
                    value = label,
                    rationale = "The label the source app attached to the clip. It names the app, " +
                        "not the copied content.",
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching {
                    val timestamp = description.timestamp
                    if (timestamp > 0L) {
                        val formatted = SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            Locale.US,
                        ).format(Date(timestamp))
                        signals += FingerprintSignal.make(
                            key = "timestamp",
                            category = category,
                            name = "Last copied",
                            value = formatted,
                            rationale = "When the current clip was set. The timing of a copy can " +
                                "correlate you across apps.",
                        )
                    }
                }
            }
        }

        return signals
    }
}
