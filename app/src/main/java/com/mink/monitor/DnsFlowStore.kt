package com.mink.monitor

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mink.guardian.KeystorePayloadCipher
import com.mink.guardian.PayloadCipher
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Current on-disk schema for the DNS rollup; a mismatch discards the blob on load. */
const val DNS_ROLLUP_SCHEMA_VERSION = 1

/** One persisted (app, host) rollup entry — a serialisable mirror of [DnsLookup]. */
@Serializable
data class DnsRollupEntry(
    val uid: Int,
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val host: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val count: Int,
)

/** The persisted DNS-flow state: the rollup entries. */
@Serializable
data class DnsRollupSnapshot(
    val schemaVersion: Int = 0,
    val entries: List<DnsRollupEntry> = emptyList(),
)

/**
 * Encrypted-at-rest persistence for the DNS-flow monitor, in its own DataStore
 * file rather than the guardian's. It reuses the same Keystore AES-GCM cipher
 * ([KeystorePayloadCipher]) as everything else Mink stores, and adds no native
 * dependency — the decided storage path for this feature (no SQLite/SQLCipher;
 * the rollup is small and rewritten as a single blob).
 */
class DnsFlowStore(
    private val context: Context,
    private val cipher: PayloadCipher = KeystorePayloadCipher(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Load the snapshot, or null if absent, unreadable, or a schema mismatch. */
    suspend fun load(): DnsRollupSnapshot? {
        val stored = context.dnsFlowDataStore.data.first()[KEY] ?: return null
        val raw = cipher.decrypt(stored) ?: return null
        val snapshot = runCatching { json.decodeFromString(DnsRollupSnapshot.serializer(), raw) }.getOrNull()
            ?: return null
        return if (snapshot.schemaVersion == DNS_ROLLUP_SCHEMA_VERSION) snapshot else null
    }

    /** Persist [snapshot] encrypted. A serialise or encrypt failure is a silent no-op. */
    suspend fun save(snapshot: DnsRollupSnapshot) {
        val raw = runCatching { json.encodeToString(DnsRollupSnapshot.serializer(), snapshot) }.getOrNull()
            ?: return
        val stored = cipher.encrypt(raw) ?: return
        context.dnsFlowDataStore.edit { it[KEY] = stored }
    }

    private companion object {
        val KEY: Preferences.Key<String> = stringPreferencesKey("dns_rollup")
    }
}

/** Serialisable form of a live lookup. */
fun DnsLookup.toEntry(): DnsRollupEntry =
    DnsRollupEntry(uid, packageName, label, isSystem, host, firstSeenMs, lastSeenMs, count)

/** Live form of a persisted entry. */
fun DnsRollupEntry.toLookup(): DnsLookup =
    DnsLookup(uid, packageName, label, isSystem, host, firstSeenMs, lastSeenMs, count)

private val Context.dnsFlowDataStore: DataStore<Preferences> by preferencesDataStore(name = "dns_flow")
