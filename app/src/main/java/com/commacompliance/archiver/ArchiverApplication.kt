package com.commacompliance.archiver

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import com.commacompliance.archiver.config.ManagedConfigChangeReceiver
import com.commacompliance.archiver.enroll.EnrollmentStore
import com.commacompliance.archiver.upload.HeartbeatWorker
import com.commacompliance.archiver.upload.ReconcileWorker
import com.commacompliance.archiver.upload.UploadWorker

/**
 * Registers the managed-config-change receiver for the process lifetime and arms
 * the background maintenance jobs whenever the process starts.
 *
 * `ACTION_APPLICATION_RESTRICTIONS_CHANGED` is only delivered to context-registered
 * receivers, never manifest-declared ones, so it is registered here rather than in
 * the manifest. The capture path is broadcast-driven and may run with no persistent
 * process, but whenever the process is alive this keeps managed-config rotations
 * observed; an enrollment refresh also happens lazily on the next upload pass.
 *
 * On start we also (idempotently) register the periodic heartbeat and a
 * network-gated reconcile, and nudge the upload drain - so a process that came up
 * from any path (launch, broadcast, boot) resumes draining a pending backlog and an
 * idle connected device keeps checking in. Only done once connected, so an
 * unconnected install does not schedule pointless work.
 */
class ArchiverApplication : Application() {
    private val configReceiver = ManagedConfigChangeReceiver()

    override fun onCreate() {
        super.onCreate()
        registerReceiver(
            configReceiver,
            IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
        )
        // Arm background maintenance off the critical startup path. Reading the
        // (keystore-backed) credential store or scheduling work must never crash app
        // startup; if it hiccups, the next broadcast/connect re-arms the jobs.
        runCatching {
            if (EnrollmentStore(this).isConnected()) {
                HeartbeatWorker.schedule(this)
                ReconcileWorker.enqueue(this)
                UploadWorker.enqueue(this)
            }
        }
    }
}
