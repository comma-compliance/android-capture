package com.commacompliance.archiver.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Bounded post-upload retention against a real in-memory Room store: only uploaded
 * rows past the window are pruned, un-uploaded rows and recent uploaded rows survive,
 * and the same applies to completed pending-attachment rows. Run on the framework
 * factory (Robolectric cannot load the SQLCipher native; the query semantics are the
 * same either way).
 */
@RunWith(RobolectricTestRunner::class)
class QueueRetentionTest {

    private lateinit var db: ArchiverDatabase
    private val now = 1_800_000_000_000L
    private val old = now - QueueRetention.RETENTION_MS - 1
    private val recent = now - 1

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

    private fun event(id: Long, uploaded: Boolean, createdAt: Long) = CapturedEvent(
        id = id,
        providerId = "sms:$id",
        eventType = "received",
        eventTs = createdAt,
        bodyHash = "hash$id",
        threadId = "1",
        sender = "+15555550100",
        rawJson = """{"body":"secret-$id"}""",
        uploaded = uploaded,
        createdAt = createdAt,
    )

    private fun attachment(id: Long, uploaded: Boolean, createdAt: Long) = PendingAttachment(
        id = id,
        contentHash = "ahash$id",
        partUri = "content://mms/part/$id",
        mime = "image/jpeg",
        sizeBytes = 1024,
        uploaded = uploaded,
        createdAt = createdAt,
    )

    @Test
    fun prunes_only_uploaded_events_past_the_window() {
        val dao = db.capturedEventDao()
        dao.insert(event(1, uploaded = true, createdAt = old)) // pruned
        dao.insert(event(2, uploaded = true, createdAt = recent)) // kept: recent
        dao.insert(event(3, uploaded = false, createdAt = old)) // kept: not uploaded
        dao.insert(event(4, uploaded = false, createdAt = recent)) // kept

        val removed = QueueRetention.prune(db, now)

        assertEquals(1, removed)
        assertEquals(3, dao.count())
        // The still-pending old row survives - retention never drops un-uploaded data.
        assertTrue(dao.pendingUpload(10).any { it.id == 3L })
    }

    @Test
    fun prunes_only_completed_attachments_past_the_window() {
        val dao = db.pendingAttachmentDao()
        dao.insert(attachment(1, uploaded = true, createdAt = old)) // pruned
        dao.insert(attachment(2, uploaded = true, createdAt = recent)) // kept: recent
        dao.insert(attachment(3, uploaded = false, createdAt = old)) // kept: not uploaded

        val removed = db.pendingAttachmentDao().pruneUploadedOlderThan(now - QueueRetention.RETENTION_MS)

        assertEquals(1, removed)
        assertEquals(1, dao.pendingCount()) // only the un-uploaded one remains pending
    }

    @Test
    fun prune_is_a_no_op_when_nothing_is_eligible() {
        db.capturedEventDao().insert(event(1, uploaded = true, createdAt = recent))
        db.pendingAttachmentDao().insert(attachment(1, uploaded = false, createdAt = old))

        assertEquals(0, QueueRetention.prune(db, now))
        assertEquals(1, db.capturedEventDao().count())
    }

    @Test
    fun prune_counts_events_and_attachments_together() {
        db.capturedEventDao().insert(event(1, uploaded = true, createdAt = old))
        db.capturedEventDao().insert(event(2, uploaded = true, createdAt = old))
        db.pendingAttachmentDao().insert(attachment(1, uploaded = true, createdAt = old))

        assertEquals(3, QueueRetention.prune(db, now))
        assertFalse(db.capturedEventDao().pendingUpload(10).any())
    }

    @Test
    fun retains_uploaded_old_events_still_live_in_the_provider_snapshot() {
        val dao = db.capturedEventDao()
        // Event 1's message is gone from the provider (no snapshot row): prunable.
        dao.insert(event(1, uploaded = true, createdAt = old))
        // Event 2's message is still in the provider (snapshot row present): an edit
        // could still arrive and must correlate against this row, so it must survive.
        dao.insert(event(2, uploaded = true, createdAt = old))
        db.telephonySnapshotDao().upsert(
            TelephonySnapshot(
                providerId = "sms:2",
                bodyHash = "hash2",
                timestampMs = old,
                threadId = "1",
                sender = "+15555550100",
                rawJson = """{"body":"secret-2"}""",
            ),
        )

        val removed = QueueRetention.prune(db, now)

        assertEquals(1, removed)
        // The still-live message's captured row is kept regardless of age.
        assertTrue(dao.findByCorrelation("1", "+15555550100", old).any { it.providerId == "sms:2" })
    }
}
