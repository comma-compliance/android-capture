package com.commacompliance.archiver.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

/**
 * Proves the upload envelope is byte-for-byte compatible with the server's
 * Arc::EnvelopeDecryptor. The server decrypts as:
 *
 *   RbNaCl::Box.new(sourcePublicKey, destination_private).decrypt(nonce, ciphertext)
 *
 * so this test plays the SERVER ROLE with libsodium: it opens the client's
 * ciphertext with (sender = sourcePublicKey from the envelope, recipient =
 * destination secret key) and asserts the original plaintext returns. Same
 * libsodium primitive both sides, exact key roles, so a green test here is a real
 * interop proof, not a self-consistency check.
 */
@RunWith(RobolectricTestRunner::class)
class EnvelopeEncryptorTest {

    private val box = TestSodium.box()
    private val encryptor = EnvelopeEncryptor(box)

    @Test
    fun envelope_has_exact_wire_field_names_and_encodings() {
        val device = box.generateKeyPair()
        val destination = box.generateKeyPair()
        // A server-style kid: 11 base64 chars + a trailing '='.
        val kid = "AbCdEfGhIjK="

        val envelope = encryptor.encrypt(
            plaintext = """{"batch_id":"b1","device_id":"amdev_x","events":[]}""",
            kid = kid,
            devicePublicKey = device.publicKey,
            deviceSecretKey = device.secretKey,
            destinationPublicKey = destination.publicKey,
        )

        // Exactly these five keys, no more.
        assertEquals(setOf("version", "kid", "sourcePublicKey", "nonce", "ciphertext"), envelope.keys().asSequence().toSet())
        assertEquals("nacl-box-v1", envelope.getString("version"))
        assertEquals(kid, envelope.getString("kid"))

        val decoder = Base64.getDecoder()
        // sourcePublicKey is the device public key, 32 bytes, standard padded b64.
        val srcPub = decoder.decode(envelope.getString("sourcePublicKey"))
        assertArrayEquals(device.publicKey, srcPub)
        assertEquals(32, srcPub.size)
        // nonce is 24 bytes.
        assertEquals(24, decoder.decode(envelope.getString("nonce")).size)
        // ciphertext carries the 16-byte MAC overhead at minimum.
        assertTrue(decoder.decode(envelope.getString("ciphertext")).size >= 16)
        // Standard alphabet, WITH padding, no line wrapping (server uses strict_decode64).
        assertTrue(envelope.getString("sourcePublicKey").matches(Regex("^[A-Za-z0-9+/]+={0,2}$")))
        assertTrue(envelope.getString("ciphertext").endsWith("=") || envelope.getString("ciphertext").matches(Regex("^[A-Za-z0-9+/]+$")))
    }

    @Test
    fun server_role_decrypt_recovers_plaintext() {
        val device = box.generateKeyPair()
        val destination = box.generateKeyPair()
        val plaintext = """{"batch_id":"abc-123","device_id":"amdev_99","events":[{"provider_id":"sms:7"}]}"""

        val envelope = encryptor.encrypt(
            plaintext = plaintext,
            kid = "ZZZZZZZZZZZ=",
            devicePublicKey = device.publicKey,
            deviceSecretKey = device.secretKey,
            destinationPublicKey = destination.publicKey,
        )

        // Reproduce the server's decrypt: source pub from the envelope + destination secret.
        val decoder = Base64.getDecoder()
        val srcPub = decoder.decode(envelope.getString("sourcePublicKey"))
        val nonce = decoder.decode(envelope.getString("nonce"))
        val cipher = decoder.decode(envelope.getString("ciphertext"))

        val recovered = box.boxOpenEasy(
            cipher = cipher,
            nonce = nonce,
            senderPublicKey = srcPub,
            recipientSecretKey = destination.secretKey,
        )
        assertEquals(plaintext, String(recovered, Charsets.UTF_8))
    }

    @Test
    fun nonce_is_fresh_per_envelope() {
        val device = box.generateKeyPair()
        val destination = box.generateKeyPair()
        fun nonceOf() = EnvelopeEncryptor(box).encrypt(
            "x", "ZZZZZZZZZZZ=", device.publicKey, device.secretKey, destination.publicKey,
        ).getString("nonce")
        assertTrue(nonceOf() != nonceOf())
    }

    @Test
    fun fingerprint_matches_server_definition_lowercase_hex_sha256() {
        // The server defines device_pubkey_fingerprint as the lowercase hex SHA-256
        // of the raw 32-byte public key. Verify against an independently-computed digest.
        val pub = box.generateKeyPair().publicKey
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val expected = md.digest(pub).joinToString("") { "%02x".format(it) }
        assertEquals(expected, KeyManager.fingerprintOf(pub))
        assertEquals(64, KeyManager.fingerprintOf(pub).length)
        assertTrue(KeyManager.fingerprintOf(pub).matches(Regex("^[0-9a-f]{64}$")))
    }
}
