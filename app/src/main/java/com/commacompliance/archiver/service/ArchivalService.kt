package com.commacompliance.archiver.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.commacompliance.archiver.R
import com.commacompliance.archiver.capture.CaptureCoordinatorFactory
import com.commacompliance.archiver.enroll.EnrollmentStore
import com.commacompliance.archiver.upload.AttachmentUploadWorker
import com.commacompliance.archiver.upload.UploadWorker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/**
 * Capture core. The default messaging app starts this service with an explicit
 * broadcast on each message event. Inside the brief, time-boxed shortService
 * window it does ONLY the cheap, must-happen-now work: read the Telephony delta vs
 * the cached snapshot, classify each change (received/sent/edited/deleted), and
 * persist the classified events plus the refreshed snapshot to the durable Room
 * store. It performs no network or crypto - upload happens out-of-band so it never
 * risks the shortService deadline.
 *
 * Why shortService: the broadcast grants a brief foreground window (a hard time
 * limit on Android 14+, surfaced via onTimeout()), enough to persist locally but
 * not to do anything heavyweight.
 *
 * Why minSdk 34: shortService and its onTimeout() callback are Android 14+.
 *
 * The Telephony read and Room writes run on a single-thread background executor,
 * never the main thread.
 */
class ArchivalService : Service() {

    // Recreatable single-thread executor. onTimeout() shutdownNow()s it to
    // interrupt a running pass and discard queued ones; the next broadcast
    // re-creates a live one via submitCapture(). Guarded by a lock so a concurrent
    // onTimeout shutdown and onStartCommand submit cannot race.
    private val executorLock = Any()
    private var executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val capturePass = AtomicReference<Future<*>?>(null)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()

        // The triggering-row hint, when present, is only a Parcelable Uri and is
        // unreliable (may be stale, a box view, a bare table, or an internal media
        // URI). The actual source of truth is always the full provider delta scan,
        // never the extra alone, so we only note its presence - logging the URI
        // itself would leak message-row metadata into device logs.
        val hintUri = readArchivalUriHint(intent)
        Log.d(TAG, "archival broadcast received (uri hint present=${hintUri != null})")

        // TooGenericExceptionCaught: the capture pass runs inside the foreground
        // shortService; a thrown exception here would crash the service and could
        // surface an ANR/crash to the user mid-message. Catch everything, log, and
        // let the next broadcast re-derive the (idempotent) scan.
        @Suppress("TooGenericExceptionCaught")
        submitCapture {
            try {
                val coordinator = CaptureCoordinatorFactory.create(applicationContext, deviceNumber())
                val outcome = coordinator.runOnce()
                Log.i(
                    TAG,
                    "capture: scanned=${outcome.scanned} new=${outcome.newEvents} " +
                        "deleted=${outcome.deletes}",
                )
                // Hand the durable events off to the network-constrained upload job.
                // It runs independently with its own constraints + backoff, so this
                // never does network work inside the shortService window.
                UploadWorker.enqueue(applicationContext)
                // And kick the out-of-band attachment job: it hashes + uploads any
                // captured attachment binaries in its OWN WorkManager pass, so a
                // large video never blocks the event drain and no hashing/network
                // ever runs in the capture window.
                AttachmentUploadWorker.enqueue(applicationContext)
            } catch (t: Throwable) {
                // Log the exception CLASS only: the capture pass handles message
                // bodies and addresses, and a thrown exception's message/stack trace
                // can quote that content, so the Throwable itself must never reach the
                // device log. The next broadcast re-derives the (idempotent) scan.
                Log.e(TAG, "capture failed: ${t.javaClass.simpleName}")
            } finally {
                finish(startId)
            }
        }

        // Each event arrives as a fresh broadcast; do not recreate on process death.
        return START_NOT_STICKY
    }

    /**
     * Called when the shortService budget is about to expire. The coordinator
     * commits its work in a single Room transaction, so there is no partial write
     * to flush. Interrupt EVERY in-flight/queued pass - not just the last tracked
     * Future - because rapid overlapping broadcasts queue N tasks on the single
     * thread and an earlier one may be the one currently running past the budget.
     * shutdownNow() interrupts the running thread and discards the queue; a dropped
     * pass is re-derived on the next broadcast because the scan is idempotent, so
     * nothing is lost. The next onStartCommand recreates a live executor.
     */
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "shortService timeout reached; stopping (startId=$startId)")
        capturePass.getAndSet(null)?.cancel(true)
        synchronized(executorLock) { executor.shutdownNow() }
        finish(startId)
    }

    override fun onDestroy() {
        synchronized(executorLock) { executor.shutdownNow() }
        super.onDestroy()
    }

    /**
     * Submits a capture pass on a live executor, recreating the executor if a prior
     * onTimeout()/onDestroy() shut it down. Recreation + submit happen atomically
     * under [executorLock] so a concurrent shutdownNow() cannot reject the task or
     * leave it orphaned on a dead executor. The tracked Future is updated so
     * onTimeout()'s cancel(true) still targets the most recent pass.
     */
    private fun submitCapture(task: Runnable) = synchronized(executorLock) {
        if (executor.isShutdown) {
            executor = Executors.newSingleThreadExecutor()
        }
        capturePass.set(executor.submit(task))
    }

    private fun finish(startId: Int) {
        // stopSelf(startId) only tears the service down when startId is the most
        // recent start, so overlapping broadcasts (each its own startId) keep the
        // service alive until the last pass finishes, instead of a one-shot latch
        // that would skip stopSelf for every pass after the first.
        stopSelf(startId)
    }

    /**
     * Best-effort local device number, used to fill the side an SMS row omits.
     * Often null (carriers frequently do not populate it); the server resolves the
     * device side from the enrolled worker when it is absent, so a null is fine.
     */
    // HardwareIds: getLine1Number is deliberate. The archiver fills the side an
    // SMS row omits with this device's own number; it is device-identity data we
    // are explicitly archiving, not a covert tracking identifier. Best-effort and
    // usually null - the server resolves the side from the enrolled worker when absent.
    @SuppressLint("HardwareIds")
    private fun deviceNumber(): String? = try {
        val tm = getSystemService(android.telephony.TelephonyManager::class.java)
        @Suppress("DEPRECATION", "MissingPermission")
        tm?.line1Number?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    private fun readArchivalUriHint(intent: Intent?): Uri? {
        intent ?: return null
        // EXTRA_ARCHIVAL_URI is a Parcelable Uri, NOT a String - getStringExtra
        // returns null for it. minSdk is 34, so the typed getParcelableExtra
        // overload (API 33+) is always available.
        return intent.getParcelableExtra(EXTRA_ARCHIVAL_URI, Uri::class.java)
    }

    private fun startInForeground() {
        val channelId = ensureNotificationChannel()
        // Honest, never-hidden transparency: name the organization the device is
        // archiving for when we know it, so the user always sees who administers
        // the archive. Falls back to the generic wording before connection.
        val org = EnrollmentStore(applicationContext).connectedLabel()
        val text = if (org.isNullOrBlank()) {
            getString(R.string.archival_notification_text)
        } else {
            getString(R.string.archival_notification_text_org, org)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.archival_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
        )
    }

    private fun ensureNotificationChannel(): String {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.archival_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
        return CHANNEL_ID
    }

    companion object {
        private const val TAG = "ArchivalService"
        private const val CHANNEL_ID = "archival"
        private const val NOTIFICATION_ID = 1

        /** Optional extra naming the row that triggered the broadcast, when supplied. */
        const val EXTRA_ARCHIVAL_URI = "com.google.android.apps.messaging.EXTRA_ARCHIVAL_URI"
    }
}
