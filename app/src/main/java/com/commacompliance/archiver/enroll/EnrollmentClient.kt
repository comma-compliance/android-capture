package com.commacompliance.archiver.enroll

import com.commacompliance.archiver.crypto.KeyManager
import com.commacompliance.archiver.crypto.NaclBox
import com.commacompliance.archiver.integrity.IntegrityTokenSource
import com.commacompliance.archiver.integrity.RequestHash
import com.commacompliance.archiver.net.HttpClient
import com.commacompliance.archiver.util.anyBlank
import org.json.JSONObject
import java.util.Base64

/**
 * Performs the managed/EMM device-enrollment handshake in two signed steps:
 *
 *   1. begin:    POST {backend}/api/v1/android_archiver/enroll/begin
 *                body { enrollment_token, device_pubkey, signing_public_key,
 *                       device_pubkey_fingerprint, enrollment_specific_id, device_label }
 *                -> { challenge_id, challenge(b64), expires_at }
 *
 *   2. complete: POST {backend}/api/v1/android_archiver/enroll/complete
 *                body { challenge_id, signature(b64), integrity_token? } where
 *                signature = Ed25519 detached sig over the RAW decoded challenge
 *                bytes, made with the signing key bound at begin
 *                -> { archive_token, device_id, destination_public_key, kid, backfill_days }
 *
 * Both requests are unauthenticated by design: the EMM-provisioned `enrollment_token`
 * IS the bootstrap credential. The device additionally proves possession of its
 * Ed25519 signing key by signing the server's challenge, so knowing a device's
 * (public) box fingerprint is not enough to enroll as it - only the holder of the
 * original signing key can. This mirrors the self-serve OAuth RegistrationClient,
 * minus the bearer auth (here the managed token is the credential).
 *
 * The device keypairs are stable (generated once, never rotated), so a re-enroll
 * presents the SAME box fingerprint + signing key and the server reissues
 * idempotently - the returning-device signing-key check passes and no new device is
 * minted.
 */
open class EnrollmentClient(
    private val http: HttpClient,
    private val keyManager: KeyManager,
    // Attests the device for the complete step. Optional: a null source (or a null
    // cloudProjectNumber, or a source that returns null because Play services are
    // absent) simply omits integrity_token - enroll still completes, and the server
    // policy decides whether that is acceptable.
    private val integrityTokenSource: IntegrityTokenSource? = null,
) {
    sealed interface Result {
        data class Success(val enrollment: EnrollmentStore.Enrollment) : Result

        /**
         * 401 on the BEGIN step: the enrollment_token itself is invalid, expired, or
         * revoked. The bootstrap credential is dead, so this is terminal - only an
         * admin reprovisioning a fresh managed token can recover.
         */
        data object InvalidToken : Result

        /**
         * 401 on the COMPLETE step: begin already validated the enrollment_token, so a
         * 401 here is the server's `invalid_token` for the CHALLENGE - it expired, was
         * consumed, or is unknown between the two steps. Transient: the device recovers
         * by re-running the begin->complete handshake, NEVER by clearing a still-valid
         * stored enrollment.
         */
        data object ChallengeExpired : Result

        /** 409 fingerprint_mismatch: a consumed single-use token re-presented by a different device. */
        data object FingerprintMismatch : Result

        /** 409 signing_key_mismatch: a returning device presented a different signing key than it first enrolled with. */
        data object SigningKeyMismatch : Result

        /** 422: the challenge signature did not verify against the signing key bound at begin. */
        data object InvalidSignature : Result

        /**
         * 403: a server policy refusal (capture disabled, attestation required/failed,
         * or the reusable-token device cap reached). Carries the machine reason for
         * diagnostics; the device cannot enroll until the underlying condition changes.
         */
        data class Forbidden(val reason: String) : Result

        /**
         * Any other non-2xx (including 429 rate_limited) or a malformed 2xx body;
         * carries the status for diagnostics (never the body, which may echo input).
         */
        data class Failed(val code: Int) : Result
    }

    open fun enroll(
        backendUrl: String,
        enrollmentToken: String,
        enrollmentSpecificId: String?,
        deviceLabel: String?,
        // Google Cloud project number from discovery; null when the server has not
        // configured Play Integrity, in which case no attestation token is fetched.
        cloudProjectNumber: Long? = null,
    ): Result {
        val begin = beginEnroll(backendUrl, enrollmentToken, enrollmentSpecificId, deviceLabel)
        val beginJson = when (begin) {
            is StepResult.Ok -> begin.json
            is StepResult.Err -> return begin.result
        }

        val challengeId = beginJson.optString("challenge_id", "")
        val challengeB64 = beginJson.optString("challenge", "")
        // Fail closed on any unusable begin (201) body rather than send a complete the
        // server can never match (missing/short/non-decodable challenge).
        val challenge = decodeChallenge(challengeId, challengeB64) ?: return Result.Failed(MALFORMED_BEGIN)

        // The integrity requestHash binds the attestation to the challenge STRING as
        // received (the base64 text), NOT its decoded bytes - matching the server,
        // which hashes the same string it emitted. Fetch (best effort) only when the
        // server advertised a cloud project number.
        val integrityToken = acquireIntegrityToken(cloudProjectNumber, challengeB64)

        val signature = keyManager.sign(challenge)
        // Ed25519 detached signatures are exactly 64 bytes; refuse to send anything
        // else (a corrupt signing path) rather than hand the server a 422.
        if (signature.size != NaclBox.SIGNATURE_BYTES) return Result.Failed(MALFORMED_BEGIN)

        return completeEnroll(backendUrl, challengeId, signature, integrityToken)
    }

    // Decode + validate the begin response's challenge: present, base64-decodable, and
    // at least the contracted minimum of random bytes. Null signals an unusable begin
    // body the caller must fail closed on (never sign a malformed/hostile challenge).
    private fun decodeChallenge(challengeId: String, challengeB64: String): ByteArray? {
        if (challengeId.isBlank() || challengeB64.isBlank()) return null
        val challenge = runCatching { Base64.getDecoder().decode(challengeB64) }.getOrNull() ?: return null
        return if (challenge.size >= CHALLENGE_MIN_BYTES) challenge else null
    }

    // Best-effort attestation: returns null (and the complete payload omits
    // integrity_token) whenever integrity is not configured, no source is wired, or
    // Play services cannot produce a token. Never blocks or fails enroll.
    private fun acquireIntegrityToken(cloudProjectNumber: Long?, challengeB64: String): String? {
        if (cloudProjectNumber == null) return null
        val source = integrityTokenSource ?: return null
        return source.fetch(cloudProjectNumber, RequestHash.forChallenge(challengeB64))
    }

    private sealed interface StepResult {
        data class Ok(val json: JSONObject) : StepResult
        data class Err(val result: Result) : StepResult
    }

    private fun beginEnroll(
        backendUrl: String,
        enrollmentToken: String,
        enrollmentSpecificId: String?,
        deviceLabel: String?,
    ): StepResult {
        val request = JSONObject().apply {
            put("enrollment_token", enrollmentToken)
            put("device_pubkey", keyManager.publicKeyBase64())
            put("signing_public_key", keyManager.signingPublicKeyBase64())
            put("device_pubkey_fingerprint", keyManager.fingerprint())
            put("enrollment_specific_id", enrollmentSpecificId ?: JSONObject.NULL)
            put("device_label", deviceLabel ?: JSONObject.NULL)
        }

        val response = http.postJson(url = beginUrl(backendUrl), body = request.toString())

        return when (response.code) {
            in 200..299 -> {
                val json = runCatching { JSONObject(response.body) }.getOrNull()
                    ?: return StepResult.Err(Result.Failed(response.code))
                StepResult.Ok(json)
            }
            else -> StepResult.Err(mapError(response.code, response.body, Stage.BEGIN))
        }
    }

    private fun completeEnroll(
        backendUrl: String,
        challengeId: String,
        signature: ByteArray,
        integrityToken: String?,
    ): Result {
        val request = JSONObject().apply {
            put("challenge_id", challengeId)
            put("signature", Base64.getEncoder().encodeToString(signature))
            // Optional per contract; omitted entirely when no token was produced so an
            // integrity-unconfigured request body is unchanged.
            if (integrityToken != null) put("integrity_token", integrityToken)
        }

        val response = http.postJson(url = completeUrl(backendUrl), body = request.toString())

        return when (response.code) {
            in 200..299 -> parseProvision(response.body)
            else -> mapError(response.code, response.body, Stage.COMPLETE)
        }
    }

    // A 200 with a malformed body (not JSON, or a destination key that is not valid
    // base64 / not a 32-byte key) is Failed, never a crash: the body is
    // attacker-influenceable on a hostile/typoed backend and must not be trusted to
    // parse. The decode is delegated to EnrollmentStore so the enroll and
    // registration paths reject a non-conforming key identically.
    private fun parseProvision(body: String): Result {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return Result.Failed(PROVISION_CODE)
        val archiveToken = json.optString("archive_token", "")
        val deviceId = json.optString("device_id", "")
        val destB64 = json.optString("destination_public_key", "")
        val kid = json.optString("kid", "")
        if (anyBlank(archiveToken, deviceId, destB64, kid)) {
            return Result.Failed(PROVISION_CODE)
        }
        val destinationKey = EnrollmentStore.decodeDestinationKey(destB64) ?: return Result.Failed(PROVISION_CODE)
        return Result.Success(
            EnrollmentStore.Enrollment(
                archiveToken = archiveToken,
                deviceId = deviceId,
                destinationPublicKey = destinationKey,
                kid = kid,
                // Absent on older server responses; default to forward-only.
                backfillDays = json.optInt("backfill_days", 0),
            ),
        )
    }

    // Which handshake step produced a non-2xx response. Only the meaning of a 401
    // differs between the two; every other status maps identically, so both steps
    // still route through one mapper and cannot drift.
    private enum class Stage { BEGIN, COMPLETE }

    // Map a non-2xx status to a typed Result. A 401 is stage-aware: on BEGIN the
    // enrollment_token is bad (terminal InvalidToken); on COMPLETE the token was
    // already accepted at begin, so the server's `invalid_token` is the CHALLENGE
    // having expired/been consumed between steps (transient ChallengeExpired) - the
    // device must re-run the handshake, not wipe a still-valid enrollment.
    private fun mapError(code: Int, body: String, stage: Stage): Result = when (code) {
        401 -> when (stage) {
            Stage.BEGIN -> Result.InvalidToken
            Stage.COMPLETE -> Result.ChallengeExpired
        }
        409 -> mapConflict(body)
        422 -> Result.InvalidSignature
        403 -> Result.Forbidden(errorReason(body))
        else -> Result.Failed(code)
    }

    // A 409 is disambiguated by the server's machine error so a returning-device key
    // mismatch (a possible re-key / impersonation) stays distinct from a single-use
    // token consumed by another device. ONLY those two known terminal reasons clear
    // the device's credentials downstream; an unexpected or body-less 409 (a proxy or
    // backend glitch) is treated as transient so a still-valid enrollment is never
    // wiped on a fluke.
    private fun mapConflict(body: String): Result = when (errorReason(body)) {
        "signing_key_mismatch" -> Result.SigningKeyMismatch
        "fingerprint_mismatch" -> Result.FingerprintMismatch
        else -> Result.Failed(409)
    }

    private fun errorReason(body: String): String =
        runCatching { JSONObject(body).optString("error", "") }.getOrDefault("")

    companion object {
        const val BEGIN_PATH = "/api/v1/android_archiver/enroll/begin"
        const val COMPLETE_PATH = "/api/v1/android_archiver/enroll/complete"

        // The server is contracted to emit at least this many random challenge bytes.
        private const val CHALLENGE_MIN_BYTES = 32

        // Diagnostic Failed() codes for an unusable begin (201) body and a malformed
        // complete (200) body, so the surfaced status stays honest to the step that
        // produced it.
        private const val MALFORMED_BEGIN = 201
        private const val PROVISION_CODE = 200

        fun beginUrl(backendUrl: String): String = backendUrl.trimEnd('/') + BEGIN_PATH

        fun completeUrl(backendUrl: String): String = backendUrl.trimEnd('/') + COMPLETE_PATH
    }
}
