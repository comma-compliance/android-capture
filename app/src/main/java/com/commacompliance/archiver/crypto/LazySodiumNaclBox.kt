package com.commacompliance.archiver.crypto

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.Sign
import com.goterl.lazysodium.utils.Key
import java.security.SecureRandom

/**
 * libsodium-backed [NaclBox]. Accepts any concrete lazysodium implementation
 * (which implement both [Box.Lazy]/[Box.Native]) so the app can supply the
 * device-native binding and host tests can supply the desktop-native binding.
 *
 * Nonces are drawn from [SecureRandom]; a 24-byte XSalsa20 nonce is large enough
 * that random generation per message is collision-safe for a single device key.
 */
class LazySodiumNaclBox(private val sodium: LazySodium) : NaclBox {

    private val box: Box.Native = sodium as Box.Native
    private val sign: Sign.Native = sodium as Sign.Native

    override fun generateKeyPair(): NaclBox.KeyPair {
        val publicKey = ByteArray(NaclBox.PUBLIC_KEY_BYTES)
        val secretKey = ByteArray(NaclBox.SECRET_KEY_BYTES)
        check(box.cryptoBoxKeypair(publicKey, secretKey)) { "crypto_box_keypair failed" }
        return NaclBox.KeyPair(secretKey = secretKey, publicKey = publicKey)
    }

    override fun publicKeyFromSecret(secretKey: ByteArray): ByteArray {
        require(secretKey.size == NaclBox.SECRET_KEY_BYTES) { "bad secret key length" }
        return sodium.cryptoScalarMultBase(Key.fromBytes(secretKey)).asBytes.copyOf()
    }

    override fun boxEasy(
        message: ByteArray,
        nonce: ByteArray,
        recipientPublicKey: ByteArray,
        senderSecretKey: ByteArray,
    ): ByteArray {
        require(nonce.size == NaclBox.NONCE_BYTES) { "bad nonce length" }
        require(recipientPublicKey.size == NaclBox.PUBLIC_KEY_BYTES) { "bad public key length" }
        require(senderSecretKey.size == NaclBox.SECRET_KEY_BYTES) { "bad secret key length" }

        val cipher = ByteArray(message.size + Box.MACBYTES)
        val ok = box.cryptoBoxEasy(
            cipher,
            message,
            message.size.toLong(),
            nonce,
            recipientPublicKey,
            senderSecretKey,
        )
        check(ok) { "crypto_box_easy failed" }
        return cipher
    }

    override fun boxOpenEasy(
        cipher: ByteArray,
        nonce: ByteArray,
        senderPublicKey: ByteArray,
        recipientSecretKey: ByteArray,
    ): ByteArray {
        require(cipher.size >= Box.MACBYTES) { "cipher too short" }
        val message = ByteArray(cipher.size - Box.MACBYTES)
        val ok = box.cryptoBoxOpenEasy(
            message,
            cipher,
            cipher.size.toLong(),
            nonce,
            senderPublicKey,
            recipientSecretKey,
        )
        check(ok) { "crypto_box_open_easy failed (auth)" }
        return message
    }

    override fun randomNonce(): ByteArray {
        val nonce = ByteArray(NaclBox.NONCE_BYTES)
        SecureRandom().nextBytes(nonce)
        return nonce
    }

    override fun generateSigningKeyPair(): NaclBox.SigningKeyPair {
        val publicKey = ByteArray(NaclBox.SIGN_PUBLIC_KEY_BYTES)
        val secretKey = ByteArray(NaclBox.SIGN_SECRET_KEY_BYTES)
        check(sign.cryptoSignKeypair(publicKey, secretKey)) { "crypto_sign_keypair failed" }
        return NaclBox.SigningKeyPair(secretKey = secretKey, publicKey = publicKey)
    }

    override fun signDetached(message: ByteArray, signingSecretKey: ByteArray): ByteArray {
        require(signingSecretKey.size == NaclBox.SIGN_SECRET_KEY_BYTES) { "bad signing secret key length" }
        val signature = ByteArray(NaclBox.SIGNATURE_BYTES)
        val ok = sign.cryptoSignDetached(
            signature,
            message,
            message.size.toLong(),
            signingSecretKey,
        )
        check(ok) { "crypto_sign_detached failed" }
        return signature
    }

    override fun verifyDetached(
        signature: ByteArray,
        message: ByteArray,
        signingPublicKey: ByteArray,
    ): Boolean {
        if (signature.size != NaclBox.SIGNATURE_BYTES) return false
        if (signingPublicKey.size != NaclBox.SIGN_PUBLIC_KEY_BYTES) return false
        return sign.cryptoSignVerifyDetached(
            signature,
            message,
            message.size,
            signingPublicKey,
        )
    }
}
