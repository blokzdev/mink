package com.mink.guardian

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [KeystorePayloadCipher] against the real Android Keystore on a
 * device/emulator: round trips, the `ENC1:` wire format, IV randomization,
 * transparent legacy read-through, and exception-free failure on corruption.
 *
 * A unique alias per run keeps the key material out of other tests' way.
 */
@RunWith(AndroidJUnit4::class)
class PayloadCipherInstrumentedTest {

    private fun newCipher(): KeystorePayloadCipher =
        KeystorePayloadCipher(alias = "mink.guardian.test." + System.nanoTime())

    @Test
    fun roundTripsEmptyAsciiAndUnicodePayloads() {
        val cipher = newCipher()
        for (plain in listOf("", "hello world", multiKbUnicodePayload())) {
            val stored = cipher.encrypt(plain)
            assertTrue("encrypt should succeed", stored != null)
            assertEquals(plain, cipher.decrypt(stored!!))
        }
    }

    @Test
    fun ciphertextDiffersFromPlaintextAndCarriesPrefix() {
        val cipher = newCipher()
        val plain = "the guardian remembers"
        val stored = cipher.encrypt(plain)!!
        assertTrue("stored payload must carry the ENC1: marker", stored.startsWith("ENC1:"))
        assertNotEquals(plain, stored)
    }

    @Test
    fun samePlaintextEncryptsToDifferentPayloads() {
        val cipher = newCipher()
        val plain = "repeat me"
        val a = cipher.encrypt(plain)!!
        val b = cipher.encrypt(plain)!!
        // Randomized IV means two encryptions of the same plaintext never collide.
        assertNotEquals(a, b)
        // Both still decrypt back to the original.
        assertEquals(plain, cipher.decrypt(a))
        assertEquals(plain, cipher.decrypt(b))
    }

    @Test
    fun legacyUnprefixedValueIsReturnedUnchanged() {
        val cipher = newCipher()
        val legacy = "{\"enabled\":true,\"modelDownloaded\":false}"
        assertEquals(legacy, cipher.decrypt(legacy))
    }

    @Test
    fun corruptedCiphertextReturnsNullWithoutThrowing() {
        val cipher = newCipher()
        val stored = cipher.encrypt("sensitive baseline")!!

        // Flip a character in the base64 body: GCM authentication must fail.
        val body = stored.substring("ENC1:".length)
        val flippedChar = if (body[0] == 'A') 'B' else 'A'
        val flipped = "ENC1:" + flippedChar + body.substring(1)
        assertNull(cipher.decrypt(flipped))

        // Truncated body: too short to even contain the IV + tag.
        assertNull(cipher.decrypt("ENC1:" + body.take(4)))

        // A prefix with empty body.
        assertNull(cipher.decrypt("ENC1:"))
    }

    private fun multiKbUnicodePayload(): String {
        val unit = "{\"signal\":\"café ☕ 日本語 — Ω\",\"hash\":\"deadbeef\"}"
        return buildString {
            append('[')
            repeat(60) {
                if (it > 0) append(',')
                append(unit)
            }
            append(']')
        }
    }
}
