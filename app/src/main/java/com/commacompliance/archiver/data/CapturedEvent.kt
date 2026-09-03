package com.commacompliance.archiver.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A durably-stored classified message event awaiting upload.
 *
 * The unique index on `(provider_id, event_type, event_ts, body_hash)` mirrors the
 * server's idempotency key exactly. Because the broadcast is only a wake-up and the
 * same row may be re-read across many broadcasts, re-inserting an identical event is
 * a deliberate no-op (`OnConflictStrategy.IGNORE`), so re-reads never produce
 * duplicates locally or on the server.
 */
@Entity(
    tableName = "captured_events",
    indices = [
        Index(
            value = ["provider_id", "event_type", "event_ts", "body_hash"],
            unique = true,
        ),
        Index(value = ["uploaded"]),
        Index(value = ["ready_for_upload"]),
        // Edit-correlation looks up prior captures by (thread, sender, timestamp).
        Index(value = ["thread_id", "sender", "event_ts"]),
    ],
)
data class CapturedEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @androidx.room.ColumnInfo(name = "provider_id")
    val providerId: String,
    @androidx.room.ColumnInfo(name = "event_type")
    val eventType: String,
    @androidx.room.ColumnInfo(name = "event_ts")
    val eventTs: Long,
    @androidx.room.ColumnInfo(name = "body_hash")
    val bodyHash: String,
    // Denormalized correlation columns (also present inside raw_json) so the
    // edit-correlation query does not have to parse every stored payload.
    @androidx.room.ColumnInfo(name = "thread_id")
    val threadId: String,
    @androidx.room.ColumnInfo(name = "sender")
    val sender: String?,
    /** The normalized payload JSON (the server-facing `payload` object). */
    @androidx.room.ColumnInfo(name = "raw_json")
    val rawJson: String,
    @androidx.room.ColumnInfo(name = "uploaded")
    val uploaded: Boolean = false,
    /**
     * Whether this event may be drained to the ingest server yet. An event with NO
     * attachments is ready immediately. An event WITH attachments is held back
     * (`false`) until its attachments' `content_hash`es have been computed
     * out-of-band and patched into `raw_json` - the server links attachment bytes
     * to the message by `content_hash`, so uploading the event before its hashes
     * exist would orphan the attachment metadata. Defaults true so a plain message
     * (the common case) never waits.
     */
    @androidx.room.ColumnInfo(name = "ready_for_upload")
    val readyForUpload: Boolean = true,
    @androidx.room.ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)
