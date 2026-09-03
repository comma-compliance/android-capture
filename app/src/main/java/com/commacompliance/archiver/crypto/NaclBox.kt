package com.commacompliance.archiver.crypto

/**
 * The minimal NaCl public-key authenticated-encryption surface the upload
 * envelope needs. Pulled behind an interface so the envelope logic is unit-
 * testable on a host JVM with a desktop-native libsodium, while the app wires in
 * the device-native libsodium - both via the same libsodium primitive.
 *
 * Semantics MUST match libsodium `crypto_box_easy` / `crypto_box_keypair` and
 * therefore the server's RbNaCl::Box (Curve25519 + XSalsa20-Poly1305, combined
 * mode where the 16-byte MAC is prepended to the ciphertext).
 */
interface NaclBox {
    /** A freshly generated Curve25519 keypair (32-byte secret + 32-byte public). */
    data class KeyPair(val secretKey: ByteArray, val publicKey: ByteArray)

    fun generateKeyPair(): KeyPair

    /** Derive the 32-byte public key from a 32-byte secret key. */
    fun publicKeyFromSecret(secretKey: ByteArray): ByteArray

    /**
     * Combined-mode box: encrypt [message] from [senderSecretKey] to
     * [recipientPublicKey] under [nonce] (24 bytes). Returns MAC(16) || ciphertext,
     * exactly what RbNaCl::Box#decrypt expects.
     */
    fun boxEasy(
        message: ByteArray,
        nonce: ByteArray,
        recipientPublicKey: ByteArray,
        senderSecretKey: ByteArray,
    ): ByteArray

    /**
     * Inverse of [boxEasy]; present so the round-trip vector test can prove the
     * client envelope decrypts under the server's key roles without standing up
     * Ruby. Returns the plaintext or throws on auth failure.
     */
    fun boxOpenEasy(
        cipher: ByteArray,
        nonce: ByteArray,
        senderPublicKey: ByteArray,
        recipientSecretKey: ByteArray,
    ): ByteArray

    fun randomNonce(): ByteArray

    /**
     * A freshly generated Ed25519 signing keypair (64-byte secret + 32-byte
     * public). The signing key is SEPARATE from the box (encryption) key: signing
     * proves possession of the device during registration, encryption protects the
     * upload envelope. They are never the same key.
     */
    data class SigningKeyPair(val secretKey: ByteArray, val publicKey: ByteArray)

    fun generateSigningKeyPair(): SigningKeyPair

    /**
     * Detached Ed25519 signature of [message] under [signingSecretKey] (the
     * 64-byte libsodium signing secret key). Returns the 64-byte signature,
     * matching libsodium `crypto_sign_detached` / the server's RbNaCl::SigningKey.
     */
    fun signDetached(message: ByteArray, signingSecretKey: ByteArray): ByteArray

    /**
     * Inverse of [signDetached]; present so the signing round-trip test can verify
     * a signature against the public key without standing up Ruby. Returns true on
     * a valid signature.
     */
    fun verifyDetached(signature: ByteArray, message: ByteArray, signingPublicKey: ByteArray): Boolean

    companion object {
        const val PUBLIC_KEY_BYTES = 32
        const val SECRET_KEY_BYTES = 32
        const val NONCE_BYTES = 24
        const val MAC_BYTES = 16
        const val SIGN_PUBLIC_KEY_BYTES = 32
        const val SIGN_SECRET_KEY_BYTES = 64
        const val SIGNATURE_BYTES = 64
    }
}
