package com.commacompliance.archiver.onboarding

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
 * Exercises the two-step device-registration handshake against scripted server
 * responses: request serialization (the fields the server's begin/complete
 * actions read), the challenge-sign-and-verify round trip, and the mapping of
 * each server status to a client Result.
 */
@RunWith(RobolectricTestRunner::class)
class RegistrationClientTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val box = TestSodium.box()
    private val keyManager = KeyManager(context, box) { FakeSharedPreferences() }

    private val origin = "https://acme.example.com"
    private val token = "oauth-access-token"

    private fun client(http: FakeHttpClient) = RegistrationClient(http, keyManager)

    private fun beginResponse(challenge: ByteArray): String = JSONObject().apply {
        put("registration_id", "reg_123")
        put("challenge", Base64.getEncoder().encodeToString(challenge))
        put("expires_at", "2026-01-01T00:00:00Z")
    }.toString()

    private fun completeResponse(token: String, backfill: Int): String {
        val destPub = box.generateKeyPair().publicKey
        return JSONObject().apply {
            put("archive_token", token)
            put("device_id", "amdev_9")
            put("destination_public_key", Base64.getEncoder().encodeToString(destPub))
            put("kid", "KID123=")
            put("backfill_days", backfill)
        }.toString()
    }

    @Test
    fun happy_path_serializes_begin_signs_challenge_and_persists_provision() {
        val challenge = ByteArray(NaclBox.PUBLIC_KEY_BYTES) { (it * 3).toByte() }
        val http = FakeHttpClient()
        http.enqueue(201, beginResponse(challenge))
        http.enqueue(200, completeResponse("arc_token_xyz", 7))

        val result = client(http).register(
            backendOrigin = origin,
            accessToken = token,
            membershipId = "m_1",
            enrollmentSpecificId = "esid",
            deviceLabel = "Pixel 8",
        )

        result as RegistrationClient.Result.Success
        assertEquals("arc_token_xyz", result.enrollment.archiveToken)
        assertEquals("amdev_9", result.enrollment.deviceId)
        assertEquals(7, result.enrollment.backfillDays)

        // begin request: correct URL, Bearer auth, and the device key material.
        val begin = http.calls[0]
        assertEquals(origin + RegistrationClient.BEGIN_PATH, begin.url)
        assertEquals("Bearer $token", begin.headers["Authorization"])
        val beginBody = JSONObject(begin.body)
        assertEquals(keyManager.publicKeyBase64(), beginBody.getString("device_pubkey"))
        assertEquals(keyManager.signingPublicKeyBase64(), beginBody.getString("signing_public_key"))
        assertEquals(keyManager.fingerprint(), beginBody.getString("device_pubkey_fingerprint"))
        assertEquals("esid", beginBody.getString("enrollment_specific_id"))
        assertEquals("Pixel 8", beginBody.getString("device_label"))
        assertEquals("m_1", beginBody.getString("membership_id"))

        // complete request: correct URL + a signature over the SERVER's challenge
        // that verifies against the signing public key the device submitted.
        val complete = http.calls[1]
        assertEquals(
            RegistrationClient.completeUrl(origin, "reg_123"),
            complete.url,
        )
        val signature = Base64.getDecoder().decode(JSONObject(complete.body).getString("signature"))
        assertTrue(box.verifyDetached(signature, challenge, keyManager.signingPublicKey()))
    }

    @Test
    fun feature_disabled_maps_to_FeatureDisabled() {
        val http = FakeHttpClient()
        http.enqueue(403, """{"error":"capture_disabled"}""")
        assertEquals(
            RegistrationClient.Result.FeatureDisabled,
            client(http).register(origin, token, null, null, null),
        )
    }

    @Test
    fun unauthorized_client_on_begin_maps_to_Unauthorized() {
        val http = FakeHttpClient()
        http.enqueue(403, """{"error":"unauthorized_client"}""")
        assertEquals(
            RegistrationClient.Result.Unauthorized,
            client(http).register(origin, token, null, null, null),
        )
    }

    @Test
    fun expired_or_consumed_registration_on_complete_maps_to_RegistrationExpired() {
        val http = FakeHttpClient()
        http.enqueue(201, beginResponse(ByteArray(32) { 1 }))
        http.enqueue(404, """{"error":"invalid_registration"}""")
        assertEquals(
            RegistrationClient.Result.RegistrationExpired,
            client(http).register(origin, token, null, null, null),
        )
    }

    @Test
    fun bad_signature_409_or_422_on_complete_maps_to_RegistrationExpired() {
        val http = FakeHttpClient()
        http.enqueue(201, beginResponse(ByteArray(32) { 2 }))
        http.enqueue(422, """{"error":"invalid_signature"}""")
        assertEquals(
            RegistrationClient.Result.RegistrationExpired,
            client(http).register(origin, token, null, null, null),
        )
    }

    @Test
    fun unauthorized_oauth_token_maps_to_Unauthorized() {
        val http = FakeHttpClient()
        http.enqueue(401, "")
        assertEquals(
            RegistrationClient.Result.Unauthorized,
            client(http).register(origin, token, null, null, null),
        )
    }

    @Test
    fun omits_membership_id_when_absent() {
        val http = FakeHttpClient()
        http.enqueue(201, beginResponse(ByteArray(32) { 4 }))
        http.enqueue(200, completeResponse("t", 0))
        client(http).register(origin, token, membershipId = null, enrollmentSpecificId = null, deviceLabel = null)
        val beginBody = JSONObject(http.calls[0].body)
        assertTrue(!beginBody.has("membership_id"))
    }

    @Test
    fun malformed_complete_body_is_failed_not_a_crash() {
        val http = FakeHttpClient()
        http.enqueue(201, beginResponse(ByteArray(NaclBox.PUBLIC_KEY_BYTES) { 5 }))
        http.enqueue(200, "not json {")
        assertEquals(RegistrationClient.Result.Failed(200), client(http).register(origin, token, null, null, null))
    }

    @Test
    fun short_destination_key_on_complete_is_failed() {
        val http = FakeHttpClient()
        http.enqueue(201, beginResponse(ByteArray(NaclBox.PUBLIC_KEY_BYTES) { 6 }))
        // A destination key that decodes to fewer than 32 bytes is rejected.
        val body = JSONObject().apply {
            put("archive_token", "arc")
            put("device_id", "amdev_9")
            put("destination_public_key", Base64.getEncoder().encodeToString(ByteArray(16)))
            put("kid", "KID123=")
        }.toString()
        http.enqueue(200, body)
        assertEquals(RegistrationClient.Result.Failed(200), client(http).register(origin, token, null, null, null))
    }

    @Test
    fun includes_integrity_token_and_binds_requesthash_to_the_challenge_string() {
        // Fixed challenge so we can assert the requestHash the source was handed.
        val challenge = ByteArray(NaclBox.PUBLIC_KEY_BYTES) { (it + 1).toByte() }
        val challengeB64 = Base64.getEncoder().encodeToString(challenge)
        val beginBody = JSONObject().apply {
            put("registration_id", "reg_123")
            put("challenge", challengeB64)
            put("expires_at", "2026-01-01T00:00:00Z")
        }.toString()

        val http = FakeHttpClient()
        http.enqueue(201, beginBody)
        http.enqueue(200, completeResponse("arc", 0))

        val integrity = FakeIntegrityTokenSource(token = "itok-abc")
        val client = RegistrationClient(http, keyManager, integrity)
        val result = client.register(origin, token, null, null, null, cloudProjectNumber = 123456789012L)
        result as RegistrationClient.Result.Success

        // The complete payload carries the integrity_token.
        val complete = JSONObject(http.calls[1].body)
        assertEquals("itok-abc", complete.getString("integrity_token"))

        // The source was asked with the project number and the hash of the b64 STRING.
        val call = integrity.calls.single()
        assertEquals(123456789012L, call.cloudProjectNumber)
        assertEquals(RequestHash.forChallenge(challengeB64), call.requestHash)
    }

    @Test
    fun omits_integrity_token_when_no_cloud_project_number() {
        // Absent cloud_project_number -> no acquisition at all, body unchanged.
        val http = FakeHttpClient()
        http.enqueue(201, beginResponse(ByteArray(32) { 7 }))
        http.enqueue(200, completeResponse("arc", 0))
        val integrity = FakeIntegrityTokenSource(token = "itok-abc")
        RegistrationClient(http, keyManager, integrity)
            .register(origin, token, null, null, null, cloudProjectNumber = null)
        assertTrue(integrity.calls.isEmpty())
        assertFalse(JSONObject(http.calls[1].body).has("integrity_token"))
    }

    @Test
    fun omits_integrity_token_when_play_services_absent() {
        // cloud_project_number present but the source returns null (Play absent):
        // registration still completes, body simply omits the token.
        val http = FakeHttpClient()
        http.enqueue(201, beginResponse(ByteArray(32) { 8 }))
        http.enqueue(200, completeResponse("arc", 0))
        val integrity = FakeIntegrityTokenSource(token = null)
        val result = RegistrationClient(http, keyManager, integrity)
            .register(origin, token, null, null, null, cloudProjectNumber = 42L)
        assertTrue(result is RegistrationClient.Result.Success)
        assertEquals(1, integrity.calls.size)
        assertFalse(JSONObject(http.calls[1].body).has("integrity_token"))
    }

    @Test
    fun non_https_origin_makes_no_request_and_is_failed() {
        // Defense-in-depth: register() carries the OAuth access token as a bearer
        // header, so a non-https origin is refused BEFORE any request is sent.
        val http = FakeHttpClient()
        val result = client(http).register("http://acme.example.com", token, null, null, null)
        assertTrue(result is RegistrationClient.Result.Failed)
        assertTrue(http.calls.isEmpty())
    }
}
