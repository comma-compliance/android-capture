package com.commacompliance.archiver.upload

import com.commacompliance.archiver.config.ConfigProvider
import com.commacompliance.archiver.crypto.EnvelopeEncryptor
import com.commacompliance.archiver.crypto.KeyManager
import com.commacompliance.archiver.data.CapturedEventDao
import com.commacompliance.archiver.enroll.EnrollmentManager
import android.util.Log
import com.commacompliance.archiver.net.HttpClient

/**
 * One upload pass: ensure enrolled, drain pending captured events in batches of at
 * most [BatchBuilder.MAX_BATCH_EVENTS], encrypt each batch into a `nacl-box-v1`
 * envelope, POST it bearer-authed, and mark the batch uploaded on HTTP 200.
 *
 * A 200 is treated as whole-batch success regardless of the {accepted, duplicates}
 * split - retries are idempotent server-side (the unique idempotency key collapses
 * re-sends to duplicates), so re-uploading after a lost ack never double-ingests.
 *
 * On HTTP 401 the archive_token has been revoked/rotated; the pass re-enrolls once
 * and retries. Transport failures propagate so WorkManager applies backoff.
 */
class Uploader(
    private val dao: CapturedEventDao,
    private val enrollmentManager: EnrollmentManager,
    private val keyManager: KeyManager,
    private val encryptor: EnvelopeEncryptor,
    private val http: HttpClient,
    private val configProvider: ConfigProvider,
    // Where to POST. Managed devices use the managed-config backend_url; a
    // self-serve device has none, so it falls back to the origin stored at
    // connect time. Returns null only when neither is available.
    private val backendUrlResolver: () -> String? = {
        configProvider.current().backendUrl
    },
    // Called after a batch is acknowledged so the in-app "last sync" time stays
    // current. No-op by default to keep the upload logic testable in isolation.
    private val onBatchUploaded: () -> Unit = {},
) {
    enum class Result {
        /** Nothing left to upload (or nothing to do); the pass is complete. */
        DONE,

        /** A transient problem (network, 5xx, not-yet-configured); retry with backoff. */
        RETRY,

        /** The managed enrollment_token is rejected; no point retrying until reprovisioned. */
        TOKEN_REJECTED,
    }

    // The pass is a small HTTP retry state machine: dispatch on the response code,
    // re-enroll at most once on 401, and return a distinct terminal disposition at
    // each decision point. Those early returns + branches read more clearly than
    // threading a result through nested blocks, so the complexity/return-count here
    // is inherent, not accidental.
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun runOnce(): Result {
        var enrollment = when (val outcome = enrollmentManager.ensureEnrolled()) {
            is EnrollmentManager.Outcome.Ready -> outcome.enrollment
            EnrollmentManager.Outcome.NotConfigured -> return Result.RETRY
            EnrollmentManager.Outcome.TokenRejected -> return Result.TOKEN_REJECTED
            EnrollmentManager.Outcome.Retry -> return Result.RETRY
        }

        val devicePub = keyManager.publicKey()
        val deviceSecret = keyManager.secretKey()

        // A single re-enroll is allowed per pass. A freshly-minted archive_token that
        // is STILL rejected means the server is rejecting our valid credential (clock
        // skew, account disabled, server bug); re-enrolling again would just hammer
        // enroll and burn the worker budget, so we bail to backoff instead.
        var reenrollUsed = false

        while (true) {
            val batch = dao.pendingUpload(BatchBuilder.MAX_BATCH_EVENTS)
            if (batch.isEmpty()) return Result.DONE

            val plaintext = BatchBuilder.build(enrollment.deviceId, batch)
            val envelope = encryptor.encrypt(
                plaintext = plaintext,
                kid = enrollment.kid,
                devicePublicKey = devicePub,
                deviceSecretKey = deviceSecret,
                destinationPublicKey = enrollment.destinationPublicKey,
            )

            // Read the backend URL fresh each batch: a managed-config change mid-pass
            // must not keep posting to a stale host. Managed devices resolve from
            // managed config; self-serve devices from the stored connect-time origin.
            val backendUrl = backendUrlResolver() ?: return Result.RETRY

            val response = http.postJson(
                url = uploadEndpoint(backendUrl),
                body = envelope.toString(),
                headers = mapOf("Authorization" to "Bearer ${enrollment.archiveToken}"),
            )

            when (response.code) {
                200 -> {
                    dao.markUploaded(batch.map { it.id })
                    onBatchUploaded()
                }
                401 -> {
                    // Archive token revoked/rotated. Re-enroll at most once per pass; a
                    // second 401 after a fresh token means retrying now is futile, so
                    // back off and let WorkManager reschedule.
                    if (reenrollUsed) return Result.RETRY
                    reenrollUsed = true
                    enrollment = when (val redo = enrollmentManager.ensureEnrolled(forceReenroll = true)) {
                        is EnrollmentManager.Outcome.Ready -> redo.enrollment
                        EnrollmentManager.Outcome.TokenRejected -> return Result.TOKEN_REJECTED
                        else -> return Result.RETRY
                    }
                    // Loop again WITHOUT marking uploaded; the same batch re-sends under
                    // the new token (idempotent server-side).
                }
                in 500..599, 408, 429 -> return Result.RETRY
                else -> {
                    // A non-transient 4xx (e.g. 400 invalid_batch, 403, 404) will not
                    // heal on retry. Surface it loudly and back off rather than spin
                    // the same doomed batch; a contract/config bug needs a human, and
                    // WorkManager's capped backoff keeps a stuck batch from hammering.
                    Log.e(TAG, "upload rejected with non-retryable status ${response.code}; backing off")
                    return Result.RETRY
                }
            }
        }
    }

    companion object {
        private const val TAG = "Uploader"
        const val UPLOAD_PATH = "/webhooks/incoming/android_messages_webhooks"

        fun uploadEndpoint(backendUrl: String): String =
            backendUrl.trimEnd('/') + UPLOAD_PATH
    }
}
