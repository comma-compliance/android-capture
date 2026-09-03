package com.commacompliance.archiver.upload

import android.util.Log
import com.commacompliance.archiver.crypto.EnvelopeEncryptor
import com.commacompliance.archiver.crypto.KeyManager
import com.commacompliance.archiver.data.PendingAttachment
import com.commacompliance.archiver.data.PendingAttachmentDao
import com.commacompliance.archiver.enroll.EnrollmentManager
import com.commacompliance.archiver.enroll.EnrollmentStore
import com.commacompliance.archiver.net.HttpClient
import java.io.InputStream

/**
 * One attachment-upload pass. Drains the durable pending-attachment store: for
 * each not-yet-uploaded blob it streams the bytes from the `part_uri`, encrypts a
 * domain-separated frame to the destination key, and POSTs it bearer-authed to the
 * attachment endpoint. Small files (<= the server's single-shot ceiling) go in one
 * frame; larger files stream as chunks carrying the whole-file content_hash so the
 * file is NEVER fully buffered in memory.
 *
 * Out-of-band by design: this runs in its OWN WorkManager job, never inside the
 * capture window and never blocking the event-batch drain. Resumable: a chunk
 * re-send is a server no-op, so a killed/retried pass simply re-sends and the
 * server dedups by content_hash. The blob is marked uploaded only after the
 * terminal 200 (single-shot success or final-chunk completion).
 *
 * A 401 means the archive_token rotated; like the event uploader, this re-enrolls
 * once per pass and retries. Transport failures propagate so WorkManager backs off.
 */
class AttachmentUploader(
    private val dao: PendingAttachmentDao,
    private val enrollmentManager: EnrollmentManager,
    private val keyManager: KeyManager,
    private val encryptor: EnvelopeEncryptor,
    private val http: HttpClient,
    private val source: AttachmentSource,
    private val backendUrlResolver: () -> String?,
    private val onUploaded: () -> Unit = {},
    private val batchLimit: Int = 50,
) {
    enum class Result { DONE, RETRY, TOKEN_REJECTED }

    // Like the event Uploader, the pass is a cohesive retry state machine: drain the
    // pending blobs, dispatch on each upload outcome, re-enroll at most once on a
    // rotated token, and return a distinct terminal disposition at each point. The
    // early returns + per-outcome branches are clearer than threading a result
    // through deeper nesting, so the complexity/return-count/nesting here is inherent.
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "NestedBlockDepth")
    fun runOnce(): Result {
        var enrollment = when (val outcome = enrollmentManager.ensureEnrolled()) {
            is EnrollmentManager.Outcome.Ready -> outcome.enrollment
            EnrollmentManager.Outcome.NotConfigured -> return Result.RETRY
            EnrollmentManager.Outcome.TokenRejected -> return Result.TOKEN_REJECTED
            EnrollmentManager.Outcome.Retry -> return Result.RETRY
        }

        val devicePub = keyManager.publicKey()
        val deviceSecret = keyManager.secretKey()
        var reenrollUsed = false

        while (true) {
            val pending = dao.pendingUploads(batchLimit)
            if (pending.isEmpty()) return Result.DONE

            for (attachment in pending) {
                // A different part_uri pointing at identical bytes may have already
                // uploaded this content_hash; the server would dedup it anyway, but
                // skip the work and mark this row uploaded too.
                if (dao.isUploaded(attachment.contentHash)) {
                    dao.markUploadedByHash(attachment.contentHash)
                    continue
                }

                val backendUrl = backendUrlResolver() ?: return Result.RETRY
                val endpoint = uploadEndpoint(backendUrl)

                val outcome = uploadOne(attachment, endpoint, enrollment, devicePub, deviceSecret)
                when (outcome) {
                    UploadOutcome.SUCCESS -> markUploaded(attachment)
                    UploadOutcome.MISSING_SOURCE -> {
                        // The part vanished before we could stream it. Do NOT drop:
                        // leave it pending and retry on the next pass. Back off so a
                        // permanently-gone part does not spin.
                        Log.w(TAG, "attachment source missing; retaining pending and backing off")
                        return Result.RETRY
                    }
                    UploadOutcome.RETRY -> return Result.RETRY
                    UploadOutcome.TOKEN_REJECTED -> {
                        // The archive_token rotated/revoked. Re-enroll at most once per
                        // pass (a bounded flag, NOT recursion), then retry the SAME
                        // attachment under the fresh token (a re-send is idempotent).
                        if (reenrollUsed) return Result.RETRY
                        reenrollUsed = true
                        enrollment = when (val redo = enrollmentManager.ensureEnrolled(forceReenroll = true)) {
                            is EnrollmentManager.Outcome.Ready -> redo.enrollment
                            EnrollmentManager.Outcome.TokenRejected -> return Result.TOKEN_REJECTED
                            else -> return Result.RETRY
                        }
                        // A second non-success after a fresh token means retrying now
                        // is futile; back off.
                        if (uploadOne(attachment, endpoint, enrollment, devicePub, deviceSecret) !=
                            UploadOutcome.SUCCESS
                        ) {
                            return Result.RETRY
                        }
                        markUploaded(attachment)
                    }
                }
            }
        }
    }

    private fun markUploaded(attachment: PendingAttachment) {
        dao.markUploadedByHash(attachment.contentHash)
        onUploaded()
    }

    private enum class UploadOutcome { SUCCESS, RETRY, TOKEN_REJECTED, MISSING_SOURCE }

    private fun uploadOne(
        attachment: PendingAttachment,
        endpoint: String,
        enrollment: EnrollmentStore.Enrollment,
        devicePub: ByteArray,
        deviceSecret: ByteArray,
    ): UploadOutcome {
        return if (attachment.sizeBytes <= AttachmentFrames.SINGLE_SHOT_MAX_BYTES) {
            uploadSingleShot(attachment, endpoint, enrollment, devicePub, deviceSecret)
        } else {
            uploadChunked(attachment, endpoint, enrollment, devicePub, deviceSecret)
        }
    }

    private fun uploadSingleShot(
        attachment: PendingAttachment,
        endpoint: String,
        enrollment: EnrollmentStore.Enrollment,
        devicePub: ByteArray,
        deviceSecret: ByteArray,
    ): UploadOutcome {
        val bytes = source.open(attachment.partUri)?.use { it.readBytes() } ?: return UploadOutcome.MISSING_SOURCE
        val frame = AttachmentFrames.singleShotFrame(
            contentHash = attachment.contentHash,
            mime = attachment.mime,
            sizeBytes = bytes.size.toLong(),
            bytes = bytes,
        )
        return post(frame, endpoint, enrollment, devicePub, deviceSecret)
    }

    private fun uploadChunked(
        attachment: PendingAttachment,
        endpoint: String,
        enrollment: EnrollmentStore.Enrollment,
        devicePub: ByteArray,
        deviceSecret: ByteArray,
    ): UploadOutcome {
        val chunkCount = AttachmentFrames.chunkCount(attachment.sizeBytes)
        val stream = source.open(attachment.partUri) ?: return UploadOutcome.MISSING_SOURCE
        stream.use { input ->
            val buffer = ByteArray(AttachmentFrames.CHUNK_PLAINTEXT_BYTES)
            var index = 0
            while (index < chunkCount) {
                val filled = readFully(input, buffer)
                if (filled <= 0 && index < chunkCount) {
                    // The source ended early vs the recorded size: it changed or was
                    // truncated. Retain pending and retry (a re-stream re-reads from
                    // the start); never upload a partial file as complete.
                    Log.w(TAG, "attachment stream ended early at chunk $index; retrying")
                    return UploadOutcome.RETRY
                }
                val frame = AttachmentFrames.chunkFrame(
                    contentHash = attachment.contentHash,
                    chunkIndex = index,
                    chunkCount = chunkCount,
                    mime = attachment.mime,
                    sizeBytes = attachment.sizeBytes,
                    chunkBytes = buffer,
                    chunkLength = filled,
                )
                val outcome = post(frame, endpoint, enrollment, devicePub, deviceSecret)
                if (outcome != UploadOutcome.SUCCESS) return outcome
                index++
            }
        }
        // Every chunk acked 200; the final one completed the server-side reassembly
        // and store, so the blob is durably canonical.
        return UploadOutcome.SUCCESS
    }

    private fun post(
        frame: String,
        endpoint: String,
        enrollment: EnrollmentStore.Enrollment,
        devicePub: ByteArray,
        deviceSecret: ByteArray,
    ): UploadOutcome {
        val envelope = encryptor.encrypt(
            plaintext = frame,
            kid = enrollment.kid,
            devicePublicKey = devicePub,
            deviceSecretKey = deviceSecret,
            destinationPublicKey = enrollment.destinationPublicKey,
        )
        val response = http.postJson(
            url = endpoint,
            body = envelope.toString(),
            headers = mapOf("Authorization" to "Bearer ${enrollment.archiveToken}"),
        )
        return when (response.code) {
            200 -> UploadOutcome.SUCCESS
            401 -> UploadOutcome.TOKEN_REJECTED
            in 500..599, 408, 429 -> UploadOutcome.RETRY
            else -> {
                // A non-transient 4xx (e.g. a contract/schema bug) will not heal on
                // retry. Back off rather than spin; never log the body or token.
                Log.e(TAG, "attachment upload rejected status ${response.code}; backing off")
                UploadOutcome.RETRY
            }
        }
    }

    // Read up to buffer.size bytes, looping until the buffer is full or the stream
    // ends, so one short read does not produce an undersized chunk mid-file.
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var off = 0
        while (off < buffer.size) {
            val n = input.read(buffer, off, buffer.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    companion object {
        private const val TAG = "AttachmentUploader"
        const val UPLOAD_PATH = "/webhooks/incoming/android_messages_attachments"

        fun uploadEndpoint(backendUrl: String): String =
            backendUrl.trimEnd('/') + UPLOAD_PATH
    }
}
