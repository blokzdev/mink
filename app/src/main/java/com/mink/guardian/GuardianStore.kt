package com.mink.guardian

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mink.monitor.APP_ACCESS_SCHEMA_VERSION
import com.mink.monitor.AppAccessSnapshot
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
 *
 * Every value is encrypted at rest with a Keystore AES-GCM key via [cipher]
 * (see [KeystorePayloadCipher]). A legacy plaintext value written before
 * encryption existed is read once transparently and re-encrypted on the next
 * write. A decryption failure is treated as absent data: the read yields its
 * empty/null/default just as a parse failure does.
 */
class GuardianStore(
    private val context: Context,
    private val cipher: PayloadCipher = KeystorePayloadCipher(),
) {

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
        val raw = readRaw(KEY_SETTINGS) ?: return GuardianSettings()
        return runCatching {
            val dto = json.decodeFromString(SettingsDto.serializer(), raw)
            dto.toModel()
        }.getOrDefault(GuardianSettings())
    }

    suspend fun saveSettings(settings: GuardianSettings) {
        val raw = runCatching {
            json.encodeToString(SettingsDto.serializer(), SettingsDto.from(settings))
        }.getOrNull() ?: return
        writeRaw(KEY_SETTINGS, raw)
    }

    // ---- Last snapshot ----

    suspend fun loadSnapshot(): GuardianSnapshot? {
        val raw = readRaw(KEY_SNAPSHOT) ?: return null
        return runCatching { json.decodeFromString(GuardianSnapshot.serializer(), raw) }.getOrNull()
    }

    suspend fun saveSnapshot(snapshot: GuardianSnapshot) {
        val raw = runCatching {
            json.encodeToString(GuardianSnapshot.serializer(), snapshot)
        }.getOrNull() ?: return
        writeRaw(KEY_SNAPSHOT, raw)
    }

    // ---- Learned baseline ----

    /**
     * The learned historical baseline, or null if none is stored yet, it fails
     * to parse, or its [GuardianBaseline.schemaVersion] does not match
     * [BASELINE_SCHEMA_VERSION]. Discard-on-mismatch is deliberate: a legacy
     * (unversioned, decodes to 0) or future/downgrade blob may be shaped
     * differently, and misreading months of learned baseline is worse than
     * losing it — a discarded baseline simply relearns. A future version adds a
     * forward migration here instead of discarding.
     *
     * [GuardianBaseline] is `@Serializable` directly (a documented deviation
     * from the private-DTO convention, since it is data-layer machinery, not a
     * public contract type), so no DTO mirror.
     */
    suspend fun loadBaseline(): GuardianBaseline? {
        val raw = readRaw(KEY_BASELINE) ?: return null
        val baseline = runCatching {
            json.decodeFromString(GuardianBaseline.serializer(), raw)
        }.getOrNull() ?: return null
        return if (baseline.schemaVersion == BASELINE_SCHEMA_VERSION) baseline else null
    }

    suspend fun saveBaseline(b: GuardianBaseline) {
        val raw = runCatching {
            json.encodeToString(GuardianBaseline.serializer(), b)
        }.getOrNull() ?: return
        writeRaw(KEY_BASELINE, raw)
    }

    // ---- App access snapshot ----

    /**
     * The last persisted app-access snapshot, or null if none is stored yet, it
     * fails to parse, or its [AppAccessSnapshot.schemaVersion] does not match
     * [APP_ACCESS_SCHEMA_VERSION]. Discard-on-mismatch mirrors [loadBaseline]: a
     * legacy (unversioned, decodes to 0) or future/downgrade blob may be shaped
     * differently, and misreading it is worse than losing it — a discarded
     * snapshot just means the next sweep records fresh state and diffs nothing.
     *
     * [AppAccessSnapshot] is `@Serializable` directly (data-layer machinery, not
     * a public contract type), so no DTO mirror.
     */
    suspend fun loadAppAccessSnapshot(): AppAccessSnapshot? {
        val raw = readRaw(KEY_APP_ACCESS) ?: return null
        val snapshot = runCatching {
            json.decodeFromString(AppAccessSnapshot.serializer(), raw)
        }.getOrNull() ?: return null
        return if (snapshot.schemaVersion == APP_ACCESS_SCHEMA_VERSION) snapshot else null
    }

    suspend fun saveAppAccessSnapshot(snapshot: AppAccessSnapshot) {
        val raw = runCatching {
            json.encodeToString(AppAccessSnapshot.serializer(), snapshot)
        }.getOrNull() ?: return
        writeRaw(KEY_APP_ACCESS, raw)
    }

    // ---- generic list helpers ----

    private suspend fun <T> readList(
        key: Preferences.Key<String>,
        serializer: KSerializer<T>,
    ): List<T> {
        val raw = readRaw(key) ?: return emptyList()
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
        writeRaw(key, raw)
    }

    // ---- encrypted raw value helpers ----

    /**
     * Reads the stored value for [key] and decrypts it, or returns null if the
     * key is absent or [PayloadCipher.decrypt] fails (treated as absent data).
     * A legacy plaintext value is returned as-is and re-encrypted on next write.
     */
    private suspend fun readRaw(key: Preferences.Key<String>): String? {
        val stored = context.guardianDataStore.data.first()[key] ?: return null
        return cipher.decrypt(stored)
    }

    /**
     * Encrypts [raw] and stores it under [key]. If [PayloadCipher.encrypt]
     * fails the write is skipped, matching the serialize-failure behavior.
     */
    private suspend fun writeRaw(key: Preferences.Key<String>, raw: String) {
        val stored = cipher.encrypt(raw) ?: return
        context.guardianDataStore.edit { it[key] = stored }
    }

    /**
     * Re-encrypts any value still stored as legacy plaintext, once, at startup.
     *
     * Lazy migration on next write is not enough on its own: the snapshot and
     * baseline are rewritten every sweep, but the chat log is only rewritten
     * when the user chats, so their plaintext would otherwise linger
     * indefinitely. Exception-safe; a failure leaves the value as it was.
     */
    suspend fun migrateLegacyPayloads() {
        runCatching {
            val data = context.guardianDataStore.data.first()
            // Note: `key to value` would resolve to DataStore's Preferences.Pair
            // infix, not kotlin.Pair, so construct the pairs explicitly.
            val reencrypted = ALL_KEYS.mapNotNull { key ->
                val stored = data[key] ?: return@mapNotNull null
                if (!isLegacyPayload(stored)) return@mapNotNull null
                cipher.encrypt(stored)?.let { Pair(key, it) }
            }
            if (reencrypted.isEmpty()) return@runCatching
            context.guardianDataStore.edit { prefs ->
                reencrypted.forEach { prefs[it.first] = it.second }
            }
        }
    }

    private companion object {
        /** Every key holding an encrypted payload, for one-shot legacy migration. */
        val ALL_KEYS: List<Preferences.Key<String>>
            get() = listOf(
                KEY_OBSERVATIONS, KEY_ALERTS, KEY_CHAT, KEY_SETTINGS, KEY_SNAPSHOT,
                KEY_BASELINE, KEY_APP_ACCESS,
            )

        val KEY_OBSERVATIONS = stringPreferencesKey("observations")
        val KEY_ALERTS = stringPreferencesKey("alerts")
        val KEY_CHAT = stringPreferencesKey("chat_log")
        val KEY_SETTINGS = stringPreferencesKey("settings")
        val KEY_SNAPSHOT = stringPreferencesKey("last_snapshot")
        val KEY_BASELINE = stringPreferencesKey("baseline")
        val KEY_APP_ACCESS = stringPreferencesKey("app_access")
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
