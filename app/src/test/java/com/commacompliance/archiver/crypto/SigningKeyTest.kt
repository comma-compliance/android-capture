package com.commacompliance.archiver.crypto

import androidx.test.core.app.ApplicationProvider
import com.commacompliance.archiver.FakeSharedPreferences
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Ed25519 signing key is what proves device possession during self-serve
 * registration. These tests prove the client can sign a challenge that verifies
 * against the public key it hands the server, that the key is stable, and that it
 * is cryptographically SEPARATE from the X25519 box (encryption) key.
 */
@RunWith(RobolectricTestRunner::class)
class SigningKeyTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val box = TestSodium.box()

    private fun keyManager() = KeyManager(context, box, sharedPrefs)

    // One backing store shared across KeyManager instances in a test so the
    // generate-once behavior is observable.
    private val backing = FakeSharedPreferences()
    private val sharedPrefs: (android.content.Context) -> android.content.SharedPreferences = { backing }

    @Test
    fun signs_a_challenge_that_verifies_against_the_public_key() {
        val km = keyManager()
        val challenge = ByteArray(32) { it.toByte() }

        val signature = km.sign(challenge)
        assertEquals(NaclBox.SIGNATURE_BYTES, signature.size)
        assertTrue(box.verifyDetached(signature, challenge, km.signingPublicKey()))
    }

    @Test
    fun a_tampered_challenge_does_not_verify() {
        val km = keyManager()
        val challenge = ByteArray(32) { it.toByte() }
        val signature = km.sign(challenge)

        val tampered = challenge.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(box.verifyDetached(signature, tampered, km.signingPublicKey()))
    }

    @Test
    fun signing_public_key_is_32_bytes_and_stable() {
        val first = keyManager().signingPublicKey()
        val second = keyManager().signingPublicKey()
        assertEquals(NaclBox.SIGN_PUBLIC_KEY_BYTES, first.size)
        assertArrayEquals(first, second)
    }

    @Test
    fun signing_key_is_separate_from_the_box_key() {
        val km = keyManager()
        // The two public keys must differ - the box key is for encryption, the
        // signing key for the registration challenge. Reusing one for both would
        // be a cryptographic error.
        assertNotEquals(
            java.util.Base64.getEncoder().encodeToString(km.signingPublicKey()),
            km.publicKeyBase64(),
        )
        // A signature made with the signing key must NOT verify against the box
        // public key (they are unrelated keypairs).
        val challenge = ByteArray(32) { 7 }
        val signature = km.sign(challenge)
        assertFalse(box.verifyDetached(signature, challenge, km.publicKey()))
    }
}
