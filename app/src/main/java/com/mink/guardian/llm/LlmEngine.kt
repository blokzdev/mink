package com.mink.guardian.llm

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * High-level wrapper over [LlamaBridge], the concrete [TextGenerator]. All
 * native calls run on a single dedicated thread because a llama.cpp context is
 * not safe to touch from more than one thread at a time. Two layers guard the
 * shared context: the dispatcher serialises individual native calls onto that
 * one thread, and [genMutex] serialises whole generations and load/unload so
 * they never interleave on the shared handle and KV cache. The mutex matters
 * because a flow suspends at emit under backpressure, which frees the thread
 * and would otherwise let a second generation start priming the same context
 * mid-stream.
 *
 * Every method is exception-safe. If the native bridge is absent the engine
 * simply never loads and callers fall back to the rules engine. Lifecycle
 * ([load]/[unload]/[isBridgeAvailable]) is deliberately not part of
 * [TextGenerator] — the controller owns it against this concrete type.
 */
class LlmEngine : TextGenerator {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mink-llm").apply { isDaemon = true }
    }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    /**
     * Held across a whole generation and across load/unload so those flows can
     * never interleave on the shared context, even while one is suspended at emit.
     */
    private val genMutex = Mutex()

    @Volatile
    private var handle: Long = 0L

    override val isLoaded: Boolean
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
        genMutex.withLock {
            if (!LlamaBridge.isAvailable) return@withLock false
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
    }

    /**
     * Stream generated token pieces for [prompt]. The flow runs on the engine's
     * dedicated thread, honours cancellation between tokens, and completes when
     * the model emits end of stream or [GenParams.maxTokens] is reached.
     */
    override fun generate(prompt: String, params: GenParams): Flow<String> = flow {
        genMutex.withLock<Unit> {
            if (handle == 0L || !LlamaBridge.isAvailable) return@withLock
            val primed = runCatching { LlamaBridge.nativePrompt(handle, prompt) }.getOrDefault(-1)
            if (primed < 0) return@withLock
            try {
                var emitted = 0
                while (emitted < params.maxTokens && currentCoroutineContext().isActive) {
                    val piece = runCatching {
                        LlamaBridge.nativeSampleToken(handle, params.temperature, params.topP)
                    }.getOrDefault("")
                    if (piece.isEmpty()) break
                    emit(piece)
                    emitted++
                }
            } finally {
                // Reset in a finally so a collector that cancels or times out
                // mid-stream (a caller's generation budget elapsing) still leaves a
                // clean context for the next generation rather than a half-primed one.
                runCatching { LlamaBridge.nativeResetContext(handle) }
            }
        }
    }.flowOn(dispatcher)

    /** Release the model and context. */
    suspend fun unload() = withContext(dispatcher) {
        genMutex.withLock {
            freeLocked()
        }
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
