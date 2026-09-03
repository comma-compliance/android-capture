package com.commacompliance.archiver.crypto

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.commacompliance.archiver.util.Hex
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Owns the device's long-lived Curve25519 keypair used for the upload envelope.
 *
 * The 32-byte libsodium box secret key is held in EncryptedSharedPreferences,
 * whose backing AES-256-GCM key lives in the Android Keystore. The Keystore
 * cannot itself hold an exportable X25519 scalar for libsodium box use, so the
 * scalar is kept app-side under a Keystore-wrapped envelope - standard for NaCl
 * keys on Android.
 *
 * The keypair is generated once on first access and is stable thereafter; the
 * server binds enrollment to its fingerprint, so rotating it silently would
 * orphan the device. Clearing app data deliberately forces a fresh keypair (and
 * thus a fresh enrollment).
 *
 * It ALSO owns a SEPARATE Ed25519 signing keypair used only to prove possession
 * of the device during self-serve registration (the device signs a server
 * challenge). Signing keys and box (encryption) keys are cryptographically
 * distinct purposes and are never the same key; the two live under distinct
 * preference entries so neither can overwrite the other.
 */
class KeyManager(
    context: Context,
    private val box: NaclBox,
    prefsFactory: (Context) -> SharedPreferences = ::defaultPrefs,
) {
    private val prefs: SharedPreferences = prefsFactory(context.applicationContext)

    /** Raw 32-byte device public key, generating the keypair on first call. */
    fun publicKey(): ByteArray = box.publicKeyFromSecret(secretKey())

    /**
     * Raw 32-byte device secret key, generating the keypair on first call. The
     * generate-once is guarded by a process-wide lock (not just this instance) so
     * two KeyManagers built in different components cannot both generate and one
     * overwrite the other - a regenerated key would orphan the enrollment binding
     * (the server 409s on a changed fingerprint). The commit() inside the lock makes
     * the write visible before the lock is released.
     */
    @SuppressLint("ApplySharedPref") // commit() is the durability guarantee above, not a slip.
    fun secretKey(): ByteArray = synchronized(GENERATION_LOCK) {
        prefs.getString(KEY_SECRET, null)?.let { return Base64.getDecoder().decode(it) }
        val pair = box.generateKeyPair()
        prefs.edit()
            .putString(KEY_SECRET, Base64.getEncoder().encodeToString(pair.secretKey))
            .commit()
        pair.secretKey
    }

    /**
     * The 32-byte passphrase for the SQLCipher-encrypted on-device event queue,
     * generating it once on first call and returning the same bytes thereafter.
     *
     * Held in the SAME Keystore-backed EncryptedSharedPreferences as the box and
     * signing keys, under a distinct entry, and generated once under the shared
     * generate-once lock with a commit() durability barrier - mirroring
     * [secretKey]. The bytes come from [SecureRandom] (a full 256-bit secret).
     * SQLCipher receives the raw 32 bytes as its passphrase (it runs its standard
     * PBKDF2 key derivation over them - not raw-key mode, which would need the
     * `x'hex'` form); the SAME byte array is handed to both the open-helper factory
     * and the plaintext->encrypted export, so the derived key cannot diverge between
     * the two paths.
     *
     * Stability is load-bearing: the passphrase is the ONLY thing that can decrypt
     * the durable queue. If it were silently regenerated while an encrypted DB
     * existed, the queue would become permanently unreadable and captured-but-unsent
     * events would be lost - which a compliance archiver must never do. The
     * generate-once + commit() guarantee here keeps it stable; the open path treats
     * an unexpectedly-absent passphrase beside an existing DB as fail-closed rather
     * than regenerating (see ArchiverDatabase).
     */
    @SuppressLint("ApplySharedPref") // commit() is the durability guarantee above, not a slip.
    fun dbPassphrase(): ByteArray = synchronized(GENERATION_LOCK) {
        prefs.getString(KEY_DB_PASSPHRASE, null)?.let { return Base64.getDecoder().decode(it) }
        val passphrase = ByteArray(DB_PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        val stored = prefs.edit()
            .putString(KEY_DB_PASSPHRASE, Base64.getEncoder().encodeToString(passphrase))
            .commit()
        // If the write did not durably land we must NOT return the key: the caller
        // would create/encrypt the DB with a passphrase that vanishes on restart,
        // and the next start would fail-closed forever against an unopenable queue.
        // Throwing here keeps the failure recoverable - no encrypted DB is created.
        check(stored) { "could not persist db passphrase; refusing to key an unrecoverable database" }
        passphrase
    }

    /** True once a database passphrase has been generated and stored - the open path's fail-closed gate. */
    fun hasDbPassphrase(): Boolean = prefs.contains(KEY_DB_PASSPHRASE)

    /** Lowercase hex SHA-256 of the raw 32-byte public key - the server's `device_pubkey_fingerprint`. */
    fun fingerprint(): String = fingerprintOf(publicKey())

    fun publicKeyBase64(): String = Base64.getEncoder().encodeToString(publicKey())

    /** Raw 32-byte Ed25519 signing public key, generating the signing keypair on first call. */
    fun signingPublicKey(): ByteArray = signingSecretKey().copyOfRange(
        NaclBox.SIGN_SECRET_KEY_BYTES - NaclBox.SIGN_PUBLIC_KEY_BYTES,
        NaclBox.SIGN_SECRET_KEY_BYTES,
    )

    fun signingPublicKeyBase64(): String = Base64.getEncoder().encodeToString(signingPublicKey())

    /**
     * Sign [message] with the device's Ed25519 signing key, returning the 64-byte
     * detached signature the server verifies against [signingPublicKey]. Used to
     * answer the registration challenge.
     */
    fun sign(message: ByteArray): ByteArray = box.signDetached(message, signingSecretKey())

    /**
     * Raw 64-byte Ed25519 signing secret key, generating the signing keypair on
     * first call. The libsodium signing secret key embeds the public key in its
     * last 32 bytes, so [signingPublicKey] derives the public half from it without
     * a second stored entry. Same generate-once lock + commit() durability as the
     * box key so two components cannot both generate and one overwrite the other.
     */
    @SuppressLint("ApplySharedPref") // commit() is the durability guarantee above, not a slip.
    private fun signingSecretKey(): ByteArray = synchronized(GENERATION_LOCK) {
        prefs.getString(KEY_SIGNING_SECRET, null)?.let { return Base64.getDecoder().decode(it) }
        val pair = box.generateSigningKeyPair()
        prefs.edit()
            .putString(KEY_SIGNING_SECRET, Base64.getEncoder().encodeToString(pair.secretKey))
            .commit()
        pair.secretKey
    }

    companion object {
        private const val PREFS_NAME = "archiver_keys"
        private const val KEY_SECRET = "device_box_secret"
        private const val KEY_SIGNING_SECRET = "device_signing_secret"
        private const val KEY_DB_PASSPHRASE = "db_passphrase"

        /** 256-bit SQLCipher passphrase - full-entropy random bytes, no human input. */
        const val DB_PASSPHRASE_BYTES = 32

        // Serializes first-run key generation across all KeyManager instances in the
        // process (each component builds its own), so the keypair is generated once.
        private val GENERATION_LOCK = Any()

        fun fingerprintOf(publicKey: ByteArray): String =
            Hex.encode(MessageDigest.getInstance("SHA-256").digest(publicKey))

        private fun defaultPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
