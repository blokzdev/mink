package com.mink.signals

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.PermissionKind
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider

/**
 * Lists the text-to-speech engines installed on the device and the default one
 * you use. The set of speech engines and their voices varies by maker, region,
 * and the extra language packs you have added, so it quietly individuates you.
 * Mink queries the installed engines through the package manager; it does not
 * start any engine or speak.
 */
class VoicesProvider(ctx: ProviderContext) : SignalProvider {

    private val appContext: Context = ctx.appContext

    override val category: SignalCategory = SignalCategory.VOICES
    override val permission: PermissionKind? = null

    override suspend fun collect(): List<FingerprintSignal> {
        val signals = mutableListOf<FingerprintSignal>()

        val engines = queryEngines()
        signals += FingerprintSignal.make(
            key = "engineCount",
            category = category,
            name = "Speech engines",
            value = if (engines.isEmpty()) "unavailable" else "${engines.size} installed",
            rationale = "How many text-to-speech engines are installed. Extra engines from a " +
                "maker or a language pack stand out. Read from the package manager.",
        )

        if (engines.isNotEmpty()) {
            signals += FingerprintSignal.make(
                key = "engines",
                category = category,
                name = "Installed engines",
                value = engines.joinToString(", "),
                rationale = "The speech engines available on this device. The combination is a " +
                    "fairly stable per-device trait.",
                displayHint = DisplayHint.TAGS,
                entries = engines.map { SignalEntry(it, "") },
            )
        }

        val defaultEngine = readDefaultEngine()
        if (!defaultEngine.isNullOrBlank()) {
            signals += FingerprintSignal.make(
                key = "defaultEngine",
                category = category,
                name = "Default engine",
                value = defaultEngine,
                rationale = "The engine your phone speaks with by default. A non-standard choice " +
                    "narrows you down.",
            )
        }

        return signals
    }

    private fun queryEngines(): List<String> = runCatching {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val pm = appContext.packageManager
        val resolved: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, 0)
        }
        resolved
            .mapNotNull { info ->
                runCatching { info.loadLabel(pm)?.toString() }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: info.serviceInfo?.packageName
            }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())

    private fun readDefaultEngine(): String? = runCatching {
        Settings.Secure.getString(appContext.contentResolver, "tts_default_synth")
    }.getOrNull()
}
