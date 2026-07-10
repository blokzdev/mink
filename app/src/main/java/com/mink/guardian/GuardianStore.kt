package com.mink.guardian

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Persisted guardian settings. */
data class GuardianSettings(
    val enabled: Boolean = false,
    val tierOverride: GuardianTier? = null,
    val modelDownloaded: Boolean = false,
)

/** One recorded signal value, used for diffing snapshots across sweeps. */
@Serializable
data class SignalSnap(val id: String, val name: String, val value: String)

/** A full sweep snapshot: category id to the signals collected for it. */
@Serializable
data class GuardianSnapshot(
    val epochMs: Long = 0L,
    val categories: Map<String, List<SignalSnap>> = emptyMap(),
)

/**
 * JSON-over-DataStore persistence for everything the guardian remembers:
 * observations, alerts, the chat log, settings, and the last sweep snapshot.
 *
 * The public contract types ([Observation], [GuardianAlert], [ChatMessage])
 * are not serializable and must not be modified, so this store maps them to
 * private DTO mirrors. Every read is exception-safe and returns a sane empty
 * default on any parse failure.
 */
class GuardianStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ---- Observations ----

    suspend fun loadObservations(): List<Observation> =
        readList(KEY_OBSERVATIONS, ObservationDto.serializer()).map { it.toModel() }

    suspend fun saveObservations(list: List<Observation>) =
        writeList(KEY_OBSERVATIONS, list.map { ObservationDto.from(it) }, ObservationDto.serializer())

    // ---- Alerts ----

    suspend fun loadAlerts(): List<GuardianAlert> =
        readList(KEY_ALERTS, AlertDto.serializer()).map { it.toModel() }

    suspend fun saveAlerts(list: List<GuardianAlert>) =
        writeList(KEY_ALERTS, list.map { AlertDto.from(it) }, AlertDto.serializer())

    // ---- Chat log ----

    suspend fun loadChatLog(): List<ChatMessage> =
        readList(KEY_CHAT, ChatDto.serializer()).map { it.toModel() }

    suspend fun saveChatLog(list: List<ChatMessage>) =
        writeList(KEY_CHAT, list.map { ChatDto.from(it) }, ChatDto.serializer())

    // ---- Settings ----

    suspend fun loadSettings(): GuardianSettings {
        val raw = context.guardianDataStore.data.first()[KEY_SETTINGS] ?: return GuardianSettings()
        return runCatching {
            val dto = json.decodeFromString(SettingsDto.serializer(), raw)
            dto.toModel()
        }.getOrDefault(GuardianSettings())
    }

    suspend fun saveSettings(settings: GuardianSettings) {
        val raw = runCatching {
            json.encodeToString(SettingsDto.serializer(), SettingsDto.from(settings))
        }.getOrNull() ?: return
        context.guardianDataStore.edit { it[KEY_SETTINGS] = raw }
    }

    // ---- Last snapshot ----

    suspend fun loadSnapshot(): GuardianSnapshot? {
        val raw = context.guardianDataStore.data.first()[KEY_SNAPSHOT] ?: return null
        return runCatching { json.decodeFromString(GuardianSnapshot.serializer(), raw) }.getOrNull()
    }

    suspend fun saveSnapshot(snapshot: GuardianSnapshot) {
        val raw = runCatching {
            json.encodeToString(GuardianSnapshot.serializer(), snapshot)
        }.getOrNull() ?: return
        context.guardianDataStore.edit { it[KEY_SNAPSHOT] = raw }
    }

    // ---- generic list helpers ----

    private suspend fun <T> readList(
        key: Preferences.Key<String>,
        serializer: KSerializer<T>,
    ): List<T> {
        val raw = context.guardianDataStore.data.first()[key] ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(serializer), raw)
        }.getOrDefault(emptyList())
    }

    private suspend fun <T> writeList(
        key: Preferences.Key<String>,
        list: List<T>,
        serializer: KSerializer<T>,
    ) {
        val raw = runCatching {
            json.encodeToString(ListSerializer(serializer), list)
        }.getOrNull() ?: return
        context.guardianDataStore.edit { it[key] = raw }
    }

    private companion object {
        val KEY_OBSERVATIONS = stringPreferencesKey("observations")
        val KEY_ALERTS = stringPreferencesKey("alerts")
        val KEY_CHAT = stringPreferencesKey("chat_log")
        val KEY_SETTINGS = stringPreferencesKey("settings")
        val KEY_SNAPSHOT = stringPreferencesKey("last_snapshot")
    }
}

private val Context.guardianDataStore: DataStore<Preferences> by preferencesDataStore(name = "guardian")

// ---- serializable DTO mirrors of the public (non-serializable) contract types ----

@Serializable
private data class ObservationDto(
    val id: String,
    val categoryId: String,
    val summary: String,
    val epochMs: Long,
    val kind: String,
) {
    fun toModel() = Observation(
        id = id,
        categoryId = categoryId,
        summary = summary,
        epochMs = epochMs,
        kind = runCatching { ObservationKind.valueOf(kind) }.getOrDefault(ObservationKind.SNAPSHOT),
    )

    companion object {
        fun from(o: Observation) = ObservationDto(o.id, o.categoryId, o.summary, o.epochMs, o.kind.name)
    }
}

@Serializable
private data class AlertDto(
    val id: String,
    val level: String,
    val title: String,
    val body: String,
    val categoryId: String?,
    val createdAtEpochMs: Long,
    val acknowledged: Boolean,
) {
    fun toModel() = GuardianAlert(
        id = id,
        level = runCatching { AlertLevel.valueOf(level) }.getOrDefault(AlertLevel.INFO),
        title = title,
        body = body,
        categoryId = categoryId,
        createdAtEpochMs = createdAtEpochMs,
        acknowledged = acknowledged,
    )

    companion object {
        fun from(a: GuardianAlert) =
            AlertDto(a.id, a.level.name, a.title, a.body, a.categoryId, a.createdAtEpochMs, a.acknowledged)
    }
}

@Serializable
private data class ChatDto(
    val id: String,
    val role: String,
    val content: String,
    val epochMs: Long,
    val thinking: String?,
) {
    fun toModel() = ChatMessage(
        id = id,
        role = runCatching { ChatRole.valueOf(role) }.getOrDefault(ChatRole.GUARDIAN),
        content = content,
        epochMs = epochMs,
        thinking = thinking,
        streaming = false,
    )

    companion object {
        fun from(m: ChatMessage) = ChatDto(m.id, m.role.name, m.content, m.epochMs, m.thinking)
    }
}

@Serializable
private data class SettingsDto(
    val enabled: Boolean,
    val tierOverride: String?,
    val modelDownloaded: Boolean,
) {
    fun toModel() = GuardianSettings(
        enabled = enabled,
        tierOverride = tierOverride?.let { runCatching { GuardianTier.valueOf(it) }.getOrNull() },
        modelDownloaded = modelDownloaded,
    )

    companion object {
        fun from(s: GuardianSettings) =
            SettingsDto(s.enabled, s.tierOverride?.name, s.modelDownloaded)
    }
}
