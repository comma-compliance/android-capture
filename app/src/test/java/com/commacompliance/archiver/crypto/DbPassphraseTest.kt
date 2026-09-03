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
 * The database passphrase is the only key that can decrypt the durable on-device
 * queue. These tests prove it is generated once, is stable across KeyManager
 * instances (so the encrypted DB stays openable), is a full 32-byte key, and is
 * distinct from the box/signing keys (it must never reuse another key's material).
 */
@RunWith(RobolectricTestRunner::class)
class DbPassphraseTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val box = TestSodium.box()

    // One backing store shared across KeyManager instances so generate-once is observable.
    private val backing = FakeSharedPreferences()
    private val sharedPrefs: (android.content.Context) -> android.content.SharedPreferences = { backing }

    private fun keyManager() = KeyManager(context, box, sharedPrefs)

    @Test
    fun passphrase_is_32_bytes() {
        assertEquals(KeyManager.DB_PASSPHRASE_BYTES, keyManager().dbPassphrase().size)
    }

    @Test
    fun passphrase_is_generated_once_and_stable_across_instances() {
        val first = keyManager().dbPassphrase()
        val second = keyManager().dbPassphrase()
        // A regenerated passphrase would orphan the encrypted queue, so it must be
        // byte-identical even across freshly-built KeyManagers sharing the store.
        assertArrayEquals(first, second)
    }

    @Test
    fun has_db_passphrase_flips_only_after_generation() {
        val km = keyManager()
        assertFalse(km.hasDbPassphrase())
        km.dbPassphrase()
        assertTrue(km.hasDbPassphrase())
    }

    @Test
    fun passphrase_is_distinct_from_box_and_signing_keys() {
        val km = keyManager()
        val passphrase = km.dbPassphrase()
        // Distinct material from the encryption and signing secrets.
        assertNotEquals(
            java.util.Base64.getEncoder().encodeToString(passphrase),
            java.util.Base64.getEncoder().encodeToString(km.secretKey()),
        )
        assertNotEquals(
            java.util.Base64.getEncoder().encodeToString(passphrase),
            java.util.Base64.getEncoder().encodeToString(km.signingPublicKey()),
        )
    }
}
