package com.commacompliance.archiver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ArchiverMetaDao {

    @Query("SELECT value FROM archiver_meta WHERE key = :key")
    fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(meta: ArchiverMeta)

    fun putValue(key: String, value: String) = put(ArchiverMeta(key, value))

    companion object {
        /**
         * Set true (inside the baseline transaction) once the first-run snapshot has
         * been established. Distinguishes "never captured" from "captured, provider
         * is genuinely empty" so an empty live provider is not re-treated as first run.
         */
        const val KEY_FIRST_RUN_DONE = "first_run_done"

        /** Epoch-millis the last capture/reconciliation pass committed. */
        const val KEY_LAST_SCANNED_AT = "last_scanned_at"

        /**
         * Set when a durable write failed for lack of storage. Surfaced in the UI as
         * a blocked state; un-uploaded events are NEVER dropped to make room.
         */
        const val KEY_STORAGE_BLOCKED = "storage_blocked"
    }
}
