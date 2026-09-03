package com.commacompliance.archiver.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A durably-stored attachment binary awaiting out-of-band upload, one row per
 * captured MMS/RCS part that carries bytes.
 *
 * This store obeys the SAME no-drop/offline-durable/reboot-resume policy as the
 * captured-event queue: an attachment is recorded the moment its hash is computed
 * and is NEVER discarded until its bytes have reached the server (`uploaded`).
 * Uploads are network-gated via WorkManager with backoff; capture and hashing are
 * local and run regardless of connectivity.
 *
 * Keyed unique on `(content_hash, part_uri)`: the same file referenced by two
 * different parts is the same blob server-side (dedup by content_hash) but is
 * still tracked per part_uri so each capture's reference resolves independently.
 * Re-recording an identical (hash, uri) is a deliberate no-op so a re-scan never
 * duplicates the pending row.
 */
@Entity(
    tableName = "pending_attachments",
    indices = [
        Index(value = ["content_hash", "part_uri"], unique = true),
        Index(value = ["uploaded"]),
    ],
)
data class PendingAttachment(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "part_uri")
    val partUri: String,
    @ColumnInfo(name = "mime")
    val mime: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "uploaded")
    val uploaded: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)
