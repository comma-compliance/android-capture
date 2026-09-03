package com.commacompliance.archiver.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connectivity-regained throttle: a recent scan suppresses another, so a
 * flapping connection cannot drive a full provider scan per flap. Full
 * scan-correctness is covered by CaptureCoordinatorTest; here only the gating
 * decision is asserted, as a pure function.
 */
class ReconciliationScanTest {

    @Test
    fun notDue_whenLastScanIsRecent() {
        val now = 10_000_000L
        // 1s ago is well within the 5-minute throttle window.
        assertFalse(ReconciliationScan.isDue(now - 1_000, now))
    }

    @Test
    fun due_whenLastScanIsStale() {
        val now = 10_000_000L
        assertTrue(ReconciliationScan.isDue(now - ReconciliationScan.MIN_INTERVAL_MS - 1, now))
    }

    @Test
    fun due_atExactlyTheInterval() {
        val now = 10_000_000L
        assertTrue(ReconciliationScan.isDue(now - ReconciliationScan.MIN_INTERVAL_MS, now))
    }

    @Test
    fun due_whenNeverScanned() {
        // last_scanned_at defaults to 0; the first connectivity-regained always runs.
        assertTrue(ReconciliationScan.isDue(0, 10_000_000L))
    }
}
