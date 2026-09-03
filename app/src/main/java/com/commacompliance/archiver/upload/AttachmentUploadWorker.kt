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
 * WorkManager job that prepares (hashes) and uploads captured attachment binaries.
 * It runs entirely OUT-OF-BAND from the capture shortService (which only persists
 * events) AND from the event [UploadWorker] (its own unique work), so a large video
 * upload never blocks event draining and neither ever touches the time-boxed
 * capture window.
 *
 * Each pass first runs the [AttachmentEnqueuer] (streaming-hash any not-yet-hashed
 * attachments, record them, release their events for upload) and then the
 * [AttachmentUploader] (drain the pending-blob store, single-shot or chunked).
 * Network-constrained with exponential backoff; resumable, so a killed pass simply
 * re-runs and re-sends idempotently.
 */
class AttachmentUploadWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    // A Worker must return a Result, never throw; any failure becomes retry() under
    // backoff. Narrowing the catch would let an unexpected exception crash the worker.
    @Suppress("TooGenericExceptionCaught")
    override fun doWork(): Result {
        return try {
            val awaitingSettle = UploaderFactory.attachmentEnqueuer(applicationContext).runOnce()
            when (UploaderFactory.attachmentUploader(applicationContext).runOnce()) {
                AttachmentUploader.Result.DONE -> {
                    // Close the drop-the-trigger window (see UploadWorker): a capture
                    // broadcast landing while this pass finishes is coalesced away by
                    // KEEP. If blobs remain pending after a clean drain, chain one more
                    // pass so they upload promptly rather than waiting for the next
                    // capture.
                    //
                    // An attachment held back because its bytes have not settled needs
                    // the same treatment, but delayed: re-reading it immediately proves
                    // nothing (the enqueuer requires a real time gap between the two
                    // reads), so the follow-up waits out the settle interval. Without
                    // this the re-check would ride on the next capture broadcast, which
                    // on a quiet device could be hours away.
                    if (awaitingSettle) {
                        reenqueueSettleRecheck(applicationContext)
                    } else if (hasPendingUploads()) {
                        reenqueueFollowUp(applicationContext)
                    }
                    Result.success()
                }
                AttachmentUploader.Result.RETRY -> Result.retry()
                AttachmentUploader.Result.TOKEN_REJECTED -> Result.failure()
            }
        } catch (e: Exception) {
            Log.w(TAG, "attachment upload pass failed transiently: ${e.javaClass.simpleName}")
            Result.retry()
        }
    }

    private fun hasPendingUploads(): Boolean =
        ArchiverDatabase.get(applicationContext).pendingAttachmentDao().pendingCount() > 0

    companion object {
        private const val TAG = "AttachmentUploadWorker"
        private const val UNIQUE_WORK = "android-archiver-attachment-upload"
        private const val BACKOFF_SECONDS = 30L

        /**
         * Enqueue a single coalesced attachment-upload pass. Its own unique work,
         * distinct from the event UploadWorker, so attachments drain independently
         * (a multi-MB video does not stall the 500-event message batches). KEEP so
         * a burst of broadcasts does not duplicate an in-flight pass; a post-drain
         * follow-up (below) catches blobs that raced this pass's finish.
         */
        fun enqueue(context: Context) {
            enqueue(context, ExistingWorkPolicy.KEEP)
        }

        /**
         * Re-enqueue a follow-up pass after a clean drain that left blobs behind.
         * APPEND_OR_REPLACE chains the new pass AFTER the current (finishing) one so
         * it is never coalesced away. It runs only when a leftover is detected, so it
         * cannot spin.
         */
        private fun reenqueueFollowUp(context: Context) {
            enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }

        /**
         * Re-read an attachment whose bytes had not settled, after the enqueuer's
         * settle interval has elapsed. The delay is the point: the completeness rule
         * is two identical reads separated in time, so an immediate re-run would be
         * wasted work. Self-limiting - the enqueuer stops asking once the bytes stop
         * changing (or after its bounded observation budget), so this cannot spin.
         */
        private fun reenqueueSettleRecheck(context: Context) {
            enqueue(
                context,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                initialDelayMs = AttachmentEnqueuer.SETTLE_INTERVAL_MS,
            )
        }

        private fun enqueue(context: Context, policy: ExistingWorkPolicy, initialDelayMs: Long = 0L) {
            val request = OneTimeWorkRequestBuilder<AttachmentUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
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
