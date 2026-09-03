package com.commacompliance.archiver.onboarding

import com.commacompliance.archiver.crypto.KeyManager
import com.commacompliance.archiver.enroll.EnrollmentStore
import com.commacompliance.archiver.integrity.IntegrityTokenSource
import com.commacompliance.archiver.integrity.RequestHash
import com.commacompliance.archiver.net.HttpClient
import com.commacompliance.archiver.net.HttpsUrls
import com.commacompliance.archiver.util.anyBlank
import org.json.JSONObject
import java.util.Base64

/**
 * Performs the two-step self-serve device-registration handshake after the user
 * has signed in via OAuth (Authorization-Code + PKCE through a Custom Tab):
 *
 *   1. begin:  POST {backend}/api/v1/android_archiver/device_registrations
 *              (Bearer <oauth access token>, scope android_archiver)
 *              body { device_pubkey, signing_public_key, device_pubkey_fingerprint,
 *                     enrollment_specific_id, device_label }
 *              -> { registration_id, challenge(b64), expires_at }
 *
 *   2. complete: POST .../device_registrations/{id}/complete
 *              (Bearer <oauth access token>)
 *              body { signature(b64) } where signature = Ed25519 sign of the raw
 *              challenge bytes with the device signing key bound at begin
 *              -> { archive_token, device_id, destination_public_key, kid, backfill_days }
 *
 * The OAuth access token authorizes ONLY this setup exchange; the device then
 * runs long-term on the returned archive_token. The token is passed in per call
 * and never persisted or logged here.
 */
open class RegistrationClient(
    private val http: HttpClient,
    private val keyManager: KeyManager,
    // Attests the device/app for the complete step. Optional: a null source (or a
    // null cloudProjectNumber, or a source that returns null because Play services
    // are absent) simply omits integrity_token - registration still completes, and
    // the server's policy decides whether that is acceptable.
    private val integrityTokenSource: IntegrityTokenSource? = null,
) {
    sealed interface Result {
        data class Success(val enrollment: EnrollmentStore.Enrollment) : Result

        /** The team has not enabled Android capture, or the user lacks a usable membership (403). */
        data object FeatureDisabled : Result

        /** The challenge expired or the registration was already consumed; the user must retry. */
        data object RegistrationExpired : Result

        /** The OAuth token was rejected (401/403 unauthorized_client) - re-auth needed. */
        data object Unauthorized : Result

        /** Any other non-2xx; carries the status for diagnostics (never the body). */
        data class Failed(val code: Int) : Result
    }

    open fun register(
        backendOrigin: String,
        accessToken: String,
        membershipId: String?,
        enrollmentSpecificId: String?,
        deviceLabel: String?,
        // Google Cloud project number from discovery; null when the server has not
        // configured Play Integrity, in which case no attestation token is fetched.
        cloudProjectNumber: Long? = null,
    ): Result {
        // Defense-in-depth: this sends the OAuth access token as a bearer header, so
        // refuse to proceed unless the origin is https. In production the Activity
        // only passes a discovery-validated https origin here, but guarding at the
        // call boundary means a future caller cannot accidentally leak the token over
        // cleartext. A non-https origin surfaces as a generic failure (no request made).
        if (HttpsUrls.requireHttps(backendOrigin) == null) return Result.Failed(BAD_ORIGIN)

        val begin = beginRegistration(backendOrigin, accessToken, membershipId, enrollmentSpecificId, deviceLabel)
        val beginJson = when (begin) {
            is StepResult.Ok -> begin.json
            is StepResult.Err -> return begin.result
        }

        val registrationId = beginJson.optString("registration_id", "")
        val challengeB64 = beginJson.optString("challenge", "")
        if (registrationId.isBlank() || challengeB64.isBlank()) return Result.Failed(200)

        // The integrity requestHash binds the attestation to the challenge STRING as
        // received (the base64 text), hashed BEFORE we decode it - matching the
        // server, which hashes the same string it emitted. Fetch the token (best
        // effort) only when the server advertised a cloud project number.
        val integrityToken = acquireIntegrityToken(cloudProjectNumber, challengeB64)

        val challenge = runCatching { Base64.getDecoder().decode(challengeB64) }.getOrNull()
            ?: return Result.Failed(200)
        val signature = keyManager.sign(challenge)

        return completeRegistration(backendOrigin, accessToken, registrationId, signature, integrityToken)
    }

    // Best-effort attestation: returns null (and the complete payload omits
    // integrity_token) whenever integrity is not configured, no source is wired, or
    // Play services cannot produce a token. Never blocks or fails registration.
    private fun acquireIntegrityToken(cloudProjectNumber: Long?, challengeB64: String): String? {
        if (cloudProjectNumber == null) return null
        val source = integrityTokenSource ?: return null
        return source.fetch(cloudProjectNumber, RequestHash.forChallenge(challengeB64))
    }

    private sealed interface StepResult {
        data class Ok(val json: JSONObject) : StepResult
        data class Err(val result: Result) : StepResult
    }

    private fun beginRegistration(
        backendOrigin: String,
        accessToken: String,
        membershipId: String?,
        enrollmentSpecificId: String?,
        deviceLabel: String?,
    ): StepResult {
        val request = JSONObject().apply {
            put("device_pubkey", keyManager.publicKeyBase64())
            put("signing_public_key", keyManager.signingPublicKeyBase64())
            put("device_pubkey_fingerprint", keyManager.fingerprint())
            put("enrollment_specific_id", enrollmentSpecificId ?: JSONObject.NULL)
            put("device_label", deviceLabel ?: JSONObject.NULL)
            if (!membershipId.isNullOrBlank()) put("membership_id", membershipId)
        }

        val response = http.postJson(
            url = backendOrigin.trimEnd('/') + BEGIN_PATH,
            body = request.toString(),
            headers = bearer(accessToken),
        )

        return when (response.code) {
            in 200..299 -> {
                val json = runCatching { JSONObject(response.body) }.getOrNull()
                    ?: return StepResult.Err(Result.Failed(response.code))
                StepResult.Ok(json)
            }
            401 -> StepResult.Err(Result.Unauthorized)
            403 -> StepResult.Err(classifyForbidden(response.body))
            else -> StepResult.Err(Result.Failed(response.code))
        }
    }

    private fun completeRegistration(
        backendOrigin: String,
        accessToken: String,
        registrationId: String,
        signature: ByteArray,
        integrityToken: String?,
    ): Result {
        val request = JSONObject().apply {
            put("signature", Base64.getEncoder().encodeToString(signature))
            // Optional per contract; omitted entirely when no token was produced so
            // an integrity-unconfigured request body is unchanged from before.
            if (integrityToken != null) put("integrity_token", integrityToken)
        }

        val response = http.postJson(
            url = completeUrl(backendOrigin, registrationId),
            body = request.toString(),
            headers = bearer(accessToken),
        )

        return when (response.code) {
            in 200..299 -> parseProvision(response.body)
            401 -> Result.Unauthorized
            403 -> classifyForbidden(response.body)
            // Generic 404 (unknown/expired/foreign id), 409 (already consumed/raced),
            // 422 (bad signature) all mean: this attempt cannot complete; start over.
            404, 409, 422 -> Result.RegistrationExpired
            else -> Result.Failed(response.code)
        }
    }

    // As with the enroll path, a 200 carrying a malformed body or a destination key
    // that is not valid base64 / not a 32-byte key is Failed(200), never a crash.
    // The decode + length check is delegated to EnrollmentStore so both provisioning
    // paths reject a non-conforming key identically.
    private fun parseProvision(body: String): Result {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return Result.Failed(200)
        val archiveToken = json.optString("archive_token", "")
        val deviceId = json.optString("device_id", "")
        val destB64 = json.optString("destination_public_key", "")
        val kid = json.optString("kid", "")
        if (anyBlank(archiveToken, deviceId, destB64, kid)) {
            return Result.Failed(200)
        }
        val destinationKey = EnrollmentStore.decodeDestinationKey(destB64) ?: return Result.Failed(200)
        return Result.Success(
            EnrollmentStore.Enrollment(
                archiveToken = archiveToken,
                deviceId = deviceId,
                destinationPublicKey = destinationKey,
                kid = kid,
                backfillDays = json.optInt("backfill_days", 0),
            ),
        )
    }

    // A 403 on the registration endpoints means either the capability is disabled
    // / the user has no usable membership (recoverable by an admin) or the OAuth
    // token did not come from the dedicated client (unauthorized_client).
    private fun classifyForbidden(body: String): Result {
        val error = runCatching { JSONObject(body).optString("error", "") }.getOrDefault("")
        return if (error == "unauthorized_client") Result.Unauthorized else Result.FeatureDisabled
    }

    private fun bearer(accessToken: String) = mapOf("Authorization" to "Bearer $accessToken")

    companion object {
        const val BEGIN_PATH = "/api/v1/android_archiver/device_registrations"

        // Synthetic Failed() code for a non-https backend origin (no HTTP request is
        // made). Negative so it never collides with a real HTTP status the server
        // could return; the caller maps any Failed() to a generic NETWORK error.
        private const val BAD_ORIGIN = -1

        fun completeUrl(backendOrigin: String, registrationId: String): String =
            backendOrigin.trimEnd('/') + BEGIN_PATH + "/" + registrationId + "/complete"
    }
}
