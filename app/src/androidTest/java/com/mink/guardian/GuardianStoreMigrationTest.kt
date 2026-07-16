package com.mink.guardian

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mink.monitor.APP_ACCESS_SCHEMA_VERSION
import com.mink.monitor.AppAccessSnapshot
import com.mink.monitor.AppGrant
import com.mink.monitor.HIGH_RISK_SCHEMA_VERSION
import com.mink.monitor.HighRiskAdmin
import com.mink.monitor.HighRiskCert
import com.mink.monitor.HighRiskComponent
import com.mink.monitor.HighRiskDefaultApp
import com.mink.monitor.HighRiskSnapshot
import com.mink.monitor.PermCapability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that [GuardianStore.migrateLegacyPayloads] re-encrypts values left as
 * plaintext by a pre-encryption build. The snapshot and baseline are rewritten
 * every sweep, but the chat log is only rewritten when the user chats, so
 * without this migration its plaintext would linger indefinitely.
 */
@RunWith(AndroidJUnit4::class)
class GuardianStoreMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Writes plaintext through a store with no cipher, the pre-encryption behavior. */
    private fun legacyStore() = GuardianStore(context, PayloadCipher.None)

    private fun encryptingStore(cipher: PayloadCipher) = GuardianStore(context, cipher)

    @Test
    fun migrationReEncryptsLegacyChatLogAndKeepsItReadable() = runBlocking {
        val cipher = KeystorePayloadCipher(alias = "mink.guardian.test." + System.nanoTime())

        // A pre-encryption build persisted the chat log as plaintext JSON.
        val chat = listOf(
            ChatMessage(
                id = "m1",
                role = ChatRole.USER,
                content = "what have you learned about my device",
                epochMs = 1_000L,
                thinking = null,
            ),
        )
        legacyStore().saveChatLog(chat)

        val store = encryptingStore(cipher)
        store.migrateLegacyPayloads()

        // Readable through the encrypting store...
        val loaded = store.loadChatLog()
        assertEquals(1, loaded.size)
        assertEquals("what have you learned about my device", loaded.first().content)

        // ...and no longer plaintext: a store with no cipher now sees ciphertext,
        // which cannot be parsed back into the chat log.
        val throughNoCipher = legacyStore().loadChatLog()
        assertTrue("legacy read of encrypted data should yield nothing", throughNoCipher.isEmpty())
    }

    @Test
    fun migrationIsIdempotentAndLeavesEncryptedValuesAlone() = runBlocking {
        val cipher = KeystorePayloadCipher(alias = "mink.guardian.test." + System.nanoTime())
        val store = encryptingStore(cipher)

        // Deliberately not settings: enabling the guardian would leak into the
        // smoke tests, which share this process's DataStore.
        val snapshot = GuardianSnapshot(
            epochMs = 7_000L,
            categories = mapOf("cpu" to listOf(SignalSnap("cpu.model", "Model", "A"))),
        )
        store.saveSnapshot(snapshot)
        store.migrateLegacyPayloads()
        store.migrateLegacyPayloads()

        assertEquals(snapshot, store.loadSnapshot())
    }

    @Test
    fun legacyPayloadDetectionMatchesTheWireFormat() {
        assertTrue(isLegacyPayload("""{"enabled":true}"""))
        assertFalse(isLegacyPayload("ENC1:abcdef"))
    }

    @Test
    fun appAccessSnapshotRoundTripsThroughEncryptionAndIsNotPlaintext() = runBlocking {
        val cipher = KeystorePayloadCipher(alias = "mink.guardian.test." + System.nanoTime())
        val store = encryptingStore(cipher)

        val snapshot = AppAccessSnapshot(
            schemaVersion = APP_ACCESS_SCHEMA_VERSION,
            generatedAtMs = 4_000L,
            apps = listOf(
                AppGrant(
                    packageName = "com.example.alpha",
                    label = "Alpha",
                    isSystem = false,
                    granted = setOf(PermCapability.CAMERA, PermCapability.LOCATION),
                ),
                AppGrant(
                    packageName = "com.example.sys",
                    label = "Sys",
                    isSystem = true,
                    granted = emptySet(),
                ),
            ),
        )
        store.saveAppAccessSnapshot(snapshot)

        // Readable through the encrypting store...
        assertEquals(snapshot, store.loadAppAccessSnapshot())

        // ...and actually encrypted: a store with no cipher sees ciphertext it
        // cannot parse back into a snapshot.
        assertNull(
            "legacy read of encrypted snapshot should yield nothing",
            legacyStore().loadAppAccessSnapshot(),
        )
    }

    @Test
    fun settingsRoundTripKeepsAlertnessAndMutedSources() = runBlocking {
        val cipher = KeystorePayloadCipher(alias = "mink.guardian.test." + System.nanoTime())
        val store = encryptingStore(cipher)

        // Deliberately leaves `enabled` false: enabling the guardian would leak
        // into the smoke tests, which share this process's DataStore.
        val configured = GuardianSettings(
            alertness = Alertness.QUIET,
            mutedSources = setOf(AlertSource.SENSOR_USE),
        )
        store.saveSettings(configured)
        assertEquals(configured, store.loadSettings())

        // A blob persisted before the alertness fields existed must decode to
        // the same behavior as today: STANDARD, no mutes. A default-shaped save
        // stands in for the legacy wire format (the DTO field defaults make the
        // missing keys safe, and the json config ignores unknown keys). This
        // also restores the default settings blob for the smoke tests.
        store.saveSettings(GuardianSettings())
        val restored = store.loadSettings()
        assertEquals(Alertness.STANDARD, restored.alertness)
        assertTrue(
            "legacy-shaped settings must decode to no mutes",
            restored.mutedSources.isEmpty(),
        )
    }

    @Test
    fun legacyVersionZeroAppAccessSnapshotIsDiscardedOnLoad() = runBlocking {
        val cipher = KeystorePayloadCipher(alias = "mink.guardian.test." + System.nanoTime())

        // A pre-versioning build persisted a schemaVersion-0 snapshot as plaintext.
        val legacy = AppAccessSnapshot(
            schemaVersion = 0,
            generatedAtMs = 1_000L,
            apps = listOf(
                AppGrant(
                    packageName = "com.example.alpha",
                    label = "Alpha",
                    isSystem = false,
                    granted = setOf(PermCapability.CAMERA),
                ),
            ),
        )
        legacyStore().saveAppAccessSnapshot(legacy)

        val store = encryptingStore(cipher)
        store.migrateLegacyPayloads()

        // Re-encrypted by migration, but discarded by the schema-version guard.
        assertNull("schemaVersion 0 must be discarded on load", store.loadAppAccessSnapshot())
    }

    @Test
    fun highRiskSnapshotRoundTripsThroughEncryptionAndIsNotPlaintext() = runBlocking {
        val cipher = KeystorePayloadCipher(alias = "mink.guardian.test." + System.nanoTime())
        val store = encryptingStore(cipher)

        val snapshot = HighRiskSnapshot(
            schemaVersion = HIGH_RISK_SCHEMA_VERSION,
            generatedAtMs = 5_000L,
            accessibilityServices = listOf(
                HighRiskComponent(id = "com.example.a11y/.Service", label = "A11y"),
            ),
            notificationListeners = listOf(
                HighRiskComponent(id = "com.example.notif/.Listener", label = "Notif"),
            ),
            deviceAdmins = listOf(
                HighRiskAdmin(
                    packageName = "com.example.mdm",
                    label = "MDM",
                    isDeviceOwner = true,
                    isProfileOwner = false,
                ),
            ),
            userCertificates = listOf(
                HighRiskCert(id = "user:42", label = "Example Root CA"),
            ),
            defaultApps = mapOf(
                "sms" to HighRiskDefaultApp("com.example.sms", "SMS"),
                "browser" to HighRiskDefaultApp("com.example.browser", "Browser"),
            ),
            vpnActive = true,
        )
        store.saveHighRiskSnapshot(snapshot)

        // Readable through the encrypting store...
        assertEquals(snapshot, store.loadHighRiskSnapshot())

        // ...and actually encrypted: a store with no cipher sees ciphertext it
        // cannot parse back into a snapshot.
        assertNull(
            "legacy read of encrypted snapshot should yield nothing",
            legacyStore().loadHighRiskSnapshot(),
        )
    }

    @Test
    fun legacyVersionZeroHighRiskSnapshotIsDiscardedOnLoad() = runBlocking {
        val cipher = KeystorePayloadCipher(alias = "mink.guardian.test." + System.nanoTime())

        // A pre-versioning build persisted a schemaVersion-0 snapshot as plaintext.
        val legacy = HighRiskSnapshot(
            schemaVersion = 0,
            generatedAtMs = 1_000L,
            accessibilityServices = listOf(
                HighRiskComponent(id = "com.example.a11y/.Service", label = "A11y"),
            ),
        )
        legacyStore().saveHighRiskSnapshot(legacy)

        val store = encryptingStore(cipher)
        store.migrateLegacyPayloads()

        // Re-encrypted by migration, but discarded by the schema-version guard.
        assertNull("schemaVersion 0 must be discarded on load", store.loadHighRiskSnapshot())
    }
}
