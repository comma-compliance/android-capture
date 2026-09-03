package com.commacompliance.archiver.capture

import android.content.Context
import android.util.Log
import com.commacompliance.archiver.data.ArchiverDatabase
import com.commacompliance.archiver.data.ArchiverMetaDao
import com.commacompliance.archiver.data.QueueRetention

/**
 * The periodic full-reconciliation trigger. It runs the SAME capture pass as a
 * broadcast - a whole-provider snapshot-diff - on a different schedule, to catch
 * adds/edits/deletes that a missed broadcast or a Doze window skipped. Because the
 * pass is serialized under [CaptureCoordinator]'s lock and emits zero events when
 * the provider is unchanged, re-running it is a safe no-op that only advances
 * `last_scanned_at`.
 *
 * [runIfDue] throttles bursty triggers (e.g. a flapping connection firing
 * connectivity-regained repeatedly): it skips the scan when the last one committed
 * within [MIN_INTERVAL_MS]. The heartbeat path uses [runNow] to always scan.
 */
object ReconciliationScan {

    private const val TAG = "ReconciliationScan"

    /** Minimum gap between throttled scans; protects against network-flap storms. */
    const val MIN_INTERVAL_MS = 5 * 60 * 1000L

    /**
     * Run unconditionally (heartbeat). Returns the pass outcome. Also prunes the
     * queue's bounded post-upload retention window on the way out: a reconcile pass
     * is exactly when uploaded rows have settled, so it is the natural place to bound
     * how long uploaded (body-bearing) events linger on-device. Retention failures
     * are non-fatal to the scan.
     */
    fun runNow(context: Context): CaptureCoordinator.Outcome {
        val outcome = CaptureCoordinatorFactory.create(context).runOnce()
        runCatching { QueueRetention.prune(ArchiverDatabase.get(context)) }
            .onFailure { Log.w(TAG, "retention prune skipped: ${it.javaClass.simpleName}") }
        return outcome
    }

    /**
     * Pure throttle decision: is a scan due given when the last one committed?
     * Extracted so the gating is unit-testable without a database.
     */
    fun isDue(lastScannedAtMs: Long, now: Long): Boolean = now - lastScannedAtMs >= MIN_INTERVAL_MS

    /**
     * Run only if the last scan is older than [MIN_INTERVAL_MS]. Returns the outcome
     * when it ran, or null when throttled. Reads the marker from the durable store;
     * intended to be called off the main thread (from a WorkManager worker).
     */
    fun runIfDue(context: Context, now: Long = System.currentTimeMillis()): CaptureCoordinator.Outcome? {
        val metaDao = ArchiverDatabase.get(context).archiverMetaDao()
        val last = metaDao.get(ArchiverMetaDao.KEY_LAST_SCANNED_AT)?.toLongOrNull() ?: 0L
        if (!isDue(last, now)) {
            Log.d(TAG, "reconciliation throttled (last scan ${now - last}ms ago)")
            return null
        }
        return runNow(context)
    }
}
