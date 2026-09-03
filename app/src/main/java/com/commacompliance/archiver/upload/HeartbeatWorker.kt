package com.commacompliance.archiver.upload

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic maintenance so an idle, connected device stays visible and current:
 * it heartbeats the server (updating `last_seen_at` and refreshing the backfill
 * policy), runs a full reconciliation scan, and flushes pending uploads.
 *
 * Periodic WorkManager is intentionally inexact - under Doze/app-standby a ~6h
 * period is best-effort, not an SLA, which is the right tradeoff for battery. The
 * heartbeat's only job is "an idle device eventually checks in"; nothing
 * time-critical depends on the exact cadence. It is network-constrained so it never
 * wakes a device with no connectivity just to fail.
 */
class HeartbeatWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    // A Worker must return a Result, never throw; a transient failure becomes retry()
    // under backoff, while a terminally rejected enrollment_token ends this occurrence
    // as failure() rather than spinning the impossible enrollment under backoff (the
    // 6h periodic schedule still re-fires later). Narrowing the catch would let an
    // unexpected exception crash the worker.
    @Suppress("TooGenericExceptionCaught")
    override fun doWork(): Result = try {
        when (UploaderFactory.heartbeatRunner(applicationContext).runOnce()) {
            HeartbeatRunner.Result.DONE -> Result.success()
            HeartbeatRunner.Result.RETRY -> Result.retry()
            // No path forward without admin reprovisioning; do not backoff-retry.
            HeartbeatRunner.Result.TOKEN_REJECTED -> Result.failure()
        }
    } catch (e: Exception) {
        Log.w(TAG, "heartbeat pass failed transiently: ${e.javaClass.simpleName}")
        Result.retry()
    }

    companion object {
        private const val TAG = "HeartbeatWorker"
        private const val UNIQUE_WORK = "android-archiver-heartbeat"
        private const val PERIOD_HOURS = 6L
        private const val BACKOFF_MINUTES = 15L

        /**
         * Register the periodic heartbeat. KEEP so re-registering (every app start /
         * boot) does not reset the running schedule. Network-constrained + backoff.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
