package com.commacompliance.archiver.upload

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.commacompliance.archiver.data.ArchiverDatabase
import java.util.concurrent.TimeUnit

/**
 * WorkManager job that drains the durable captured-event store to the ingest
 * server. It runs independently of the capture shortService (which must never do
 * network work) and is enqueued after each capture pass.
 *
 * Constraints: requires network connectivity. Failures retry with exponential
 * backoff so a flaky network or transient 5xx does not hammer the server. A
 * rejected enrollment token (no path forward without admin reprovisioning) ends
 * as failure rather than an endless retry storm.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    // A Worker must return a Result, never throw: any transport-level failure is
    // converted to retry() under backoff. Narrowing the catch would let an
    // unexpected runtime exception crash the worker instead of retrying.
    @Suppress("TooGenericExceptionCaught")
    override fun doWork(): Result {
        return try {
            when (UploaderFactory.create(applicationContext).runOnce()) {
                Uploader.Result.DONE -> {
                    // Close the drop-the-trigger window: a capture broadcast that
                    // lands while this pass is finishing is coalesced away by KEEP and
                    // would otherwise wait for the next broadcast. If rows remain
                    // pending after a clean drain, chain one more pass so they upload
                    // promptly instead of sitting until the next capture.
                    if (hasPendingUploads()) reenqueueFollowUp(applicationContext)
                    Result.success()
                }
                Uploader.Result.RETRY -> Result.retry()
                // No point retrying: only an admin reprovisioning the managed
                // enrollment_token can recover, which arrives as a config change that
                // re-enqueues a fresh run.
                Uploader.Result.TOKEN_REJECTED -> Result.failure()
            }
        } catch (e: Exception) {
            // Transport-level failure (e.g. IOException from the POST). Let backoff
            // handle it; do not log the request body or any token.
            Log.w(TAG, "upload pass failed transiently: ${e.javaClass.simpleName}")
            Result.retry()
        }
    }

    // Gate on rows READY to upload (the exact set the pass drains), NOT all
    // unuploaded rows: events still awaiting attachment hashing are held back for
    // the separate attachment pass, so counting them here would re-enqueue forever
    // while hashing is pending.
    private fun hasPendingUploads(): Boolean =
        ArchiverDatabase.get(applicationContext).capturedEventDao().pendingUploadCount() > 0

    companion object {
        private const val TAG = "UploadWorker"
        private const val UNIQUE_WORK = "android-archiver-upload"
        private const val BACKOFF_SECONDS = 30L

        /**
         * Enqueue a single coalesced upload pass. KEEP policy means an
         * already-pending/running upload is not duplicated by a burst of broadcasts;
         * any events captured after it starts are picked up by the next enqueue
         * (or by the post-drain follow-up below if they raced this pass's finish).
         */
        fun enqueue(context: Context) {
            enqueue(context, ExistingWorkPolicy.KEEP)
        }

        /**
         * Re-enqueue a follow-up pass after a clean drain that left rows behind.
         * APPEND_OR_REPLACE chains the new pass AFTER the current (finishing) one so
         * it is never coalesced away, draining rows that landed during this pass
         * rather than letting them wait for the next capture broadcast. It runs once
         * per leftover-detecting pass, so it cannot spin: a pass that drains cleanly
         * with nothing left does not re-enqueue.
         */
        private fun reenqueueFollowUp(context: Context) {
            enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                )
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK, policy, request)
        }
    }
}
