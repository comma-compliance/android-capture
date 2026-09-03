package com.commacompliance.archiver.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The cached last-known provider state for a single message row, keyed by its
 * table-namespaced `provider_id`. This is the prior-snapshot half of the
 * snapshot-diff: it lets the next broadcast detect what changed.
 *
 * Two columns are load-bearing beyond bookkeeping:
 * - It retains enough of the last-known payload (`raw_json`) to emit a faithful
 *   `deleted` event when a row vanishes from the provider (hard deletes leave no
 *   tombstone, so the only record of a deleted message is this cache).
 * - `body_hash` + `timestamp` + `thread_id` + `sender` are the diff/correlation keys.
 */
@Entity(tableName = "telephony_snapshot")
data class TelephonySnapshot(
    @PrimaryKey
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "body_hash")
    val bodyHash: String,
    @ColumnInfo(name = "timestamp_ms")
    val timestampMs: Long,
    @ColumnInfo(name = "thread_id")
    val threadId: String,
    @ColumnInfo(name = "sender")
    val sender: String?,
    /** Last-known normalized payload JSON, replayed verbatim on delete. */
    @ColumnInfo(name = "raw_json")
    val rawJson: String,
    /**
     * Fingerprint of the row's attachment set (see `AttachmentsFingerprint`), the
     * second half of the change key. `body_hash` covers the message text only, so
     * without this column an MMS/RCS row whose media is filled in later under the
     * same provider id is indistinguishable from unchanged.
     *
     * NULL means "recorded before this column existed". It is deliberately NOT
     * defaulted to the empty-set hash: on the first pass after upgrading, every
     * existing row with media would then compare unequal and emit an edit, dumping
     * a spurious revision for the device's entire MMS history into the archive. A
     * NULL is instead backfilled silently on the next pass - one pre-existing
     * late-fill may be missed, which is strictly better than mass false edits.
     */
    @ColumnInfo(name = "attachments_hash")
    val attachmentsHash: String? = null,
)
