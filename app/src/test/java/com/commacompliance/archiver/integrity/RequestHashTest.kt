package com.commacompliance.archiver.integrity

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fixed vectors for the self-serve integrity requestHash. The server re-derives the
 * SAME value (lowercase hex SHA-256 of the challenge STRING's UTF-8 bytes - the
 * base64 text as received, NOT its decoded bytes), so a drift here breaks
 * attestation enforcement silently. The vectors below were computed independently
 * (`printf '%s' '<challenge>' | sha256sum`).
 */
class RequestHashTest {

    @Test
    fun hashes_the_challenge_string_bytes_not_the_decoded_bytes() {
        // "dGVzdC1jaGFsbGVuZ2U=" is base64("test-challenge"); we hash the b64 TEXT.
        val challengeB64 = "dGVzdC1jaGFsbGVuZ2U="
        assertEquals(
            "247af9ed3a1a4119eb004d9029e0fbb9f580f4e2a133ff486f59b0a80f0c0177",
            RequestHash.forChallenge(challengeB64),
        )
    }

    @Test
    fun empty_challenge_vector() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            RequestHash.forChallenge(""),
        )
    }

    @Test
    fun output_is_lowercase_hex_64_chars() {
        val hash = RequestHash.forChallenge("AnyChallengeValue==")
        assertEquals(64, hash.length)
        assertEquals(hash.lowercase(), hash)
        assert(hash.matches(Regex("^[0-9a-f]{64}$")))
    }
}
