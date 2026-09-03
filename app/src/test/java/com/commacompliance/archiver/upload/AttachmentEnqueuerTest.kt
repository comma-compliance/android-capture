package com.commacompliance.archiver.upload

import com.commacompliance.archiver.data.CapturedEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest

/**
 * The out-of-band preparer: streams attachment bytes to compute the plaintext
 * content_hash, records a durable pending blob, patches the hash into the event
 * payload, and releases the event for upload - proving event/attachment
 * content_hash alignment and the never-drop policy when a source is missing.
 *
 * Also proves the completeness rule: an attachment whose bytes have not been shown
 * to have stopped changing is held back rather than archived as final, because a
 * `content://mms/part` row can be readable while its media is still arriving.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentEnqueuerTest {

    /** Test clock so the settle interval is exercised without real waiting. */
    private var now = 1_700_000_000_000L

    private fun enqueuer(
        eventDao: FakeCapturedEventDao,
        pendingDao: FakePendingAttachmentDao,
        source: FakeAttachmentSource,
    ) = AttachmentEnqueuer(eventDao, pendingDao, source, clock = { now })

    /**
     * [declaredSize] mirrors what capture read from the part's descriptor. Passing
     * the real byte length models media already fully written when it was captured
     * (the normal case); passing null models a descriptor the provider would not
     * report, which is what forces the settle check.
     *
     * [capturedAt] defaults to a full settle interval ago, because a matching
     * descriptor length only counts as evidence once it has held across real time.
     * Tests that care about a freshly-captured event pass their own value.
     */
    private fun eventWithAttachment(
        id: Long,
        partUri: String,
        declaredSize: Long?,
        contentType: String = "image/jpeg",
        capturedAt: Long = now - AttachmentEnqueuer.SETTLE_INTERVAL_MS,
    ): CapturedEvent {
        val payload = JSONObject().apply {
            put("source", "rcs")
            put("provider_id", "mms:$id")
            put("body", "see attached")
            put("attachments", org.json.JSONArray().put(attachmentJson(id, partUri, declaredSize, contentType)))
        }
        return CapturedEvent(
            id = id,
            providerId = "mms:$id",
            eventType = "received",
            eventTs = 1_700_000_000_000L + id,
            bodyHash = "h$id",
            threadId = "t",
            sender = "+1",
            rawJson = payload.toString(),
            readyForUpload = false,
            createdAt = capturedAt,
        )
    }

    private fun attachmentJson(
        id: Long,
        partUri: String,
        declaredSize: Long?,
        contentType: String = "image/jpeg",
    ): JSONObject = JSONObject().apply {
        put("part_id", "$id")
        put("part_uri", partUri)
        put("content_type", contentType)
        put("filename", "photo.jpg")
        put("size_bytes", declaredSize ?: JSONObject.NULL)
        put("status", "pending")
    }

    private fun releasedAttachment(eventDao: FakeCapturedEventDao, id: Long): JSONObject =
        JSONObject(eventDao.pendingUpload(10).first { it.id == id }.rawJson)
            .getJSONArray("attachments")
            .getJSONObject(0)

    private fun heldAttachment(eventDao: FakeCapturedEventDao, id: Long): JSONObject =
        JSONObject(eventDao.rawJsonOf(id)).getJSONArray("attachments").getJSONObject(0)

    @Test
    fun hashes_records_patches_and_releases_the_event() {
        val bytes = ByteArray(5000) { (it % 113).toByte() }
        val uri = "content://mms/part/1"
        val source = FakeAttachmentSource().apply { put(uri, bytes) }
        val eventDao = FakeCapturedEventDao(listOf(eventWithAttachment(1, uri, declaredSize = 5000)))
        val pendingDao = FakePendingAttachmentDao()

        enqueuer(eventDao, pendingDao, source).runOnce()

        val expectedHash = sha256Hex(bytes)
        // A durable pending blob was recorded with the streamed size + mime.
        val pending = pendingDao.all().single()
        assertEquals(expectedHash, pending.contentHash)
        assertEquals(uri, pending.partUri)
        assertEquals("image/jpeg", pending.mime)
        assertEquals(bytes.size.toLong(), pending.sizeBytes)
        assertFalse(pending.uploaded)

        // The event is now ready and its attachment carries the SAME content_hash -
        // the server links the uploaded blob to the message by this value.
        val released = eventDao.pendingUpload(10).single()
        assertTrue(released.readyForUpload)
        val att = releasedAttachment(eventDao, 1)
        assertEquals(expectedHash, att.getString("content_hash"))
        assertEquals("pending", att.getString("status"))
        assertEquals(bytes.size.toLong(), att.getLong("size_bytes"))
    }

    @Test
    fun missing_source_retains_the_event_not_ready_and_drops_nothing() {
        val uri = "content://mms/part/9"
        val source = FakeAttachmentSource() // no entry -> open() returns null
        val eventDao = FakeCapturedEventDao(listOf(eventWithAttachment(9, uri, declaredSize = 10)))
        val pendingDao = FakePendingAttachmentDao()

        enqueuer(eventDao, pendingDao, source).runOnce()

        // Nothing recorded, event NOT released (stays out of the upload queue) and is
        // still in the hashing queue for a later retry - never dropped, never uploaded
        // without its hash.
        assertEquals(0, pendingDao.all().size)
        assertTrue(eventDao.pendingUpload(10).isEmpty())
        assertEquals(1, eventDao.pendingAttachmentHashing(10).size)
    }

    @Test
    fun re_running_is_idempotent_no_duplicate_pending_rows() {
        val bytes = "hello".toByteArray()
        val uri = "content://mms/part/2"
        val source = FakeAttachmentSource().apply { put(uri, bytes) }
        // Two events referencing the SAME part_uri (e.g. the same image captured
        // twice) must record only one pending blob (unique content_hash+part_uri).
        val eventDao = FakeCapturedEventDao(
            listOf(
                eventWithAttachment(2, uri, declaredSize = 5),
                eventWithAttachment(3, uri, declaredSize = 5),
            ),
        )
        val pendingDao = FakePendingAttachmentDao()

        enqueuer(eventDao, pendingDao, source).runOnce()
        enqueuer(eventDao, pendingDao, source).runOnce() // re-run

        assertEquals(1, pendingDao.all().size)
        assertEquals(2, eventDao.pendingUpload(10).size) // both events released
    }

    @Test
    fun event_with_empty_attachment_array_is_released_immediately() {
        val payload = JSONObject().apply {
            put("body", "no media")
            put("attachments", org.json.JSONArray())
        }
        val event = CapturedEvent(
            id = 1, providerId = "sms:1", eventType = "received", eventTs = 1L,
            bodyHash = "h", threadId = "t", sender = "+1",
            rawJson = payload.toString(), readyForUpload = false,
        )
        val eventDao = FakeCapturedEventDao(listOf(event))
        val pendingDao = FakePendingAttachmentDao()

        enqueuer(eventDao, pendingDao, FakeAttachmentSource()).runOnce()

        assertEquals(0, pendingDao.all().size)
        assertEquals(1, eventDao.pendingUpload(10).size)
    }

    /**
     * The capture-time descriptor length is itself an earlier observation. When the
     * streamed bytes match it, the file has not changed since the message was seen,
     * so the attachment is released on the FIRST pass - the completeness rule must
     * not cost latency on the ordinary path.
     */
    @Test
    fun sizeUnchangedSinceCapture_releasesOnTheFirstPassWithNoRecheck() {
        val bytes = ByteArray(1200) { 7 }
        val uri = "content://mms/part/11"
        val source = FakeAttachmentSource().apply { put(uri, bytes) }
        val eventDao = FakeCapturedEventDao(listOf(eventWithAttachment(11, uri, declaredSize = 1200)))
        val pendingDao = FakePendingAttachmentDao()

        val deferred = enqueuer(eventDao, pendingDao, source).runOnce()

        assertFalse("no re-check should be requested", deferred)
        assertEquals(1, eventDao.pendingUpload(10).size)
        assertEquals(1, source.opens) // streamed exactly once
    }

    /**
     * The descriptor length only counts as evidence once it has held across real
     * time. A part that is readable and STILL GROWING can present a first stream
     * whose length happens to equal what capture recorded moments earlier - a
     * download between writes looks exactly like a finished one. Releasing on that
     * would archive the current prefix as final, so a recently-captured event takes
     * the settle path even when the sizes agree.
     */
    @Test
    fun sizeMatchesButCaptureWasMomentsAgo_isStillHeldForRecheck() {
        val prefix = ByteArray(1000) { 4 }
        val uri = "content://mms/part/18"
        val source = FakeAttachmentSource().apply { put(uri, prefix) }
        val eventDao = FakeCapturedEventDao(
            listOf(eventWithAttachment(18, uri, declaredSize = 1000, capturedAt = now - 1)),
        )
        val pendingDao = FakePendingAttachmentDao()

        assertTrue(enqueuer(eventDao, pendingDao, source).runOnce())
        assertTrue(eventDao.pendingUpload(10).isEmpty())
        assertEquals(0, pendingDao.all().size)

        // The download was merely paused between writes; it resumes.
        now += AttachmentEnqueuer.SETTLE_INTERVAL_MS
        val whole = ByteArray(9000) { 4 }
        source.put(uri, whole)
        assertTrue(enqueuer(eventDao, pendingDao, source).runOnce())
        assertTrue(eventDao.pendingUpload(10).isEmpty())

        now += AttachmentEnqueuer.SETTLE_INTERVAL_MS
        assertFalse(enqueuer(eventDao, pendingDao, source).runOnce())

        // The complete file was archived, not the 1000-byte prefix that matched the
        // capture-time descriptor.
        assertEquals(sha256Hex(whole), pendingDao.all().single().contentHash)
        assertEquals(9000L, releasedAttachment(eventDao, 18).getLong("size_bytes"))
    }

    /**
     * With no descriptor length to compare against, the bytes are unproven: the
     * event is held back and re-read only after the settle interval, and released
     * once two reads separated in time agree.
     */
    @Test
    fun unknownDeclaredSize_isHeldBackUntilTwoIdenticalReadsSeparatedInTime() {
        val bytes = ByteArray(64) { 3 }
        val uri = "content://mms/part/12"
        val source = FakeAttachmentSource().apply { put(uri, bytes) }
        val eventDao = FakeCapturedEventDao(listOf(eventWithAttachment(12, uri, declaredSize = null)))
        val pendingDao = FakePendingAttachmentDao()

        val deferred = enqueuer(eventDao, pendingDao, source).runOnce()

        assertTrue("caller must be told to schedule a re-check", deferred)
        assertTrue(eventDao.pendingUpload(10).isEmpty())
        // No pending blob yet: an unproven hash must never become an upload.
        assertEquals(0, pendingDao.all().size)
        // The observation is persisted so the NEXT pass has something to compare to.
        assertEquals(sha256Hex(bytes), heldAttachment(eventDao, 12).getJSONObject("_settle").getString("hash"))

        now += AttachmentEnqueuer.SETTLE_INTERVAL_MS
        assertFalse(enqueuer(eventDao, pendingDao, source).runOnce())

        assertEquals(1, eventDao.pendingUpload(10).size)
        assertEquals(sha256Hex(bytes), pendingDao.all().single().contentHash)
    }

    /**
     * The finding this guards: bytes readable but still arriving. Every read while
     * the file grows must leave the event unreleased, so a partial file is never
     * hashed and archived as the final content.
     */
    @Test
    fun bytesStillGrowing_areNeverArchivedAsFinal() {
        val uri = "content://mms/part/13"
        val source = FakeAttachmentSource().apply { put(uri, ByteArray(1000) { 1 }) }
        val eventDao = FakeCapturedEventDao(listOf(eventWithAttachment(13, uri, declaredSize = null)))
        val pendingDao = FakePendingAttachmentDao()

        assertTrue(enqueuer(eventDao, pendingDao, source).runOnce())

        // The download advances between passes.
        now += AttachmentEnqueuer.SETTLE_INTERVAL_MS
        val whole = ByteArray(4000) { 1 }
        source.put(uri, whole)
        assertTrue(enqueuer(eventDao, pendingDao, source).runOnce())

        // Nothing was recorded from either partial read.
        assertEquals(0, pendingDao.all().size)
        assertTrue(eventDao.pendingUpload(10).isEmpty())

        // Download finished; the bytes now hold still across the interval.
        now += AttachmentEnqueuer.SETTLE_INTERVAL_MS
        assertFalse(enqueuer(eventDao, pendingDao, source).runOnce())

        // The archived hash is the COMPLETE file, not either truncated prefix.
        assertEquals(sha256Hex(whole), pendingDao.all().single().contentHash)
        assertEquals(sha256Hex(whole), releasedAttachment(eventDao, 13).getString("content_hash"))
        assertEquals(4000L, releasedAttachment(eventDao, 13).getLong("size_bytes"))
    }

    /**
     * Two reads microseconds apart are no evidence at all. A re-check that arrives
     * before the interval has elapsed must not release the event, so a burst of
     * passes cannot rush the verdict.
     */
    @Test
    fun identicalReReadInsideTheSettleInterval_doesNotRelease() {
        val uri = "content://mms/part/14"
        val source = FakeAttachmentSource().apply { put(uri, ByteArray(10) { 2 }) }
        val eventDao = FakeCapturedEventDao(listOf(eventWithAttachment(14, uri, declaredSize = null)))
        val pendingDao = FakePendingAttachmentDao()

        enqueuer(eventDao, pendingDao, source).runOnce()

        // Hammer it with re-reads that all land inside the interval. None may
        // release the event, and none may spend part of the observation budget -
        // that budget exists for bytes that keep CHANGING.
        repeat(10) {
            now += (AttachmentEnqueuer.SETTLE_INTERVAL_MS - 1) / 10
            assertTrue(enqueuer(eventDao, pendingDao, source).runOnce())
            assertTrue(eventDao.pendingUpload(10).isEmpty())
        }

        // Crossing the interval is enough - the wait accrued from the FIRST read
        // rather than restarting on every identical re-read.
        now += AttachmentEnqueuer.SETTLE_INTERVAL_MS
        assertFalse(enqueuer(eventDao, pendingDao, source).runOnce())
        assertEquals(1, eventDao.pendingUpload(10).size)
        assertEquals(sha256Hex(ByteArray(10) { 2 }), pendingDao.all().single().contentHash)
    }

    /**
     * Bytes that never stop changing must not mean media that is never archived at
     * all - the whole EVENT is held, so the message record would be lost too, not
     * just its media. The bound is elapsed time rather than a count of reads, so a
     * large file arriving slowly over a poor link keeps its chance to finish.
     */
    @Test
    fun bytesThatNeverSettle_areAcceptedOnlyAfterTheMaximumWait() {
        val uri = "content://mms/part/15"
        val source = FakeAttachmentSource().apply { put(uri, ByteArray(100) { 0 }) }
        val eventDao = FakeCapturedEventDao(listOf(eventWithAttachment(15, uri, declaredSize = null)))
        val pendingDao = FakePendingAttachmentDao()
        val start = now

        // Keep the bytes moving, as a download in progress would. Right up to the
        // bound the event stays held - nothing partial is archived along the way.
        var changes = 0
        while (now - start < AttachmentEnqueuer.MAX_SETTLE_WAIT_MS - AttachmentEnqueuer.SETTLE_INTERVAL_MS) {
            assertTrue(enqueuer(eventDao, pendingDao, source).runOnce())
            assertTrue("held while the bytes keep changing", eventDao.pendingUpload(10).isEmpty())
            now += AttachmentEnqueuer.SETTLE_INTERVAL_MS
            changes++
            source.put(uri, ByteArray(100 + changes) { 0 })
        }
        assertTrue("the bound must tolerate a long slow download, not a handful of reads", changes > 50)

        // Crossing it accepts what is readable rather than holding the event forever.
        now = start + AttachmentEnqueuer.MAX_SETTLE_WAIT_MS
        assertFalse(enqueuer(eventDao, pendingDao, source).runOnce())
        assertEquals(1, eventDao.pendingUpload(10).size)
    }

    /** The private settle marker must never survive onto a released payload. */
    @Test
    fun releasedPayload_carriesNoSettleMarker() {
        val uri = "content://mms/part/16"
        val source = FakeAttachmentSource().apply { put(uri, ByteArray(10) { 5 }) }
        val eventDao = FakeCapturedEventDao(listOf(eventWithAttachment(16, uri, declaredSize = null)))
        val pendingDao = FakePendingAttachmentDao()

        enqueuer(eventDao, pendingDao, source).runOnce()
        now += AttachmentEnqueuer.SETTLE_INTERVAL_MS
        enqueuer(eventDao, pendingDao, source).runOnce()

        val att = releasedAttachment(eventDao, 16)
        assertNull(att.optJSONObject("_settle"))
        assertFalse(eventDao.pendingUpload(10).single().rawJson.contains("_settle"))
    }

    /**
     * A pass that hashes one attachment but cannot read its sibling must persist the
     * work it did. Before the payload was written back on the not-ready path this
     * was silently redone every pass, re-streaming bytes that were already hashed.
     */
    @Test
    fun partialProgressIsPersisted_soHashedBytesAreNotReStreamed() {
        val readable = "content://mms/part/17a"
        val late = "content://mms/part/17b"
        val payload = JSONObject().apply {
            put("provider_id", "mms:17")
            put("body", "two files")
            put(
                "attachments",
                org.json.JSONArray()
                    .put(attachmentJson(17, readable, declaredSize = 8))
                    .put(attachmentJson(18, late, declaredSize = 4)),
            )
        }
        val event = CapturedEvent(
            id = 17, providerId = "mms:17", eventType = "received", eventTs = 1L,
            bodyHash = "h", threadId = "t", sender = "+1",
            rawJson = payload.toString(), readyForUpload = false,
            createdAt = now - AttachmentEnqueuer.SETTLE_INTERVAL_MS,
        )
        val source = FakeAttachmentSource().apply { put(readable, "12345678".toByteArray()) }
        val eventDao = FakeCapturedEventDao(listOf(event))
        val pendingDao = FakePendingAttachmentDao()

        enqueuer(eventDao, pendingDao, source).runOnce()

        assertTrue(eventDao.pendingUpload(10).isEmpty())
        assertEquals(1, source.opens)
        // The first attachment's hash survived the held-back write.
        val held = JSONObject(eventDao.rawJsonOf(17)).getJSONArray("attachments")
        assertEquals(sha256Hex("12345678".toByteArray()), held.getJSONObject(0).getString("content_hash"))

        // The late part arrives; only IT is streamed on the second pass.
        source.put(late, "abcd".toByteArray())
        enqueuer(eventDao, pendingDao, source).runOnce()

        assertEquals(1, eventDao.pendingUpload(10).size)
        assertEquals(2, source.opens)
        assertEquals(2, pendingDao.all().size)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
