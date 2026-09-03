package com.commacompliance.archiver.capture

import android.content.ContentResolver
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.commacompliance.archiver.data.ArchiverDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the full serialized capture pass against a real in-memory Room store
 * and a scriptable reader, confirming: first-capture persists, re-reads dedupe via
 * the server-mirrored unique key, deletes fire by absence, edits correlate against
 * durable history, and status churn is a no-op. The Telephony provider itself is
 * not simulated here (Robolectric does not back the SMS/MMS provider); the reader
 * is stubbed so the coordinator + classifier + DAO path is what is verified.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureCoordinatorTest {

    private lateinit var db: ArchiverDatabase

    /** A reader whose scan output is scripted per pass. */
    private class FakeReader : TelephonyReader(
        Mockito.mock(ContentResolver::class.java),
    ) {
        var scan: List<NormalizedEvent> = emptyList()
        override fun readAll(): List<NormalizedEvent> = scan
    }

    private val reader = FakeReader()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, ArchiverDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun rcs(id: Long, ts: Long, body: String, dir: Direction = Direction.INCOMING) =
        NormalizedEvent(
            source = Source.RCS, providerId = "mms:$id", threadId = "2",
            threadType = ThreadType.ONE_TO_ONE, direction = dir, timestampMs = ts,
            from = "+15555550111", to = listOf("+15555550100"), cc = emptyList(),
            participants = listOf("+15555550111", "+15555550100"), body = body,
            attachments = emptyList(), isRcs = true,
            creator = "com.google.android.apps.messaging",
        )

    private fun coordinator(
        backfillDays: Int = 0,
        now: Long = 1779700000000,
        contactNameResolver: ContactNameResolver = ContactNameResolver { null },
    ) = CaptureCoordinator(
        reader,
        db,
        backfillDaysProvider = { backfillDays },
        now = { now },
        contactNameResolver = contactNameResolver,
    )

    /** A resolver that records every address it is asked about. */
    private class CountingResolver(
        private val names: Map<String, String?>,
    ) : ContactNameResolver {
        val calls = mutableListOf<String>()
        override fun displayName(address: String): String? {
            calls += address
            return names[address]
        }
    }

    /** Day in ms, for building backfill-window fixtures. */
    private val day = 24L * 60 * 60 * 1000

    /**
     * Establish the first-run baseline over an empty provider so the rows the test
     * then introduces are genuinely NEW (post-baseline) and emit forward-only -
     * isolating the per-broadcast classification behavior from the first-run policy,
     * which is covered by its own tests.
     */
    private fun baseline() {
        val saved = reader.scan
        reader.scan = emptyList()
        coordinator().runOnce()
        reader.scan = saved
    }

    @Test
    fun firstCapture_persistsEventAndSnapshot() {
        baseline()
        reader.scan = listOf(rcs(12, 1779668768000, "hi"))
        val outcome = coordinator().runOnce()
        assertEquals(1, outcome.newEvents)
        assertEquals(1, db.capturedEventDao().count())
        assertEquals(1, db.telephonySnapshotDao().count())
    }

    @Test
    fun reReadSameRow_acrossBroadcasts_dedupes() {
        baseline()
        reader.scan = listOf(rcs(12, 1779668768000, "hi"))
        coordinator().runOnce()
        // A second and third broadcast re-read the identical row.
        coordinator().runOnce()
        coordinator().runOnce()
        assertEquals("only one durable event despite three broadcasts",
            1, db.capturedEventDao().count())
    }

    @Test
    fun delete_byAbsence_emitsDeletedAndDropsSnapshot() {
        baseline()
        reader.scan = listOf(rcs(12, 1779668768000, "hi"))
        coordinator().runOnce()
        // Next broadcast: the row is gone from the provider.
        reader.scan = emptyList()
        val outcome = coordinator().runOnce()
        assertEquals(1, outcome.deletes)
        assertEquals(0, db.telephonySnapshotDao().count())
        // captured_events now has the original received + the delete.
        assertEquals(2, db.capturedEventDao().count())
    }

    @Test
    fun edit_correlatesAgainstDurableHistory_acrossBroadcasts() {
        baseline()
        val ts = 1779669371000
        // Broadcast 1: original received.
        reader.scan = listOf(rcs(16, ts, "Edit this #4"))
        coordinator().runOnce()
        // Broadcast 2: original still present + edit row sharing thread+sender+ts.
        reader.scan = listOf(
            rcs(16, ts, "Edit this #4"),
            rcs(17, ts, "Edit this #4 - EDITED"),
        )
        coordinator().runOnce()

        val edits = db.capturedEventDao().pendingUpload(100)
            .filter { it.eventType == EventType.EDITED.wire }
        assertEquals(1, edits.size)
        assertEquals("mms:17", edits[0].providerId)
        assertTrue(edits[0].rawJson.contains("\"edits_provider_id\":\"mms:16\""))
    }

    @Test
    fun statusChurn_noNewEvent() {
        baseline()
        reader.scan = listOf(rcs(12, 1779668768000, "hi"))
        coordinator().runOnce()
        val before = db.capturedEventDao().count()
        // Same row re-read (a read-flag flip changes no body) across two broadcasts.
        coordinator().runOnce()
        coordinator().runOnce()
        assertEquals(before, db.capturedEventDao().count())
    }

    // --- First-run backfill policy ---

    @Test
    fun firstRun_backfillZero_emitsNothing_butSnapshotsEntireProvider() {
        val now = 1779700000000
        // Three pre-existing rows of varying age already in the provider.
        reader.scan = listOf(
            rcs(1, now - 30 * day, "old"),
            rcs(2, now - 2 * day, "recent"),
            rcs(3, now - 1 * day, "newest"),
        )
        val outcome = coordinator(backfillDays = 0, now = now).runOnce()

        // Forward-only: nothing emitted...
        assertEquals(0, outcome.newEvents)
        assertEquals(0, db.capturedEventDao().count())
        // ...but the baseline snapshot covers EVERY existing row.
        assertEquals(3, db.telephonySnapshotDao().count())
        assertTrue(outcome.firstRun)
    }

    @Test
    fun firstRun_fullSnapshot_meansLaterScanDoesNotReemitOldRows() {
        val now = 1779700000000
        val rows = listOf(rcs(1, now - 30 * day, "old"), rcs(2, now - 1 * day, "new"))
        reader.scan = rows
        coordinator(backfillDays = 0, now = now).runOnce()
        assertEquals(0, db.capturedEventDao().count())

        // A later reconciliation scan over the UNCHANGED provider must emit nothing -
        // the full baseline means the old rows are not seen as new.
        val scan = coordinator(backfillDays = 0, now = now + day).runOnce()
        assertEquals(0, scan.newEvents)
        assertEquals(0, db.capturedEventDao().count())
        assertFalse(scan.firstRun)
    }

    @Test
    fun firstRun_backfillWindow_emitsOnlyInWindowRows_butSnapshotsAll() {
        val now = 1779700000000
        reader.scan = listOf(
            rcs(1, now - 30 * day, "way old"),   // outside 7-day window
            rcs(2, now - 10 * day, "old"),        // outside
            rcs(3, now - 5 * day, "in window"),   // inside
            rcs(4, now - 1 * day, "recent"),      // inside
        )
        val outcome = coordinator(backfillDays = 7, now = now).runOnce()

        // Only the two in-window rows are emitted...
        assertEquals(2, outcome.newEvents)
        assertEquals(2, db.capturedEventDao().count())
        // ...but ALL four are snapshotted, so a later scan re-emits none of them.
        assertEquals(4, db.telephonySnapshotDao().count())

        reader.scan = reader.scan // unchanged
        val scan = coordinator(backfillDays = 7, now = now + day).runOnce()
        assertEquals(0, scan.newEvents)
        assertEquals(2, db.capturedEventDao().count())
    }

    @Test
    fun firstRun_emptyProvider_stillMarksFirstRunDone() {
        reader.scan = emptyList()
        val first = coordinator(backfillDays = 0).runOnce()
        assertTrue(first.firstRun)
        // A subsequent pass is NOT treated as first run even though the provider was
        // empty (distinguishes "never captured" from "genuinely empty provider").
        reader.scan = listOf(rcs(9, 1779700000000, "first real message"))
        val second = coordinator(backfillDays = 0).runOnce()
        assertFalse(second.firstRun)
        // The new message after baseline IS emitted (forward-only from the baseline).
        assertEquals(1, second.newEvents)
    }

    // --- Reconciliation scan (same pass, different trigger) ---

    @Test
    fun reconcile_onUnchangedProvider_isNoOp_onlyTouchesLastScannedAt() {
        reader.scan = listOf(rcs(20, 1779700000000, "hello"))
        // Establish baseline forward-only so nothing is emitted on first run.
        coordinator(backfillDays = 0, now = 1000).runOnce()
        assertEquals(0, db.capturedEventDao().count())

        val meta = db.archiverMetaDao()
        val afterFirst = meta.get("last_scanned_at")
        assertEquals("1000", afterFirst)

        // Re-run the SAME pass on unchanged provider state at a later time.
        val scan = coordinator(backfillDays = 0, now = 2000).runOnce()
        assertEquals(0, scan.newEvents)
        assertEquals(0, scan.deletes)
        assertEquals(0, db.capturedEventDao().count())
        // Only the marker advanced.
        assertEquals("2000", meta.get("last_scanned_at"))
    }

    @Test
    fun reconcile_catchesOfflineAdd_edit_andDelete_thePerBroadcastPathMissed() {
        val ts = 1779700000000
        // Baseline: one message present, forward-only so it is not emitted.
        reader.scan = listOf(rcs(30, ts, "original"))
        coordinator(backfillDays = 0).runOnce()
        assertEquals(0, db.capturedEventDao().count())

        // While the device was offline (no broadcasts processed), the provider
        // changed: a new message arrived, the new message was then edited (new row,
        // same thread+sender+ts, different body), and the baseline message #30 was
        // deleted. A single reconciliation scan must derive add + edit + delete.
        reader.scan = listOf(
            rcs(31, ts + 10, "added while offline"),
            rcs(32, ts + 10, "added while offline - EDITED"),
        )
        val scan = coordinator(backfillDays = 0).runOnce()

        val events = db.capturedEventDao().pendingUpload(100)
        val byType = events.groupBy { it.eventType }
        // The add (received) and its edit are both captured...
        assertEquals(1, byType["received"]?.size ?: 0)
        assertEquals(1, byType["edited"]?.size ?: 0)
        // ...and the offline delete of the baseline row is captured by absence.
        assertEquals(1, byType["deleted"]?.size ?: 0)
        assertEquals(1, scan.deletes)
    }

    // --- Contact-name enrichment ---

    @Test
    fun capture_enrichesContactNames_intoPersistedPayload() {
        baseline()
        reader.scan = listOf(rcs(12, 1779668768000, "hi"))
        val resolver = CountingResolver(
            mapOf("+15555550111" to "Jordan Rivera", "+15555550100" to "Me"),
        )
        coordinator(contactNameResolver = resolver).runOnce()

        val raw = db.capturedEventDao().pendingUpload(100).single().rawJson
        val names = org.json.JSONObject(raw).getJSONObject("contact_names")
        assertEquals("Jordan Rivera", names.getString("+15555550111"))
        assertEquals("Me", names.getString("+15555550100"))
    }

    @Test
    fun capture_cachesContactLookups_acrossEventsInOneRun() {
        baseline()
        // Two events in the SAME run sharing the same two addresses (and each event
        // repeats addresses across from + to + participants).
        reader.scan = listOf(
            rcs(12, 1779668768000, "hi"),
            rcs(13, 1779668768001, "there"),
        )
        val resolver = CountingResolver(
            mapOf("+15555550111" to "Jordan", "+15555550100" to "Me"),
        )
        coordinator(contactNameResolver = resolver).runOnce()

        // Despite two events each referencing both numbers several times, each
        // distinct address is queried exactly once for the whole run.
        assertEquals(
            listOf("+15555550111", "+15555550100"),
            resolver.calls.distinct(),
        )
        assertEquals(2, resolver.calls.size)
    }

    @Test
    fun capture_throwingResolver_yieldsEmptyContactNames_andDoesNotFail() {
        baseline()
        reader.scan = listOf(rcs(12, 1779668768000, "hi"))
        val throwing = ContactNameResolver { throw SecurityException("denied") }
        // The pass must complete and persist the event regardless.
        val outcome = coordinator(contactNameResolver = throwing).runOnce()
        assertEquals(1, outcome.newEvents)

        val raw = db.capturedEventDao().pendingUpload(100).single().rawJson
        val names = org.json.JSONObject(raw).getJSONObject("contact_names")
        assertEquals(0, names.length())
    }
}
