package com.commacompliance.archiver.upload

import android.util.Log
import com.commacompliance.archiver.capture.Attachment
import com.commacompliance.archiver.data.CapturedEvent
import com.commacompliance.archiver.data.CapturedEventDao
import com.commacompliance.archiver.data.PendingAttachment
import com.commacompliance.archiver.data.PendingAttachmentDao
import io.sentry.Sentry
import io.sentry.SentryLevel
import org.json.JSONException
import org.json.JSONObject

/**
 * Out-of-band attachment preparation. Runs OFF the capture window (capture only
 * persists the event + flags it not-ready when it carries attachments): for each
 * captured event awaiting attachment hashing, this streams every attachment's
 * `part_uri` to compute the PLAINTEXT-bytes SHA-256, records a durable
 * `PendingAttachment`, patches the computed `content_hash` (+ `status:"pending"`)
 * into the event's stored payload, then flips the event ready to upload.
 *
 * Why patch the event payload: the server links uploaded attachment bytes to the
 * canonical message by `content_hash`, so the message event's
 * `attachments[].content_hash` MUST equal the hash of the blob that gets uploaded.
 * Computing it here (not at capture) keeps hashing - which streams the bytes - out
 * of the time-boxed capture window, and gating upload on readiness guarantees the
 * event never drains before its hashes exist.
 *
 * No-drop / resumable: hashing reads bytes only (never deletes); a part that has
 * vanished by the time we hash leaves the event NOT ready, so it is retried on the
 * next pass rather than dropped or uploaded incomplete. A re-run over an
 * already-prepared event is a no-op (it is no longer in the not-ready set).
 *
 * Completeness: an MMS/RCS part can be READABLE while its bytes are still arriving,
 * and streaming it to EOF then yields a hash of a partial file. There is no
 * "expected final size" anywhere in the Telephony provider to compare against - the
 * part row's descriptor length only reports what is on disk right now - so
 * completeness can only be established by QUIESCENCE: the bytes have not changed
 * across two observations separated in time. [isSettled] applies that rule, and an
 * attachment that has not settled leaves its event not-ready rather than archiving
 * a truncated file as final.
 */
class AttachmentEnqueuer(
    private val eventDao: CapturedEventDao,
    private val pendingDao: PendingAttachmentDao,
    private val source: AttachmentSource,
    private val batchLimit: Int = 200,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Prepare every event currently awaiting attachment hashing. Returns true when
     * at least one attachment was held back awaiting a settle re-check, so the
     * caller can schedule that re-check rather than waiting for ambient traffic.
     */
    fun runOnce(): Boolean {
        val events = eventDao.pendingAttachmentHashing(batchLimit)
        var deferred = false
        for (event in events) {
            try {
                if (prepare(event)) deferred = true
            } catch (e: JSONException) {
                // A payload we wrote should always parse; if it somehow does not,
                // do NOT wedge the queue - mark ready so it drains as-is (its
                // attachments simply stay pending server-side). Never drop.
                // Log the exception CLASS and the local row id only: a JSONException
                // message can quote the offending payload fragment, which is a
                // message body, so the Throwable itself must never reach the log.
                Log.w(TAG, "unparseable payload (${e.javaClass.simpleName}) for event ${event.id}; releasing for upload")
                eventDao.updatePayload(event.id, event.rawJson, readyForUpload = true)
            }
        }
        return deferred
    }

    /** Returns true when this event was held back awaiting a settle re-check. */
    private fun prepare(event: CapturedEvent): Boolean {
        val payload = JSONObject(event.rawJson)
        val attachments = payload.optJSONArray("attachments")
        if (attachments == null || attachments.length() == 0) {
            // No attachments after all - nothing to hash; release immediately.
            eventDao.updatePayload(event.id, event.rawJson, readyForUpload = true)
            return false
        }

        var allHashed = true
        var deferred = false
        val kept = org.json.JSONArray()
        for (i in 0 until attachments.length()) {
            val att = attachments.getJSONObject(i)
            // Already hashed on a prior partial pass - keep it verbatim.
            if (!att.optString("content_hash").isNullOrBlank()) {
                kept.put(att)
                continue
            }

            val partUri = att.optString("part_uri")
            if (partUri.isBlank()) {
                // No reference to stream - this attachment is structurally
                // unuploadable (no bytes, no content_hash). Drop it from the payload
                // rather than emit hashless metadata the server could never link.
                // Our own capture always sets part_uri for parts with data, so this
                // is a defensive degenerate case, not the normal path.
                Log.w(TAG, "attachment with no part_uri on event ${event.id}; dropping unuploadable entry")
                continue
            }

            // Never drop: an attachment that could not be read, or whose bytes have
            // not settled, is kept in the payload and simply leaves the event
            // not-ready so a later pass retries it.
            kept.put(att)
            when (hashAndRecord(att, partUri, event)) {
                Verdict.RECORDED -> Unit
                Verdict.UNREADABLE -> allHashed = false
                Verdict.UNSETTLED -> {
                    allHashed = false
                    deferred = true
                }
            }
        }

        payload.put("attachments", kept)
        if (allHashed) {
            eventDao.updatePayload(event.id, payload.toString(), readyForUpload = true)
        } else {
            // Persist WITHOUT releasing: this carries forward both the hashes already
            // computed (so their bytes are not re-streamed) and the settle
            // observation the next pass compares against. Still ready_for_upload = 0.
            eventDao.updatePayload(event.id, payload.toString(), readyForUpload = false)
        }
        return deferred
    }

    private enum class Verdict {
        /** Hashed, recorded as a pending blob, and patched into the payload. */
        RECORDED,

        /** Bytes could not be read right now; retry on a later pass. */
        UNREADABLE,

        /** Bytes read but not yet proven complete; re-check after the settle interval. */
        UNSETTLED,
    }

    @Suppress("TooGenericExceptionCaught") // Opening/reading an arbitrary content:// URI can throw
    // any of IOException/SecurityException/IllegalStateException/UnsupportedOperationException;
    // a broad catch is correct here - the attachment simply stays not-ready and is retried next pass.
    private fun hashAndRecord(att: JSONObject, partUri: String, event: CapturedEvent): Verdict {
        val result = try {
            source.open(partUri)?.use { AttachmentFrames.hashStream(it) }
        } catch (e: Exception) {
            Log.w(TAG, "failed to read attachment for hashing: ${e.javaClass.simpleName}")
            null
        } ?: return Verdict.UNREADABLE

        if (!isSettled(att, result, event)) return Verdict.UNSETTLED

        // Settled: the private observation has served its purpose and must not travel
        // on the wire. Removing it here is what keeps it off - content_hash is set
        // only on this path, and an event is only released once every attachment has
        // one. (The single exception is the unparseable-payload fallback in
        // [runOnce], which releases the stored bytes verbatim precisely because it
        // could not parse them; an extra key there is ignored server-side.)
        att.remove(SETTLE_KEY)

        val mime = att.optString("content_type").ifBlank { DEFAULT_MIME }
        pendingDao.insert(
            PendingAttachment(
                contentHash = result.contentHash,
                partUri = partUri,
                mime = mime,
                sizeBytes = result.sizeBytes,
            ),
        )
        att.put("content_hash", result.contentHash)
        att.put("status", Attachment.STATUS_PENDING)
        // Backfill the authoritative streamed size so the event metadata matches
        // the bytes that will be uploaded, even when the provider's descriptor
        // length was null or stale at capture time.
        att.put("size_bytes", result.sizeBytes)
        return Verdict.RECORDED
    }

    /**
     * Whether the streamed bytes can be treated as the attachment's final content.
     *
     * Fast path: the descriptor length recorded at CAPTURE is itself an earlier
     * observation, taken when the message was first seen. If the bytes we just
     * streamed match it AND capture was at least [SETTLE_INTERVAL_MS] ago, the file
     * has held still across a real span of time and the attachment is released on
     * this pass - no extra stream read, no extra worker pass. That is the ordinary
     * case for anything that has waited in the queue (an offline device, a backlog
     * draining). The age gate is what makes it evidence: matching lengths taken
     * moments apart say nothing, because a part can be readable and still growing,
     * and a download that happens to be between writes would look identical to one
     * that had finished.
     *
     * Slow path (descriptor was null at capture, the size has moved since, or
     * capture was too recent to prove anything): take our own observation, hold the
     * event back, and require the SAME content hash on a later pass at least
     * [SETTLE_INTERVAL_MS] afterwards. A file still being written fails that; a file
     * that has stopped changing passes it. Re-checks inside the interval neither
     * settle the attachment nor consume an observation, so a burst of passes cannot
     * rush the verdict.
     *
     * Escape hatch: bytes that keep changing for [MAX_SETTLE_WAIT_MS] from the first
     * sighting are accepted as read, logged, and reported to Sentry. Holding out
     * forever is the worse failure - the whole EVENT is held, so the message record
     * itself would never be archived either, not just its media. The bound is on
     * elapsed time rather than a count of observations so that a large file arriving
     * slowly over a poor link still wins; only bytes that are genuinely never going
     * to settle hit it, and when they do it is reported rather than silent.
     */
    private fun isSettled(att: JSONObject, result: AttachmentFrames.HashResult, event: CapturedEvent): Boolean {
        val now = clock()
        val sizeHeldSinceCapture = att.optLong("size_bytes", SIZE_UNKNOWN) == result.sizeBytes
        if (sizeHeldSinceCapture && now - event.createdAt >= SETTLE_INTERVAL_MS) return true

        val prior = att.optJSONObject(SETTLE_KEY)

        if (prior != null && prior.optString("hash") == result.contentHash) {
            // Same bytes as last time. Settled once enough time has passed; otherwise
            // just wait - the marker is left untouched so the interval accrues from
            // the FIRST sighting of these bytes rather than restarting on every
            // re-read, and an identical re-read never spends an observation.
            return now - prior.optLong("at") >= SETTLE_INTERVAL_MS
        }

        // The bytes changed (or this is the first sighting): still in flux.
        val firstSeen = prior?.optLong("first_at") ?: now
        val waited = now - firstSeen
        if (waited >= MAX_SETTLE_WAIT_MS) {
            // Report, don't hide. Metadata only - an event id and a byte count; no
            // body, filename, or address may reach Sentry.
            Log.w(TAG, "attachment bytes never settled in ${waited}ms on event ${event.id}; archiving as read")
            Sentry.captureMessage(
                "Attachment bytes never settled; archived as read after ${waited / MILLIS_PER_MINUTE}m " +
                    "(event=${event.id}, size=${result.sizeBytes})",
                SentryLevel.WARNING,
            )
            return true
        }
        att.put(
            SETTLE_KEY,
            JSONObject()
                .put("hash", result.contentHash)
                .put("at", now)
                .put("first_at", firstSeen),
        )
        return false
    }

    companion object {
        private const val TAG = "AttachmentEnqueuer"
        private const val DEFAULT_MIME = "application/octet-stream"

        /**
         * Private, never-on-the-wire settle observation stored on an attachment in
         * the event payload. Underscore-prefixed to mark it as ours rather than part
         * of the frozen attachment wire shape; stripped before the event is released.
         */
        private const val SETTLE_KEY = "_settle"

        /** A size no real attachment can report, so "absent" never matches a real length. */
        private const val SIZE_UNKNOWN = -1L

        /**
         * Minimum gap between the two identical reads that prove an attachment has
         * stopped changing. Long enough that an in-progress download visibly grows
         * between them, short enough that the added archive latency is seconds.
         */
        const val SETTLE_INTERVAL_MS = 30_000L

        /**
         * How long bytes may keep changing before the readable ones are accepted and
         * reported. Generous on purpose: a large file arriving slowly over a poor
         * link is a normal download, not a stuck one, and must not be cut off. Only
         * bytes that are never going to settle reach this bound.
         */
        const val MAX_SETTLE_WAIT_MS = 30 * 60 * 1000L

        private const val MILLIS_PER_MINUTE = 60_000L
    }
}
