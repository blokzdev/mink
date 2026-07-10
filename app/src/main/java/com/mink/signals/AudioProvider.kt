package com.mink.signals

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.PermissionKind
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider

/**
 * Reads the audio surface any app can see with no prompt: the output and input
 * devices currently attached, the hardware sample rate and buffer size, and the
 * per-stream volume levels. The set of routes and the exact buffer geometry are
 * stable enough to help tell one device apart from another.
 */
class AudioProvider(ctx: ProviderContext) : SignalProvider {

    private val appContext: Context = ctx.appContext

    override val category: SignalCategory = SignalCategory.AUDIO
    override val permission: PermissionKind? = null

    override suspend fun collect(): List<FingerprintSignal> {
        val signals = mutableListOf<FingerprintSignal>()
        val audio = runCatching {
            appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }.getOrNull()

        if (audio == null) {
            signals += FingerprintSignal.make(
                key = "unavailable",
                category = category,
                name = "Audio service",
                value = "unavailable",
                rationale = "The audio service could not be read on this device.",
            )
            return signals
        }

        addDevices(audio, signals)
        addHardwareProfile(audio, signals)
        addMicrophones(audio, signals)
        addPlaybackState(audio, signals)
        addVolumes(audio, signals)
        return signals
    }

    private fun addDevices(audio: AudioManager, signals: MutableList<FingerprintSignal>) {
        runCatching {
            val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)

            val outputTypes = outputs.map { typeName(it.type) }.distinct()
            signals += FingerprintSignal.make(
                key = "outputs",
                category = category,
                name = "Audio outputs",
                value = if (outputTypes.isEmpty()) "(none)" else outputTypes.joinToString(", "),
                rationale = "The output routes attached right now, such as the speaker, wired " +
                    "headset, or a paired Bluetooth device.",
                displayHint = if (outputTypes.isEmpty()) DisplayHint.PLAIN else DisplayHint.TAGS,
                entries = outputTypes.takeIf { it.isNotEmpty() }?.map { SignalEntry(it, "") },
            )

            val inputTypes = inputs.map { typeName(it.type) }.distinct()
            signals += FingerprintSignal.make(
                key = "inputs",
                category = category,
                name = "Audio inputs",
                value = if (inputTypes.isEmpty()) "(none)" else inputTypes.joinToString(", "),
                rationale = "The input routes present, such as the built-in microphone or an " +
                    "accessory headset.",
                displayHint = if (inputTypes.isEmpty()) DisplayHint.PLAIN else DisplayHint.TAGS,
                entries = inputTypes.takeIf { it.isNotEmpty() }?.map { SignalEntry(it, "") },
            )
        }
    }

    private fun addHardwareProfile(audio: AudioManager, signals: MutableList<FingerprintSignal>) {
        runCatching {
            val sampleRate = audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            val framesPerBuffer = audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            signals += FingerprintSignal.make(
                key = "hardwareProfile",
                category = category,
                name = "Output hardware profile",
                value = "${sampleRate ?: "unknown"} Hz, ${framesPerBuffer ?: "unknown"} frames",
                rationale = "The native output sample rate and buffer size. These are fixed by " +
                    "the audio chip and vary between models.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = listOf(
                    SignalEntry("Sample rate", "${sampleRate ?: "unknown"} Hz"),
                    SignalEntry("Frames per buffer", framesPerBuffer ?: "unknown"),
                ),
            )
        }
    }

    private fun addMicrophones(audio: AudioManager, signals: MutableList<FingerprintSignal>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            val mics = audio.microphones
            signals += FingerprintSignal.make(
                key = "microphoneCount",
                category = category,
                name = "Microphones",
                value = mics.size.toString(),
                rationale = "How many microphones the hardware exposes. The count and placement " +
                    "differ across models.",
            )
        }
    }

    private fun addPlaybackState(audio: AudioManager, signals: MutableList<FingerprintSignal>) {
        runCatching {
            signals += FingerprintSignal.make(
                key = "musicActive",
                category = category,
                name = "Music active",
                value = audio.isMusicActive.toString(),
                rationale = "Whether audio is playing right now. Any app can check this without a " +
                    "prompt.",
            )
            signals += FingerprintSignal.make(
                key = "mode",
                category = category,
                name = "Audio mode",
                value = modeName(audio.mode),
                rationale = "The current audio mode, such as normal playback or an active call.",
            )
        }
    }

    private fun addVolumes(audio: AudioManager, signals: MutableList<FingerprintSignal>) {
        runCatching {
            val streams = listOf(
                "Music" to AudioManager.STREAM_MUSIC,
                "Ring" to AudioManager.STREAM_RING,
                "Alarm" to AudioManager.STREAM_ALARM,
                "Notification" to AudioManager.STREAM_NOTIFICATION,
                "System" to AudioManager.STREAM_SYSTEM,
                "Call" to AudioManager.STREAM_VOICE_CALL,
            )
            val entries = streams.mapNotNull { (label, stream) ->
                runCatching {
                    val level = audio.getStreamVolume(stream)
                    val max = audio.getStreamMaxVolume(stream)
                    SignalEntry(label, "$level / $max")
                }.getOrNull()
            }
            if (entries.isNotEmpty()) {
                signals += FingerprintSignal.make(
                    key = "volumes",
                    category = category,
                    name = "Volume levels",
                    value = entries.joinToString(", ") { "${it.label} ${it.value}" },
                    rationale = "The volume set on each audio stream. The exact levels drift over " +
                        "time and can help distinguish one session from another.",
                    displayHint = DisplayHint.KEY_VALUE,
                    entries = entries,
                )
            }
        }
    }

    companion object {
        /** Maps an [AudioDeviceInfo] type constant to a readable label. Pure and testable. */
        fun typeName(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth call audio"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth media"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
            AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
            AudioDeviceInfo.TYPE_FM -> "FM radio"
            AudioDeviceInfo.TYPE_AUX_LINE -> "Aux line"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analog line"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital line"
            AudioDeviceInfo.TYPE_DOCK -> "Dock"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "Remote submix"
            else -> "Type $type"
        }

        /** Maps an [AudioManager] mode constant to a readable label. Pure and testable. */
        fun modeName(mode: Int): String = when (mode) {
            AudioManager.MODE_NORMAL -> "normal"
            AudioManager.MODE_RINGTONE -> "ringtone"
            AudioManager.MODE_IN_CALL -> "in call"
            AudioManager.MODE_IN_COMMUNICATION -> "in communication"
            else -> "mode $mode"
        }
    }
}
