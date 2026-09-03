package com.commacompliance.archiver.onboarding

import android.content.Context
import android.content.SharedPreferences

/**
 * A tiny record of "when did archiving last succeed", surfaced on the Status
 * screen so a user or auditor can see capture is alive and current. Written when
 * an upload pass completes (and by the self-test); read by the Status screen.
 *
 * Plain (unencrypted) SharedPreferences: a millisecond timestamp is not a
 * credential. Kept separate from the credential store on purpose.
 */
class SyncStatusStore(
    context: Context,
    prefsFactory: (Context) -> SharedPreferences = { it.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) },
) {
    private val prefs: SharedPreferences = prefsFactory(context.applicationContext)

    /** Epoch-millis of the last successful upload, or null if there has not been one. */
    fun lastSyncAtMillis(): Long? = prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0L }

    fun recordSync(atMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_SYNC, atMillis).apply()
    }

    companion object {
        private const val PREFS_NAME = "archiver_sync_status"
        private const val KEY_LAST_SYNC = "last_sync_at"
    }
}
