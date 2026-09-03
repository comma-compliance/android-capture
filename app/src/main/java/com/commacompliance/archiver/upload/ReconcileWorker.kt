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
import com.commacompliance.archiver.capture.ReconciliationScan
import java.util.concurrent.TimeUnit

/**
 * Connectivity-regained maintenance. Enqueued (network-constrained) at app start
 * and boot; WorkManager runs it once a validated network is available, which makes
 * it a "the connection came back" trigger. It runs a THROTTLED reconciliation scan
 * (so a flapping connection cannot drive a full provider scan per flap) and then
 * flushes pending uploads.
 *
 * Distinct from [UploadWorker], which only drains an already-captured queue: this
 * also re-diffs the provider, catching changes a missed broadcast skipped while the
 * device was offline. Capture is local and always runs on broadcasts; this closes
 * the gap where capture itself did not run.
 */
class ReconcileWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    // A Worker must return a Result, never throw; any failure becomes retry() under
    // backoff. Narrowing the catch would let an unexpected exception crash the worker.
    @Suppress("TooGenericExceptionCaught")
    override fun doWork(): Result = try {
        ReconciliationScan.runIfDue(applicationContext)
        UploadWorker.enqueue(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Log.w(TAG, "reconcile pass failed transiently: ${e.javaClass.simpleName}")
        Result.retry()
    }

    companion object {
        private const val TAG = "ReconcileWorker"
        private const val UNIQUE_WORK = "android-archiver-reconcile"
        private const val BACKOFF_MINUTES = 5L

        /**
         * Enqueue a network-gated reconcile. REPLACE so the latest request carries
         * the current constraint; coalesces repeated enqueues into one pending job.
         * Explicit exponential backoff so a persistently-failing pass (e.g. a full
         * disk) backs off rather than retrying hot.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReconcileWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
