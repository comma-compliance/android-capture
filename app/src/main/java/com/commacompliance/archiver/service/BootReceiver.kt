package com.commacompliance.archiver.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.commacompliance.archiver.upload.HeartbeatWorker
import com.commacompliance.archiver.upload.ReconcileWorker
import com.commacompliance.archiver.upload.UploadWorker

/**
 * Resumes background work after a reboot. WorkManager already persists enqueued work
 * across reboots, but re-enqueuing here is belt-and-suspenders: it guarantees a
 * device that rebooted with a pending event/attachment backlog resumes draining
 * promptly, re-registers the periodic heartbeat, and re-arms the connectivity-gained
 * reconcile - without waiting for the next message broadcast or app launch.
 *
 * Capture itself is broadcast-driven by the messaging app and needs no boot hook;
 * this is purely about resuming the DRAIN/heartbeat side. Nothing here does heavy
 * or networked work inline - it only enqueues network-gated jobs.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        Log.i(TAG, "resuming background drain after $action")
        UploadWorker.enqueue(context)
        ReconcileWorker.enqueue(context)
        HeartbeatWorker.schedule(context)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
