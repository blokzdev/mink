package com.mink.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for [PayloadCipher]. Only the identity cipher and the
 * legacy-detection helper are exercised here; [KeystorePayloadCipher] needs the
 * Android Keystore and is covered by the instrumented test.
 */
class PayloadCipherTest {

    // ---- PayloadCipher.None ----

    @Test
    fun noneRoundTripsPlaintext() {
        val cipher = PayloadCipher.None
        val payload = cipher.encrypt("hello")
        assertEquals("hello", payload)
        assertEquals("hello", cipher.decrypt(payload!!))
    }

    @Test
    fun noneRoundTripsEmptyAndUnicode() {
        val cipher = PayloadCipher.None
        assertEquals("", cipher.decrypt(cipher.encrypt("")!!))
        val unicode = "{\"name\":\"café ☕ 日本語\"}"
        assertEquals(unicode, cipher.decrypt(cipher.encrypt(unicode)!!))
    }

    // ---- isLegacyPayload ----

    @Test
    fun encPrefixedPayloadIsNotLegacy() {
        assertFalse(isLegacyPayload("ENC1:abcdef=="))
    }

    @Test
    fun unprefixedPayloadIsLegacy() {
        assertTrue(isLegacyPayload("{\"enabled\":true}"))
        // An empty stored value has no prefix and is therefore legacy.
        assertTrue(isLegacyPayload(""))
        // A near-miss that does not exactly match the prefix is still legacy.
        assertTrue(isLegacyPayload("ENC:abc"))
        assertTrue(isLegacyPayload("enc1:abc"))
    }
}
