package com.mink.guardian

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
