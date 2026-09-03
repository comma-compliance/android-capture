package com.commacompliance.archiver.crypto

import org.json.JSONObject
import java.util.Base64

/**
 * Builds the `nacl-box-v1` upload envelope the ingest server decrypts with
 * `Arc::EnvelopeDecryptor`. The wire shape is frozen and MUST be produced
 * byte-for-byte:
 *
 *   { "version": "nacl-box-v1",
 *     "kid": "<server-issued, echoed verbatim>",
 *     "sourcePublicKey": "<b64 device public key, 32 bytes>",
 *     "nonce": "<b64 24-byte nonce>",
 *     "ciphertext": "<b64 MAC(16)||ciphertext>" }
 *
 * Key roles mirror the server: the server decrypts with
 * RbNaCl::Box.new(sourcePublicKey, destination_private).decrypt(nonce, ciphertext),
 * so the client encrypts FROM the device secret key TO the destination public key.
 *
 * All binary fields are RFC 4648 base64 WITH padding and NO line wrapping
 * (java.util.Base64 default encoder); the server decodes with
 * Base64.strict_decode64, which rejects whitespace or missing padding.
 *
 * The `kid` is never computed here - the server mints it (blake2b of its own
 * destination public key); the client only echoes the value enrollment returned.
 */
class EnvelopeEncryptor(private val box: NaclBox) {

    companion object {
        const val VERSION = "nacl-box-v1"
    }

    /**
     * @param plaintext the UTF-8 batch JSON
     * @param kid the destination key id from enrollment (echoed verbatim)
     * @param devicePublicKey raw 32-byte device public key
     * @param deviceSecretKey raw 32-byte device secret key
     * @param destinationPublicKey raw 32-byte destination public key from enrollment
     */
    fun encrypt(
        plaintext: String,
        kid: String,
        devicePublicKey: ByteArray,
        deviceSecretKey: ByteArray,
        destinationPublicKey: ByteArray,
    ): JSONObject {
        require(devicePublicKey.size == NaclBox.PUBLIC_KEY_BYTES) { "device public key must be 32 bytes" }
        require(destinationPublicKey.size == NaclBox.PUBLIC_KEY_BYTES) { "destination public key must be 32 bytes" }

        val nonce = box.randomNonce()
        val cipher = box.boxEasy(
            message = plaintext.toByteArray(Charsets.UTF_8),
            nonce = nonce,
            recipientPublicKey = destinationPublicKey,
            senderSecretKey = deviceSecretKey,
        )

        val b64 = Base64.getEncoder()
        return JSONObject().apply {
            put("version", VERSION)
            put("kid", kid)
            put("sourcePublicKey", b64.encodeToString(devicePublicKey))
            put("nonce", b64.encodeToString(nonce))
            put("ciphertext", b64.encodeToString(cipher))
        }
    }
}
