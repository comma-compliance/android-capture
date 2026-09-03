package com.commacompliance.archiver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TelephonySnapshotDao {

    @Query("SELECT * FROM telephony_snapshot")
    fun all(): List<TelephonySnapshot>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(snapshot: TelephonySnapshot)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(snapshots: List<TelephonySnapshot>)

    @Query("DELETE FROM telephony_snapshot WHERE provider_id IN (:providerIds)")
    fun deleteByProviderIds(providerIds: List<String>)

    @Query("SELECT COUNT(*) FROM telephony_snapshot")
    fun count(): Int
}
