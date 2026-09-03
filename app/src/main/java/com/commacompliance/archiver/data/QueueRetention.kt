package com.commacompliance.archiver.data

import android.util.Log

/**
 * Bounded post-upload retention for the on-device queue.
 *
 * The durable queue keeps a row until its bytes reach the server, but historically
 * it then kept the uploaded row FOREVER. A captured event carries the message body
 * in `raw_json`, so retaining uploaded events indefinitely leaves cleartext message
 * content on the device long after it has served its purpose. This prunes uploaded
 * rows once they age past [RETENTION_MS], bounding at-rest exposure to a fixed
 * window while never touching anything still awaiting upload.
 *
 * Two invariants keep this safe (enforced in the DAO query):
 *  - Server idempotency `(provider_id, event_type, event_ts, body_hash)`: re-emission
 *    of an unchanged provider row is suppressed by the separate `telephony_snapshot`
 *    cache - the capture pass is a snapshot-diff against that cache, NOT a re-scan
 *    that reinserts into `captured_events` - so a pruned row cannot be re-captured.
 *  - Edit correlation: an edit is a new provider row that the classifier correlates
 *    against durable `captured_events` history. So the captured-event prune only
 *    removes rows whose `provider_id` is NO LONGER in `telephony_snapshot` (the
 *    message is gone from the provider and can never produce a future edit needing
 *    that row). The snapshot cache itself is deliberately NOT pruned here; it is the
 *    dedup + correlation-liveness source of truth, bounded by the provider's own size.
 */
object QueueRetention {

    private const val TAG = "QueueRetention"

    /** Retain uploaded rows for 7 days past capture, then prune. */
    const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Prune uploaded captured-event and completed pending-attachment rows older than
     * [RETENTION_MS] before [now]. Returns the total rows removed. Safe to call on
     * every reconcile/heartbeat pass: it only ever removes already-uploaded rows past
     * the window, so a no-op pass deletes nothing.
     */
    fun prune(db: ArchiverDatabase, now: Long = System.currentTimeMillis()): Int {
        val cutoff = now - RETENTION_MS
        val events = db.capturedEventDao().pruneUploadedOlderThan(cutoff)
        val attachments = db.pendingAttachmentDao().pruneUploadedOlderThan(cutoff)
        val total = events + attachments
        if (total > 0) {
            Log.d(TAG, "pruned $events uploaded events + $attachments completed attachments past retention")
        }
        return total
    }
}
