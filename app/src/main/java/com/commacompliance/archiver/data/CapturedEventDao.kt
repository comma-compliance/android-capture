package com.commacompliance.archiver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CapturedEventDao {

    /**
     * Inserts a classified event. Conflicts on the server-mirrored idempotency key
     * are ignored, making re-reads of an unchanged row a no-op. Returns the new
     * rowid, or -1 when the row already existed (the conflict was ignored).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(event: CapturedEvent): Long

    /**
     * Prior captures in a thread from a given sender at a given timestamp. The
     * edit-correlation rule keys on durable history (already-captured rows), not
     * merely the live provider snapshot: an edit is a NEW provider row that inherits
     * the original's (thread, sender, timestamp) but carries a different body_hash.
     */
    @Query(
        "SELECT * FROM captured_events " +
            "WHERE thread_id = :threadId AND event_ts = :eventTs " +
            "AND ((:sender IS NULL AND sender IS NULL) OR sender = :sender)",
    )
    fun findByCorrelation(threadId: String, sender: String?, eventTs: Long): List<CapturedEvent>

    /**
     * The next batch of events to upload. Only events flagged `ready_for_upload`
     * drain: an event with attachments is held back until its attachments'
     * content_hashes are computed and patched into raw_json, so the server can
     * link the attachment bytes by content_hash. Attachment-free events are ready
     * at insert time and drain immediately.
     */
    @Query("SELECT * FROM captured_events WHERE uploaded = 0 AND ready_for_upload = 1 ORDER BY id ASC LIMIT :limit")
    fun pendingUpload(limit: Int): List<CapturedEvent>

    @Query("UPDATE captured_events SET uploaded = 1 WHERE id IN (:ids)")
    fun markUploaded(ids: List<Long>)

    /**
     * Events captured but not yet eligible to upload because their attachment
     * content_hashes have not been computed. The out-of-band enqueuer drains this
     * set: it hashes each attachment, records the pending blob, patches the hash
     * into raw_json, then flips the event ready.
     */
    @Query("SELECT * FROM captured_events WHERE uploaded = 0 AND ready_for_upload = 0 ORDER BY id ASC LIMIT :limit")
    fun pendingAttachmentHashing(limit: Int): List<CapturedEvent>

    /**
     * Replace the stored payload, and set readiness, in one write - so an event and
     * its attachment metadata can never disagree about whether it may be uploaded.
     *
     * `readyForUpload = true` is the release: every attachment content_hash has been
     * patched into raw_json and the event may drain.
     *
     * `readyForUpload = false` records partial progress. An attachment pass that
     * hashed some attachments but not all writes what it has - so a later pass does
     * not re-stream bytes it already hashed, and the completeness observation an
     * unfinished attachment needs survives to the next pass - while the event stays
     * out of [pendingUpload]. Nothing written this way can reach the wire.
     */
    @Query("UPDATE captured_events SET raw_json = :rawJson, ready_for_upload = :readyForUpload WHERE id = :id")
    fun updatePayload(id: Long, rawJson: String, readyForUpload: Boolean)

    @Query("SELECT COUNT(*) FROM captured_events")
    fun count(): Int

    /** Number of captured events not yet uploaded - the in-app "pending" figure. */
    @Query("SELECT COUNT(*) FROM captured_events WHERE uploaded = 0")
    fun pendingCount(): Int

    /**
     * Number of events READY to upload right now - the exact set [pendingUpload]
     * drains (`uploaded = 0 AND ready_for_upload = 1`). Distinct from [pendingCount],
     * which also counts events still awaiting attachment hashing: gating the upload
     * worker's post-drain re-enqueue on THIS avoids spinning while attachment-bearing
     * rows are held back for the separate hashing/attachment pass.
     */
    @Query("SELECT COUNT(*) FROM captured_events WHERE uploaded = 0 AND ready_for_upload = 1")
    fun pendingUploadCount(): Int

    /**
     * Bounded post-upload retention: drop events whose bytes already reached the
     * server and that were captured before [cutoff]. The message body lives in
     * raw_json, so retaining uploaded rows forever keeps cleartext-on-device long
     * after it is needed; pruning bounds that exposure to the retention window.
     *
     * Two invariants make this safe to prune:
     *  - Server idempotency `(provider_id, event_type, event_ts, body_hash)`:
     *    re-emission of an unchanged provider row is suppressed by the separate
     *    telephony_snapshot cache (the capture pass is a snapshot-diff, NOT a
     *    re-scan-and-reinsert of captured_events), so a pruned row cannot be
     *    re-captured as a duplicate.
     *  - Edit correlation: an edit surfaces as a NEW provider row that inherits the
     *    original's (thread_id, sender, event_ts), and the classifier correlates it
     *    against this durable captured_events history (findByCorrelation). A message
     *    can only be edited while it still exists in the provider - and while it
     *    exists in the provider it still has a telephony_snapshot row. So we ONLY
     *    prune events whose provider_id is NO LONGER in telephony_snapshot: those
     *    messages are gone from the device and can never produce a future edit that
     *    needs this row as its correlation target. A still-live message is retained
     *    regardless of age so its edits keep classifying correctly.
     *
     * Only `uploaded = 1` rows are eligible, so nothing still awaiting upload is
     * touched.
     */
    @Query(
        "DELETE FROM captured_events " +
            "WHERE uploaded = 1 AND created_at < :cutoff " +
            "AND provider_id NOT IN (SELECT provider_id FROM telephony_snapshot)",
    )
    fun pruneUploadedOlderThan(cutoff: Long): Int
}
