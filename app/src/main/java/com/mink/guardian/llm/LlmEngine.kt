package com.mink.guardian.llm

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Sampling parameters for one generation. The temperature and top-p defaults
 * follow the MiniCPM5 deploy guidance: thinking runs a little hotter for
 * exploratory reasoning, no-think stays tighter for direct answers.
 */
data class GenParams(
    val temperature: Float,
    val topP: Float,
    val maxTokens: Int = 512,
    val thinking: Boolean = false,
) {
    companion object {
        fun think(maxTokens: Int = 768) =
            GenParams(temperature = 0.9f, topP = 0.95f, maxTokens = maxTokens, thinking = true)

        fun noThink(maxTokens: Int = 512) =
            GenParams(temperature = 0.7f, topP = 0.95f, maxTokens = maxTokens, thinking = false)
    }
}

/**
 * High-level wrapper over [LlamaBridge]. All native calls run on a single
 * dedicated thread because a llama.cpp context is not safe to touch from more
 * than one thread at a time; the dispatcher serialises load, generate, and
 * unload onto that one thread.
 *
 * Every method is exception-safe. If the native bridge is absent the engine
 * simply never loads and callers fall back to the rules engine.
 */
class LlmEngine {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mink-llm").apply { isDaemon = true }
    }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    @Volatile
    private var handle: Long = 0L

    val isLoaded: Boolean
        get() = handle != 0L

    val isBridgeAvailable: Boolean
        get() = LlamaBridge.isAvailable

    /**
     * Load a model into a fresh context. Returns true on success. A prior
     * context is released first so repeated loads do not leak.
     */
    suspend fun load(
        modelPath: String,
        nCtx: Int = 2048,
        nThreads: Int = DEFAULT_THREADS,
        nGpuLayers: Int = 0,
    ): Boolean = withContext(dispatcher) {
        if (!LlamaBridge.isAvailable) return@withContext false
        if (handle != 0L) freeLocked()
        val result = runCatching {
            LlamaBridge.nativeInit(modelPath, nCtx, nThreads, nGpuLayers)
        }.getOrDefault(0L)
        handle = result
        if (result == 0L) {
            Log.w(TAG, "nativeInit failed for $modelPath")
            false
        } else {
            true
        }
    }

    /**
     * Stream generated token pieces for [prompt]. The flow runs on the engine's
     * dedicated thread, honours cancellation between tokens, and completes when
     * the model emits end of stream or [GenParams.maxTokens] is reached.
     */
    fun generate(prompt: String, params: GenParams): Flow<String> = flow {
        if (handle == 0L || !LlamaBridge.isAvailable) return@flow
        val primed = runCatching { LlamaBridge.nativePrompt(handle, prompt) }.getOrDefault(-1)
        if (primed < 0) return@flow
        var emitted = 0
        while (emitted < params.maxTokens && currentCoroutineContext().isActive) {
            val piece = runCatching {
                LlamaBridge.nativeSampleToken(handle, params.temperature, params.topP)
            }.getOrDefault("")
            if (piece.isEmpty()) break
            emit(piece)
            emitted++
        }
        runCatching { LlamaBridge.nativeResetContext(handle) }
    }.flowOn(dispatcher)

    /** Release the model and context. */
    suspend fun unload() = withContext(dispatcher) {
        freeLocked()
    }

    private fun freeLocked() {
        val h = handle
        if (h != 0L) {
            runCatching { LlamaBridge.nativeFree(h) }
            handle = 0L
        }
    }

    companion object {
        private const val TAG = "LlmEngine"

        /** Leave a couple of cores for the UI; clamp to a sane range. */
        val DEFAULT_THREADS: Int =
            (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 8)
    }
}
