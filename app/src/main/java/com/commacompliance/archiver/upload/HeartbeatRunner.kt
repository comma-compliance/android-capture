package com.commacompliance.archiver.upload

import android.util.Log
import com.commacompliance.archiver.enroll.EnrollmentManager
import com.commacompliance.archiver.enroll.EnrollmentStore
import com.commacompliance.archiver.enroll.HeartbeatClient

/**
 * The heartbeat pass, factored out of its WorkManager wrapper so the
 * ensure-enrolled -> POST heartbeat -> refresh policy -> reconcile -> flush sequence
 * is testable from plain dependencies.
 *
 * Order matters:
 *   1. ensure we have a usable enrollment (a heartbeat needs a bearer token);
 *   2. POST the heartbeat so the server marks the device seen and returns the
 *      current backfill policy, which we persist (bookkeeping; first-run only);
 *   3. run a full reconciliation scan ([reconcile]) so an idle device still catches
 *      adds/edits/deletes missed while it had nothing to upload;
 *   4. flush pending event uploads ([flushUploads]).
 *
 * A heartbeat 401 is non-fatal here: the upload flush re-enrolls on its own 401
 * path, so we still reconcile and flush. Reconcile is local and runs even if the
 * network heartbeat fails, so a device with no connectivity still keeps its local
 * archive current and drains when the network returns.
 */
class HeartbeatRunner(
    private val enrollmentManager: EnrollmentManager,
    private val store: EnrollmentStore,
    private val heartbeatClient: HeartbeatClient,
    private val backendUrlResolver: () -> String?,
    private val reconcile: () -> Unit,
    private val flushUploads: () -> Unit,
) {
    enum class Result {
        /** The pass completed (or there was nothing to do); reschedule normally. */
        DONE,

        /** A transient failure; reschedule with backoff. */
        RETRY,

        /**
         * The managed enrollment_token is terminally rejected (dead token, or a
         * returning device whose signing key no longer matches). No amount of
         * retrying recovers it - only an admin reprovisioning can - so the heartbeat
         * must NOT loop on it with backoff, mirroring the upload path's terminal
         * disposition.
         */
        TOKEN_REJECTED,
    }

    fun runOnce(): Result {
        val enrollment = when (val outcome = enrollmentManager.ensureEnrolled()) {
            is EnrollmentManager.Outcome.Ready -> outcome.enrollment
            // Not set up yet, or token pulled: nothing to heartbeat. Not an error.
            EnrollmentManager.Outcome.NotConfigured -> return Result.DONE
            // Terminal: re-running the same impossible enrollment under backoff would
            // spin forever until reprovisioned, so stop instead of retrying.
            EnrollmentManager.Outcome.TokenRejected -> return Result.TOKEN_REJECTED
            EnrollmentManager.Outcome.Retry -> return Result.RETRY
        }

        val backendUrl = backendUrlResolver()
        var result = Result.DONE
        if (backendUrl != null) {
            when (val hb = heartbeatClient.send(backendUrl, enrollment.archiveToken)) {
                is HeartbeatClient.Result.Ok -> hb.backfillDays?.let { store.updateBackfillDays(it) }
                HeartbeatClient.Result.Unauthorized ->
                    Log.i(TAG, "heartbeat 401; upload flush will re-enroll")
                HeartbeatClient.Result.Retry -> result = Result.RETRY
            }
        } else {
            result = Result.RETRY
        }

        // Local reconcile + flush always run, even if the network heartbeat failed.
        runCatching { reconcile() }.onFailure { Log.w(TAG, "reconcile failed: ${it.javaClass.simpleName}") }
        runCatching { flushUploads() }.onFailure { Log.w(TAG, "flush enqueue failed: ${it.javaClass.simpleName}") }
        return result
    }

    companion object {
        private const val TAG = "HeartbeatRunner"
    }
}
