package com.commacompliance.archiver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingAttachmentDao {

    /**
     * Record an attachment as pending upload. A conflict on `(content_hash,
     * part_uri)` is ignored so re-scanning an already-recorded part is a no-op.
     * Returns the new rowid, or -1 when the row already existed.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(attachment: PendingAttachment): Long

    /** The next batch of attachments still needing their bytes uploaded. */
    @Query("SELECT * FROM pending_attachments WHERE uploaded = 0 ORDER BY id ASC LIMIT :limit")
    fun pendingUploads(limit: Int): List<PendingAttachment>

    /** True once an attachment with this content_hash already has its bytes uploaded. */
    @Query("SELECT EXISTS(SELECT 1 FROM pending_attachments WHERE content_hash = :contentHash AND uploaded = 1)")
    fun isUploaded(contentHash: String): Boolean

    @Query("UPDATE pending_attachments SET uploaded = 1 WHERE id = :id")
    fun markUploaded(id: Long)

    /**
     * Mark every pending row sharing a content_hash uploaded at once. The server
     * dedups bytes per (account, content_hash), so once any one part's bytes land
     * all references to the same blob are satisfied - no need to re-upload for a
     * second part_uri that points at identical content.
     */
    @Query("UPDATE pending_attachments SET uploaded = 1 WHERE content_hash = :contentHash")
    fun markUploadedByHash(contentHash: String)

    /** Count of attachments whose bytes have not yet reached the server. */
    @Query("SELECT COUNT(*) FROM pending_attachments WHERE uploaded = 0")
    fun pendingCount(): Int

    /**
     * Bounded post-upload retention: drop attachment-tracking rows whose bytes
     * already reached the server and that were recorded before [cutoff]. These rows
     * hold no message body, but the part_uri and content_hash are still device-local
     * metadata; pruning completed rows keeps the table from growing without bound and
     * matches the captured-event retention window. Only `uploaded = 1` rows are
     * eligible, so a blob still needing upload is never removed.
     */
    @Query("DELETE FROM pending_attachments WHERE uploaded = 1 AND created_at < :cutoff")
    fun pruneUploadedOlderThan(cutoff: Long): Int
}
