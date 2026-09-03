package com.commacompliance.archiver.integrity

import com.commacompliance.archiver.util.Hex
import java.security.MessageDigest

/**
 * The Play Integrity `requestHash` binding. The server re-derives the SAME value and
 * rejects a verdict whose requestHash does not match, so this definition must stay
 * byte-identical to the server's. Both provisioning paths bind the attestation to
 * the challenge they received:
 *
 *   - Self-serve registration (begin_registration) and EMM enroll (enroll/begin):
 *     lowercase hex SHA-256 of the UTF-8 bytes of the `challenge` STRING the server
 *     returned - i.e. the base64 text as received, NOT its decoded bytes. The server
 *     hashes the same string it emitted, so the verdict is bound to that specific
 *     handshake and cannot be replayed against a different challenge.
 */
object RequestHash {
    /** Lowercase hex SHA-256 of [challengeString]'s UTF-8 bytes (the b64 text as received). */
    fun forChallenge(challengeString: String): String =
        Hex.encode(MessageDigest.getInstance("SHA-256").digest(challengeString.toByteArray(Charsets.UTF_8)))
}
