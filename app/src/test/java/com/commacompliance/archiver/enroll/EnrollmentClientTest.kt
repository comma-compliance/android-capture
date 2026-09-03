package com.commacompliance.archiver.enroll

import androidx.test.core.app.ApplicationProvider
import com.commacompliance.archiver.FakeSharedPreferences
import com.commacompliance.archiver.crypto.KeyManager
import com.commacompliance.archiver.crypto.NaclBox
import com.commacompliance.archiver.crypto.TestSodium
import com.commacompliance.archiver.integrity.FakeIntegrityTokenSource
import com.commacompliance.archiver.integrity.RequestHash
import com.commacompliance.archiver.net.FakeHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

/**
 * Exercises the two-step signed EMM enroll handshake against scripted server
 * responses: the begin request serialization (the fields the server's enroll_begin
 * reads), the challenge-sign-and-verify round trip, the Play Integrity requestHash
 * binding, the provision parse, and the mapping of each server status to a Result.
 */
@RunWith(RobolectricTestRunner::class)
class EnrollmentClientTest {

    private val box = TestSodium.box()
    private val keyManager = KeyManager(
        ApplicationProvider.getApplicationContext(),
        box,
    ) { FakeSharedPreferences() }
    private val http = FakeHttpClient()
    private val client = EnrollmentClient(http, keyManager)

    private val backend = "https://ingest.example.com/"

    private fun beginResponse(challenge: ByteArray): String = JSONObject().apply {
        put("challenge_id", "ch_123")
        put("challenge", Base64.getEncoder().encodeToString(challenge))
        put("expires_at", "2026-01-01T00:00:00Z")
    }.toString()

    private fun completeResponse(token: String = "comma_test_abc", backfill: Int = 0): String {
        val destPub = box.generateKeyPair().publicKey
        return JSONObject().apply {
            put("archive_token", token)
            put("device_id", "amdev_1")
            put("destination_public_key", Base64.getEncoder().encodeToString(destPub))
            put("kid", "AbCdEfGhIjK=")
            put("backfill_days", backfill)
        }.toString()
    }

    private fun fixedChallenge(): ByteArray = ByteArray(NaclBox.PUBLIC_KEY_BYTES) { (it * 3).toByte() }

    @Test
    fun begin_request_serializes_the_exact_wire_fields() {
        http.enqueue(201, beginResponse(fixedChallenge()))
        http.enqueue(200, completeResponse())

        client.enroll(
            backendUrl = backend,
            enrollmentToken = "tok-123",
            enrollmentSpecificId = "esid-9",
            deviceLabel = "Google Pixel 8",
        )

        val begin = http.calls[0]
        assertEquals("https://ingest.example.com/api/v1/android_archiver/enroll/begin", begin.url)
        val body = JSONObject(begin.body)
        assertEquals("tok-123", body.getString("enrollment_token"))
        assertEquals(keyManager.publicKeyBase64(), body.getString("device_pubkey"))
        assertEquals(keyManager.signingPublicKeyBase64(), body.getString("signing_public_key"))
        assertEquals(keyManager.fingerprint(), body.getString("device_pubkey_fingerprint"))
        assertEquals("esid-9", body.getString("enrollment_specific_id"))
        assertEquals("Google Pixel 8", body.getString("device_label"))
        // The device pubkey + signing key each decode to 32 bytes; the fingerprint is hex SHA-256.
        assertEquals(32, Base64.getDecoder().decode(body.getString("device_pubkey")).size)
        assertEquals(32, Base64.getDecoder().decode(body.getString("signing_public_key")).size)
        assertTrue(body.getString("device_pubkey_fingerprint").matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun happy_path_signs_the_server_challenge_and_parses_provision() {
        val challenge = fixedChallenge()
        http.enqueue(201, beginResponse(challenge))
        http.enqueue(200, completeResponse(token = "comma_test_xyz", backfill = 7))

        val result = client.enroll(backend, "t", null, null) as EnrollmentClient.Result.Success
        assertEquals("comma_test_xyz", result.enrollment.archiveToken)
        assertEquals("amdev_1", result.enrollment.deviceId)
        assertEquals("AbCdEfGhIjK=", result.enrollment.kid)
        assertEquals(32, result.enrollment.destinationPublicKey.size)
        assertEquals(7, result.enrollment.backfillDays)

        // complete request: correct URL, the challenge_id from begin, and a signature
        // over the SERVER's raw challenge bytes that verifies against the signing key
        // the device submitted at begin.
        val complete = http.calls[1]
        assertEquals("https://ingest.example.com/api/v1/android_archiver/enroll/complete", complete.url)
        val completeBody = JSONObject(complete.body)
        assertEquals("ch_123", completeBody.getString("challenge_id"))
        val signature = Base64.getDecoder().decode(completeBody.getString("signature"))
        assertEquals(64, signature.size)
        assertTrue(box.verifyDetached(signature, challenge, keyManager.signingPublicKey()))
    }

    @Test
    fun null_enrollment_specific_id_and_label_serialize_as_json_null() {
        http.enqueue(201, beginResponse(fixedChallenge()))
        http.enqueue(200, completeResponse())
        client.enroll(backend, "t", null, null)
        val body = JSONObject(http.calls[0].body)
        assertTrue(body.isNull("enrollment_specific_id"))
        assertTrue(body.isNull("device_label"))
    }

    @Test
    fun integrity_token_binds_requesthash_to_the_challenge_string() {
        // Fixed challenge so we can assert the requestHash the source was handed: the
        // SHA-256 of the base64 challenge STRING, matching the server.
        val challenge = ByteArray(NaclBox.PUBLIC_KEY_BYTES) { (it + 1).toByte() }
        val challengeB64 = Base64.getEncoder().encodeToString(challenge)
        http.enqueue(201, beginResponse(challenge))
        http.enqueue(200, completeResponse())

        val integrity = FakeIntegrityTokenSource(token = "itok-abc")
        val withIntegrity = EnrollmentClient(http, keyManager, integrity)
        val result = withIntegrity.enroll(backend, "t", null, null, cloudProjectNumber = 123456789012L)
        result as EnrollmentClient.Result.Success

        // The complete payload carries the integrity_token (not the begin payload).
        assertEquals("itok-abc", JSONObject(http.calls[1].body).getString("integrity_token"))
        assertFalse(JSONObject(http.calls[0].body).has("integrity_token"))

        // The source was asked with the project number and the hash of the b64 STRING.
        val call = integrity.calls.single()
        assertEquals(123456789012L, call.cloudProjectNumber)
        assertEquals(RequestHash.forChallenge(challengeB64), call.requestHash)
    }

    @Test
    fun omits_integrity_token_when_no_cloud_project_number() {
        http.enqueue(201, beginResponse(fixedChallenge()))
        http.enqueue(200, completeResponse())
        val integrity = FakeIntegrityTokenSource(token = "itok-abc")
        EnrollmentClient(http, keyManager, integrity).enroll(backend, "t", null, null, cloudProjectNumber = null)
        assertTrue(integrity.calls.isEmpty())
        assertFalse(JSONObject(http.calls[1].body).has("integrity_token"))
    }

    @Test
    fun omits_integrity_token_when_play_services_absent() {
        // cloud_project_number present but the source yields null (Play absent): the
        // enroll still completes, the complete body simply omits the token.
        http.enqueue(201, beginResponse(fixedChallenge()))
        http.enqueue(200, completeResponse())
        val integrity = FakeIntegrityTokenSource(token = null)
        val result = EnrollmentClient(http, keyManager, integrity)
            .enroll(backend, "t", null, null, cloudProjectNumber = 555L)
        assertTrue(result is EnrollmentClient.Result.Success)
        assertEquals(1, integrity.calls.size)
        assertFalse(JSONObject(http.calls[1].body).has("integrity_token"))
    }

    @Test
    fun maps_401_to_invalid_token() {
        http.enqueue(401, """{"error":"invalid_token"}""")
        assertTrue(client.enroll(backend, "t", null, null) is EnrollmentClient.Result.InvalidToken)
        // begin failed; complete is never attempted.
        assertEquals(1, http.calls.size)
    }

    @Test
    fun maps_401_on_complete_to_challenge_expired_not_invalid_token() {
        // begin succeeds (the enrollment_token is good), then the challenge expires or
        // is consumed before complete -> the server's invalid_token (401) on COMPLETE.
        // That is transient: the device must re-run the handshake, NOT treat the
        // enrollment_token as dead, so it is ChallengeExpired and never InvalidToken.
        http.enqueue(201, beginResponse(fixedChallenge()))
        http.enqueue(401, """{"error":"invalid_token"}""")
        val result = client.enroll(backend, "t", null, null)
        assertTrue(result is EnrollmentClient.Result.ChallengeExpired)
        assertFalse(result is EnrollmentClient.Result.InvalidToken)
        // Both steps were attempted: begin succeeded, complete returned 401.
        assertEquals(2, http.calls.size)
    }

    @Test
    fun maps_409_fingerprint_mismatch() {
        http.enqueue(409, """{"error":"fingerprint_mismatch"}""")
        assertTrue(client.enroll(backend, "t", null, null) is EnrollmentClient.Result.FingerprintMismatch)
    }

    @Test
    fun maps_409_signing_key_mismatch_distinctly() {
        http.enqueue(409, """{"error":"signing_key_mismatch"}""")
        assertTrue(client.enroll(backend, "t", null, null) is EnrollmentClient.Result.SigningKeyMismatch)
    }

    @Test
    fun maps_unknown_409_body_to_failed_not_a_credential_wipe() {
        // An unexpected or body-less 409 (a proxy / backend glitch, not one of the two
        // known terminal reasons) must NOT be treated as a fingerprint mismatch - that
        // would wipe a still-valid enrollment downstream. It is a transient Failed.
        http.enqueue(409, "")
        assertEquals(EnrollmentClient.Result.Failed(409), client.enroll(backend, "t", null, null))
    }

    @Test
    fun maps_403_to_forbidden_with_reason() {
        http.enqueue(403, """{"error":"capture_disabled"}""")
        val result = client.enroll(backend, "t", null, null)
        result as EnrollmentClient.Result.Forbidden
        assertEquals("capture_disabled", result.reason)
    }

    @Test
    fun maps_422_on_complete_to_invalid_signature() {
        // A 422 surfaces from the complete step; the shared mapper handles both steps.
        http.enqueue(201, beginResponse(fixedChallenge()))
        http.enqueue(422, """{"error":"invalid_signature"}""")
        assertTrue(client.enroll(backend, "t", null, null) is EnrollmentClient.Result.InvalidSignature)
    }

    @Test
    fun maps_429_rate_limited_to_failed() {
        http.enqueue(429, """{"error":"rate_limited"}""")
        assertEquals(EnrollmentClient.Result.Failed(429), client.enroll(backend, "t", null, null))
    }

    @Test
    fun maps_other_codes_to_failed() {
        http.enqueue(500, """{"error":"enrollment_failed"}""")
        assertEquals(EnrollmentClient.Result.Failed(500), client.enroll(backend, "t", null, null))
    }

    @Test
    fun malformed_begin_body_is_failed_not_a_crash() {
        // A 201 missing the challenge fields must NOT throw and must NOT send a complete.
        http.enqueue(201, """{"expires_at":"2026-01-01T00:00:00Z"}""")
        assertTrue(client.enroll(backend, "t", null, null) is EnrollmentClient.Result.Failed)
        assertEquals(1, http.calls.size)
    }

    @Test
    fun non_decodable_begin_challenge_is_failed() {
        val body = JSONObject().apply {
            put("challenge_id", "ch_1")
            put("challenge", "!!! not base64 !!!")
            put("expires_at", "2026-01-01T00:00:00Z")
        }.toString()
        http.enqueue(201, body)
        assertTrue(client.enroll(backend, "t", null, null) is EnrollmentClient.Result.Failed)
        assertEquals(1, http.calls.size)
    }

    @Test
    fun too_short_begin_challenge_is_failed() {
        // A challenge shorter than the contracted 32 bytes is a malformed/hostile
        // begin response and must not be signed.
        http.enqueue(201, beginResponse(ByteArray(16) { 1 }))
        assertTrue(client.enroll(backend, "t", null, null) is EnrollmentClient.Result.Failed)
        assertEquals(1, http.calls.size)
    }

    @Test
    fun malformed_complete_body_is_failed_not_a_crash() {
        http.enqueue(201, beginResponse(fixedChallenge()))
        http.enqueue(200, "this is not json {")
        assertEquals(EnrollmentClient.Result.Failed(200), client.enroll(backend, "t", null, null))
    }

    @Test
    fun short_destination_key_on_complete_is_failed() {
        http.enqueue(201, beginResponse(fixedChallenge()))
        val body = JSONObject().apply {
            put("archive_token", "comma_test_abc")
            put("device_id", "amdev_1")
            put("destination_public_key", Base64.getEncoder().encodeToString(ByteArray(16)))
            put("kid", "AbCdEfGhIjK=")
        }.toString()
        http.enqueue(200, body)
        assertEquals(EnrollmentClient.Result.Failed(200), client.enroll(backend, "t", null, null))
    }

    @Test
    fun non_base64_destination_key_on_complete_is_failed() {
        http.enqueue(201, beginResponse(fixedChallenge()))
        val body = JSONObject().apply {
            put("archive_token", "comma_test_abc")
            put("device_id", "amdev_1")
            put("destination_public_key", "!!! not base64 !!!")
            put("kid", "AbCdEfGhIjK=")
        }.toString()
        http.enqueue(200, body)
        assertEquals(EnrollmentClient.Result.Failed(200), client.enroll(backend, "t", null, null))
    }
}
