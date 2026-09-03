package com.commacompliance.archiver.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A tiny durable key/value store living in the SAME Room database as the snapshot
 * and the captured-event queue. It exists so capture-pass bookkeeping markers can
 * be written inside the SAME transaction that establishes/overwrites the snapshot,
 * which is what makes the first-run baseline crash-safe:
 *
 * - `first_run_done` must flip to true ONLY in the same transaction that persists
 *   the full baseline snapshot. If it lived in SharedPreferences and were set
 *   separately, a crash between the two writes could leave the flag set with an
 *   empty snapshot, and the next pass would treat the entire provider as brand-new.
 * - `last_scanned_at` records when the last reconciliation/capture pass committed,
 *   used to throttle connectivity-regained scans and to surface freshness in the UI.
 */
@Entity(tableName = "archiver_meta")
data class ArchiverMeta(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,
    @ColumnInfo(name = "value")
    val value: String,
)
