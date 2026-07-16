package com.mink

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mink.guardian.GuardianTier
import com.mink.guardian.ModelManager
import com.mink.guardian.llm.GenParams
import com.mink.guardian.llm.LlamaBridge
import com.mink.guardian.llm.LlmEngine
import com.mink.guardian.llm.MiniCpmChatFormat
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real on-device inference path end to end: the native llama.cpp
 * bridge loads a MiniCPM5-1B GGUF and streams tokens for a prompt.
 *
 * This test only runs when BOTH the native bridge was vendored/built
 * ([LlamaBridge.isAvailable]) AND a model file is present at the tier's path. On
 * a plain checkout (CI included) neither holds, so every case
 * [assumeTrue]-skips and the suite stays green. To run it for real, vendor
 * llama.cpp (see app/src/main/cpp/README.md), build, and push a model to the
 * app's files/models/ directory:
 *
 *   adb push MiniCPM5-1B-Q4_K_M.gguf /data/local/tmp/m.gguf
 *   adb shell run-as com.mink sh -c 'mkdir -p files/models &&
 *       cp /data/local/tmp/m.gguf files/models/MiniCPM5-1B-Q4_K_M.gguf'
 */
@RunWith(AndroidJUnit4::class)
class LlmEngineInstrumentedTest {

    @Test
    fun bridgeLoadsModelAndStreamsTokens() = runBlocking {
        assumeTrue("native llama.cpp bridge not built", LlamaBridge.isAvailable)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = ModelManager(context)
        // Prefer the real internal path the app uses; also accept the app's
        // external files dir, which a test harness can `adb push` to directly
        // without the internal storage's tight space budget.
        val externalModels = context.getExternalFilesDir("models")
        val candidates = listOf(GuardianTier.LITE, GuardianTier.FULL)
            .flatMap { tier ->
                listOfNotNull(
                    manager.modelFile(tier),
                    externalModels?.let { java.io.File(it, manager.modelFile(tier).name) },
                )
            }
        val model = candidates.firstOrNull { it.exists() && it.length() > 0L }
        assumeTrue("no model file present (internal or external files/models/)", model != null)

        val engine = LlmEngine()
        val loaded = engine.load(model!!.absolutePath, nCtx = 1024)
        assertTrue("nativeInit failed to load ${model.name}", loaded)

        val prompt = MiniCpmChatFormat.buildPrompt(
            systemPrompt = "You are Mink, a calm on-device privacy guardian.",
            userMessage = "Reply with a short friendly greeting.",
            enableThinking = false,
        )
        val out = StringBuilder()
        engine.generate(prompt, GenParams.noThink(maxTokens = 24)).collect { piece ->
            out.append(piece)
        }
        engine.unload()

        assertTrue("model produced no visible tokens", out.toString().isNotBlank())
    }
}
