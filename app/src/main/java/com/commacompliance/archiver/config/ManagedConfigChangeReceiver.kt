package com.commacompliance.archiver.config

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.commacompliance.archiver.upload.UploadWorker

/**
 * Reacts to the EMM pushing new managed configuration
 * (`Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED`).
 *
 * On a managed device this is the PROACTIVE enrollment trigger: the moment the
 * admin provisions (or rotates) `backend_url` + `enrollment_token`, we kick the
 * upload job whose enrollment step re-reads the managed config and enrolls/
 * re-enrolls right away - rather than waiting for the first message broadcast.
 * A changed `enrollment_token` (detected by hash) forces a re-enroll against the
 * current host so events are never posted to a stale tenant/host with a dead
 * credential. A self-serve device has no managed config, so for it this is a
 * no-op beyond a harmless upload-job nudge.
 */
class ManagedConfigChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED) return
        Log.i(TAG, "managed config changed; scheduling enrollment + upload refresh")
        UploadWorker.enqueue(context)
    }

    companion object {
        private const val TAG = "ManagedConfigChange"
    }
}
