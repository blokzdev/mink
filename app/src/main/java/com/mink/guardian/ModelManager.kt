package com.mink.guardian

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Owns the on-device GGUF model file: which quant a tier needs, whether it is
 * present, and the opt-in, resumable download from HuggingFace.
 *
 * Downloads never start on their own. The user taps to download, and the
 * manager refuses a metered connection unless explicitly allowed, so the model
 * never pulls megabytes over cellular by surprise.
 */
class ModelManager(private val context: Context) {

    private val _state = MutableStateFlow(GuardianModelState())
    val state: StateFlow<GuardianModelState> = _state.asStateFlow()

    @Volatile
    private var cancelled = false

    /** The resolved model file for [tier] under filesDir/models. */
    fun modelFile(tier: GuardianTier): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        return File(dir, fileNameFor(tier))
    }

    fun quantName(tier: GuardianTier): String = when (tier) {
        GuardianTier.FULL -> "Q8_0"
        GuardianTier.LITE, GuardianTier.MINIMAL -> "Q4_K_M"
        GuardianTier.RULES_ONLY -> ""
    }

    /**
     * Published sizes of the MiniCPM5-1B GGUF quants on HuggingFace. These are
     * only a UI progress estimate for the download bar; the real completeness
     * gate is the server-reported content length inside [fetch], never this
     * figure.
     */
    fun expectedSizeBytes(tier: GuardianTier): Long = when (tier) {
        GuardianTier.FULL -> 1_153_529_216L
        GuardianTier.LITE, GuardianTier.MINIMAL -> 688_065_920L
        GuardianTier.RULES_ONLY -> 0L
    }

    /**
     * True when a fully downloaded, validated model file is present. A completed
     * download is promoted into place, so the presence of a non-empty file whose
     * head carries the GGUF magic bytes is the marker of completeness, not any
     * guessed byte count.
     */
    fun isDownloaded(tier: GuardianTier): Boolean {
        if (tier == GuardianTier.RULES_ONLY) return false
        val file = modelFile(tier)
        return file.exists() && file.length() > 0L && hasGgufMagic(file)
    }

    fun markUnsupported(message: String? = null) {
        _state.value = GuardianModelState(status = ModelStatus.UNSUPPORTED, message = message)
    }

    fun markReadyIfPresent(tier: GuardianTier) {
        if (isDownloaded(tier)) {
            _state.value = GuardianModelState(
                status = ModelStatus.READY,
                downloadProgress = 1f,
                quantName = quantName(tier),
                sizeBytes = modelFile(tier).length(),
            )
        }
    }

    fun cancel() {
        cancelled = true
    }

    /**
     * Download (or resume) the model for [tier]. Blocking on the calling
     * dispatcher; the controller runs it on IO. Never throws: any failure moves
     * [state] to FAILED with a message. Returns true when the file ends up
     * complete.
     */
    fun download(tier: GuardianTier, allowMetered: Boolean = false): Boolean {
        if (tier == GuardianTier.RULES_ONLY) {
            markUnsupported("This device runs the rules guardian, no model needed.")
            return false
        }
        if (isDownloaded(tier)) {
            markReadyIfPresent(tier)
            return true
        }
        if (!allowMetered && isMetered()) {
            _state.value = GuardianModelState(
                status = ModelStatus.FAILED,
                quantName = quantName(tier),
                message = "On a metered connection. Connect to Wi-Fi or allow metered downloads.",
            )
            return false
        }

        cancelled = false
        val target = modelFile(tier)
        val part = File(target.parentFile, target.name + ".part")
        val url = downloadUrl(tier)
        val expected = expectedSizeBytes(tier)

        _state.value = GuardianModelState(
            status = ModelStatus.DOWNLOADING,
            quantName = quantName(tier),
            sizeBytes = expected,
            downloadProgress = progressOf(part.length(), expected),
        )

        val ok = runCatching { fetch(url, part, expected, tier) }.getOrElse { error ->
            Log.w(TAG, "Model download failed: ${error.message}")
            _state.value = GuardianModelState(
                status = ModelStatus.FAILED,
                quantName = quantName(tier),
                message = "Download failed. Tap to resume.",
            )
            false
        }
        if (!ok) return false

        // Verify integrity by the GGUF magic bytes, then promote the .part file
        // into place. Completeness against the server total is already enforced
        // inside fetch, so here we only confirm the file really is a GGUF.
        _state.value = _state.value.copy(status = ModelStatus.VERIFYING)
        if (part.length() <= 0L || !hasGgufMagic(part)) {
            _state.value = GuardianModelState(
                status = ModelStatus.FAILED,
                quantName = quantName(tier),
                message = "Downloaded file failed its integrity check. Tap to resume.",
            )
            return false
        }
        val renamed = runCatching { part.renameTo(target) }.getOrDefault(false)
        if (!renamed) {
            _state.value = GuardianModelState(
                status = ModelStatus.FAILED,
                quantName = quantName(tier),
                message = "Could not finalise the model file.",
            )
            return false
        }
        _state.value = GuardianModelState(
            status = ModelStatus.READY,
            downloadProgress = 1f,
            quantName = quantName(tier),
            sizeBytes = target.length(),
        )
        return true
    }

    private fun fetch(url: String, part: File, expected: Long, tier: GuardianTier): Boolean {
        var connection: HttpURLConnection? = null
        try {
            val existing = if (part.exists()) part.length() else 0L
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
                requestMethod = "GET"
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            connection.connect()
            val code = connection.responseCode
            // A 416 on resume means the server has no bytes past what we already
            // hold: the .part is already the whole file. Treat it as complete and
            // let the caller re-validate the GGUF, rather than failing the resume.
            if (existing > 0 && code == HTTP_RANGE_NOT_SATISFIABLE) {
                return true
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IllegalStateException("HTTP $code")
            }
            val resuming = code == HttpURLConnection.HTTP_PARTIAL
            val hasContentLength = connection.contentLengthLong > 0
            // The completeness target is the server-reported total, never the
            // hardcoded estimate. Fall back to the estimate only for progress
            // display when the server does not report a length.
            val reportedTotal = when {
                hasContentLength && resuming -> existing + connection.contentLengthLong
                hasContentLength -> connection.contentLengthLong
                else -> expected
            }

            var written = if (resuming) existing else 0L
            RandomAccessFile(part, "rw").use { raf ->
                if (resuming) raf.seek(existing) else raf.setLength(0)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        if (cancelled) return false
                        val read = input.read(buffer)
                        if (read < 0) break
                        raf.write(buffer, 0, read)
                        written += read
                        _state.value = GuardianModelState(
                            status = ModelStatus.DOWNLOADING,
                            quantName = quantName(tier),
                            sizeBytes = reportedTotal,
                            downloadProgress = progressOf(written, reportedTotal),
                        )
                    }
                }
                // Guard against a stream that ended before the server-reported
                // total. Leave the .part in place so a later resume can finish it.
                if (hasContentLength && written < reportedTotal) {
                    throw IllegalStateException("Incomplete download: $written of $reportedTotal bytes")
                }
            }
            return true
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * True when [file]'s first four bytes are the ASCII "GGUF" magic. Reads only
     * the head, so it is cheap even for a multi-gigabyte model.
     */
    private fun hasGgufMagic(file: File): Boolean = fileHasGgufMagic(file)

    private fun isMetered(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(true)

    private fun progressOf(done: Long, total: Long): Float =
        if (total <= 0) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    // HuggingFace resolve URLs are case-sensitive; these must match the exact
    // filenames published in openbmb/MiniCPM5-1B-GGUF or every download 404s.
    private fun fileNameFor(tier: GuardianTier): String = when (tier) {
        GuardianTier.FULL -> "MiniCPM5-1B-Q8_0.gguf"
        GuardianTier.LITE, GuardianTier.MINIMAL -> "MiniCPM5-1B-Q4_K_M.gguf"
        GuardianTier.RULES_ONLY -> "none.gguf"
    }

    private fun downloadUrl(tier: GuardianTier): String =
        "$HF_BASE/${fileNameFor(tier)}?download=true"

    private companion object {
        const val TAG = "ModelManager"
        const val HF_BASE =
            "https://huggingface.co/openbmb/MiniCPM5-1B-GGUF/resolve/main"

        // HttpURLConnection has no constant for 416 Range Not Satisfiable.
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}

// The GGUF file format begins with the ASCII bytes "GGUF".
private val GGUF_MAGIC = byteArrayOf(
    'G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(),
)

/**
 * True when [file] exists and its first four bytes are the ASCII "GGUF" magic.
 * Reads only the head, so it is cheap even for a multi-gigabyte model. Any I/O
 * failure is treated as "not a GGUF" rather than propagating.
 */
internal fun fileHasGgufMagic(file: File): Boolean = runCatching {
    if (!file.exists() || file.length() < GGUF_MAGIC.size) return false
    file.inputStream().use { input ->
        val head = ByteArray(GGUF_MAGIC.size)
        var off = 0
        while (off < head.size) {
            val read = input.read(head, off, head.size - off)
            if (read < 0) break
            off += read
        }
        off == head.size && head.contentEquals(GGUF_MAGIC)
    }
}.getOrDefault(false)
