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

    fun expectedSizeBytes(tier: GuardianTier): Long = when (tier) {
        // Approximate published sizes for MiniCPM5-1B GGUF quants.
        GuardianTier.FULL -> 1_320_000_000L
        GuardianTier.LITE, GuardianTier.MINIMAL -> 820_000_000L
        GuardianTier.RULES_ONLY -> 0L
    }

    /** True when the model file is present and at least its expected size. */
    fun isDownloaded(tier: GuardianTier): Boolean {
        if (tier == GuardianTier.RULES_ONLY) return false
        val file = modelFile(tier)
        return file.exists() && file.length() >= expectedSizeBytes(tier)
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

        // Verify by size, then promote the .part file into place.
        _state.value = _state.value.copy(status = ModelStatus.VERIFYING)
        if (part.length() < expected) {
            _state.value = GuardianModelState(
                status = ModelStatus.FAILED,
                quantName = quantName(tier),
                message = "Downloaded file is short. Tap to resume.",
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
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IllegalStateException("HTTP $code")
            }
            val resuming = code == HttpURLConnection.HTTP_PARTIAL
            val reportedTotal = when {
                connection.contentLengthLong > 0 && resuming -> existing + connection.contentLengthLong
                connection.contentLengthLong > 0 -> connection.contentLengthLong
                else -> expected
            }

            RandomAccessFile(part, "rw").use { raf ->
                if (resuming) raf.seek(existing) else raf.setLength(0)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(1 shl 16)
                    var written = if (resuming) existing else 0L
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
            }
            return true
        } finally {
            connection?.disconnect()
        }
    }

    private fun isMetered(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(true)

    private fun progressOf(done: Long, total: Long): Float =
        if (total <= 0) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    private fun fileNameFor(tier: GuardianTier): String = when (tier) {
        GuardianTier.FULL -> "minicpm5-1b-q8_0.gguf"
        GuardianTier.LITE, GuardianTier.MINIMAL -> "minicpm5-1b-q4_k_m.gguf"
        GuardianTier.RULES_ONLY -> "none.gguf"
    }

    private fun downloadUrl(tier: GuardianTier): String =
        "$HF_BASE/${fileNameFor(tier)}?download=true"

    private companion object {
        const val TAG = "ModelManager"
        const val HF_BASE =
            "https://huggingface.co/openbmb/MiniCPM5-1B-GGUF/resolve/main"
    }
}
