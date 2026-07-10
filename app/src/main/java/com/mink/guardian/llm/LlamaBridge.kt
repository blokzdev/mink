package com.mink.guardian.llm

import android.util.Log

/**
 * The thin JNI seam over llama.cpp. Each `external fun` is implemented in
 * `cpp/mink_llm.cpp` and exposed by the `mink_llm` shared library.
 *
 * The library is optional. If it was not vendored and built (the app ships
 * fine without it), [System.loadLibrary] throws and [isAvailable] stays false;
 * every caller then degrades to the deterministic rules engine. No native
 * method is ever invoked when [isAvailable] is false.
 */
object LlamaBridge {

    private const val TAG = "LlamaBridge"

    /** True only when the native library loaded successfully at class init. */
    @JvmField
    val isAvailable: Boolean = runCatching {
        System.loadLibrary("mink_llm")
        true
    }.getOrElse { error ->
        Log.i(TAG, "Native guardian bridge unavailable, using rules engine: ${error.message}")
        false
    }

    /**
     * Load a GGUF model and create an inference context.
     *
     * @return an opaque handle (> 0) on success, or 0 on failure.
     */
    external fun nativeInit(
        modelPath: String,
        nCtx: Int,
        nThreads: Int,
        nGpuLayers: Int,
    ): Long

    /**
     * Evaluate a full prompt into the context, priming it for sampling.
     *
     * @return the number of prompt tokens accepted, or a negative value on error.
     */
    external fun nativePrompt(handle: Long, prompt: String): Int

    /**
     * Sample and decode the next token.
     *
     * @return the decoded token piece, or an empty string at end of stream.
     */
    external fun nativeSampleToken(handle: Long, temperature: Float, topP: Float): String

    /** Clear the sampled sequence so the same context can serve a new prompt. */
    external fun nativeResetContext(handle: Long)

    /** Release the context and model. Safe to call once per [nativeInit]. */
    external fun nativeFree(handle: Long)
}
