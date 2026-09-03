package com.commacompliance.archiver.capture

import com.commacompliance.archiver.data.TelephonySnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventClassifierTest {

    /** Captured-events history backing the edit-correlation lookup. */
    private val history = mutableListOf<PriorCapture>()

    private fun classifier() = EventClassifier { threadId, sender, ts ->
        history.filter { it.threadId == threadId && it.sender == sender && it.timestampMs == ts }
    }

    private fun rcs(
        id: Long,
        thread: String,
        sender: String,
        ts: Long,
        body: String,
        direction: Direction = Direction.INCOMING,
        attachments: List<Attachment> = emptyList(),
    ) = NormalizedEvent(
        source = Source.RCS,
        providerId = "mms:$id",
        threadId = thread,
        threadType = ThreadType.ONE_TO_ONE,
        direction = direction,
        timestampMs = ts,
        from = sender,
        to = listOf("+15555550100"),
        cc = emptyList(),
        participants = listOf(sender, "+15555550100"),
        body = body,
        attachments = attachments,
        isRcs = true,
        creator = "com.google.android.apps.messaging",
    )

    /** An MMS/RCS binary part as the normalizer emits it (only parts with `_data`). */
    private fun attachment(
        partId: String,
        contentType: String = "image/jpeg",
        filename: String? = "photo.jpg",
        sizeBytes: Long? = 319048,
    ) = Attachment(
        partId = partId,
        partUri = "content://mms/part/$partId",
        contentType = contentType,
        filename = filename,
        sizeBytes = sizeBytes,
    )

    /**
     * A prior snapshot in POST-migration steady state: the attachment fingerprint is
     * populated, as it is for any row snapshotted by a build that has the column.
     * The pre-migration NULL case is covered explicitly by its own test.
     */
    private fun snapshotOf(ev: NormalizedEvent) = TelephonySnapshot(
        providerId = ev.providerId,
        bodyHash = BodyHash.of(ev.body),
        timestampMs = ev.timestampMs,
        threadId = ev.threadId,
        sender = ev.sender,
        rawJson = ev.toPayloadJson().toString(),
        attachmentsHash = AttachmentsFingerprint.of(ev.attachments),
    )

    @Test
    fun newRow_incoming_isReceived() {
        val scan = listOf(rcs(12, "2", "+15555550111", 1779668768000, "hi"))
        val result = classifier().classify(scan, emptyList())
        assertEquals(1, result.events.size)
        assertEquals(EventType.RECEIVED, result.events[0].eventType)
        assertEquals(1, result.snapshotUpserts.size)
    }

    @Test
    fun newRow_outgoing_isSent() {
        val scan = listOf(rcs(13, "2", "+15555550100", 1779669024000, "reply",
            direction = Direction.OUTGOING))
        val result = classifier().classify(scan, emptyList())
        assertEquals(EventType.SENT, result.events[0].eventType)
    }

    @Test
    fun reaction_isOrdinaryReceived() {
        val scan = listOf(rcs(19, "2", "+15555550111", 1779669913000,
            "❤️ to \"Sounds good, see you then\""))
        val result = classifier().classify(scan, emptyList())
        assertEquals(EventType.RECEIVED, result.events[0].eventType)
    }

    @Test
    fun sameRowReadAgain_dedupesToNoNewEvent() {
        // First pass produces the event and snapshot.
        val original = rcs(12, "2", "+15555550111", 1779668768000, "hi")
        val first = classifier().classify(listOf(original), emptyList())
        // Second pass: same provider_id present in snapshot, unchanged body.
        val second = classifier().classify(listOf(original), first.snapshotUpserts)
        assertTrue("re-read must yield no new event", second.events.isEmpty())
        // An unchanged row is neither re-upserted (no write amplification) nor deleted.
        assertTrue(second.snapshotUpserts.isEmpty())
        assertTrue(second.snapshotDeletes.isEmpty())
    }

    @Test
    fun statusChurn_readFlagFlip_isNoOp() {
        // Read-flag flips do not change the body, so body_hash is identical and the
        // same provider_id is present in both -> no event.
        val ev = rcs(12, "2", "+15555550111", 1779668768000, "hi")
        val priorSnap = listOf(snapshotOf(ev))
        val result = classifier().classify(listOf(ev), priorSnap)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun lateFilledMedia_sameProviderId_emitsEditedCarryingTheAttachment() {
        // The ordinary MMS/RCS arrival pattern: the row is persisted first with only
        // its caption, and the picture lands under the SAME content://mms _id a
        // moment later. The text never changes, so body_hash is identical in both
        // passes - this is exactly the case the body-only diff could not see.
        val textOnly = rcs(14, "2", "+15555550111", 1779669102000, "#2 on test list")
        val withMedia = rcs(
            14, "2", "+15555550111", 1779669102000, "#2 on test list",
            attachments = listOf(attachment("21")),
        )
        assertEquals(BodyHash.of(textOnly.body), BodyHash.of(withMedia.body))

        val result = classifier().classify(listOf(withMedia), listOf(snapshotOf(textOnly)))

        assertEquals(1, result.events.size)
        val event = result.events[0]
        assertEquals(EventType.EDITED, event.eventType)
        // The raw-event id is namespaced under the real provider id so the event is
        // still traceable to its row, but is distinct from it.
        assertTrue(event.providerId.startsWith("mms:14#att:"))

        val payload = JSONObject(event.payloadJson)
        // The PAYLOAD keeps the true provider id - that is what the server resolves
        // the message from - even though the raw-event id carries a discriminator.
        assertEquals("mms:14", payload.getString("provider_id"))
        // Correlates to ITSELF: the media completed on the existing row, so the
        // server must update that message rather than insert a second one.
        assertEquals("mms:14", payload.getString("edits_provider_id"))
        val attachments = payload.getJSONArray("attachments")
        assertEquals(1, attachments.length())
        assertEquals("21", attachments.getJSONObject(0).getString("part_id"))
        assertEquals("image/jpeg", attachments.getJSONObject(0).getString("content_type"))
    }

    @Test
    fun lateFilledMedia_onceCaptured_furtherPassesAreSilent() {
        // The follow-up pass must NOT re-emit: a second EDITED event would carry the
        // same (provider_id, event_type, event_ts, body_hash) and be dropped by both
        // idempotency keys anyway, so emitting it is pure churn.
        val withMedia = rcs(
            14, "2", "+15555550111", 1779669102000, "#2 on test list",
            attachments = listOf(attachment("21")),
        )
        val result = classifier().classify(listOf(withMedia), listOf(snapshotOf(withMedia)))

        assertTrue(result.events.isEmpty())
        assertTrue(result.snapshotUpserts.isEmpty())
    }

    @Test
    fun growingAttachmentSize_doesNotEmit() {
        // size_bytes is read from the part descriptor and can climb while the file is
        // still being written. Only the FIRST change would ever survive the
        // idempotency key, so fingerprinting size would let a partially-written state
        // win and lock out the complete one. Identity, not size, is the change key.
        val small = rcs(
            14, "2", "+15555550111", 1779669102000, "caption",
            attachments = listOf(attachment("21", sizeBytes = 1024)),
        )
        val grown = rcs(
            14, "2", "+15555550111", 1779669102000, "caption",
            attachments = listOf(attachment("21", sizeBytes = 319048)),
        )
        val result = classifier().classify(listOf(grown), listOf(snapshotOf(small)))

        assertTrue(result.events.isEmpty())
    }

    @Test
    fun nullPriorFingerprint_backfillsSilentlyWithoutEmitting() {
        // A snapshot row written before the attachments_hash column existed. There is
        // nothing to diff against, so the row must be backfilled WITHOUT emitting -
        // otherwise the first pass of an upgraded build would emit an edit for every
        // media-bearing message already on the device.
        val withMedia = rcs(
            14, "2", "+15555550111", 1779669102000, "#2 on test list",
            attachments = listOf(attachment("21")),
        )
        val preMigration = snapshotOf(withMedia).copy(attachmentsHash = null)

        val result = classifier().classify(listOf(withMedia), listOf(preMigration))

        assertTrue(result.events.isEmpty())
        assertEquals(1, result.snapshotUpserts.size)
        assertEquals(
            AttachmentsFingerprint.of(withMedia.attachments),
            result.snapshotUpserts[0].attachmentsHash,
        )
    }

    @Test
    fun secondAttachmentArrivingLater_isNotDedupedAwayByTheFirst() {
        // An RCS message with two file transfers completing in DIFFERENT scans. Both
        // idempotency keys are (provider_id, event_type, event_ts, body_hash), and for
        // an attachment-only change the last three are identical between the two
        // passes. If the raw-event id were the bare provider id, the second event
        // would collide with the first and be dropped - and because the snapshot
        // advances either way, the second file would be lost permanently.
        val first = rcs(
            14, "2", "+15555550111", 1779669102000, "two files",
            attachments = listOf(attachment("21")),
        )
        val second = rcs(
            14, "2", "+15555550111", 1779669102000, "two files",
            attachments = listOf(attachment("21"), attachment("22", filename = "clip.mp4")),
        )

        val pass1 = classifier().classify(listOf(first), listOf(snapshotOf(rcs(14, "2", "+15555550111", 1779669102000, "two files"))))
        val pass2 = classifier().classify(listOf(second), listOf(snapshotOf(first)))

        assertEquals(1, pass1.events.size)
        assertEquals(1, pass2.events.size)
        // Distinct raw-event ids are the whole point: identical ids would collide on
        // the unique index and the second event would never persist.
        assertNotEquals(pass1.events[0].providerId, pass2.events[0].providerId)
        // And the second event carries the COMPLETE set, not just the new part, so a
        // single delivered event is sufficient to describe the final state.
        val attachments = JSONObject(pass2.events[0].payloadJson).getJSONArray("attachments")
        assertEquals(2, attachments.length())
    }

    @Test
    fun rescanningAnUnchangedAttachmentSet_reusesTheSameEventId() {
        // The discriminator must be DETERMINISTIC, not a counter or a timestamp:
        // re-deriving the same id for an unchanged state is what lets the idempotency
        // key correctly absorb a repeated scan instead of emitting duplicates forever.
        val textOnly = rcs(14, "2", "+15555550111", 1779669102000, "caption")
        val withMedia = rcs(
            14, "2", "+15555550111", 1779669102000, "caption",
            attachments = listOf(attachment("21")),
        )
        val a = classifier().classify(listOf(withMedia), listOf(snapshotOf(textOnly)))
        val b = classifier().classify(listOf(withMedia), listOf(snapshotOf(textOnly)))

        assertEquals(a.events[0].providerId, b.events[0].providerId)
    }

    @Test
    fun removedAttachment_alsoCountsAsAChange() {
        // The inverse direction: a part that disappears from the set is a change to
        // the archived record too, and must not be silently absorbed as churn.
        val withMedia = rcs(
            14, "2", "+15555550111", 1779669102000, "caption",
            attachments = listOf(attachment("21")),
        )
        val withoutMedia = rcs(14, "2", "+15555550111", 1779669102000, "caption")

        val result = classifier().classify(listOf(withoutMedia), listOf(snapshotOf(withMedia)))

        assertEquals(1, result.events.size)
        assertEquals(EventType.EDITED, result.events[0].eventType)
    }

    @Test
    fun deletedRow_absentNow_emitsCachedPayload() {
        val ev = rcs(12, "2", "+15555550111", 1779668768000, "to be deleted")
        val priorSnap = listOf(snapshotOf(ev))
        // Current scan no longer contains mms:12.
        val result = classifier().classify(emptyList(), priorSnap)
        assertEquals(1, result.events.size)
        val deleted = result.events[0]
        assertEquals(EventType.DELETED, deleted.eventType)
        assertEquals("mms:12", deleted.providerId)
        assertEquals(listOf("mms:12"), result.snapshotDeletes)
        // The emitted payload is the cached last-known one.
        val payload = JSONObject(deleted.payloadJson)
        assertEquals("to be deleted", payload.getString("body"))
    }

    @Test
    fun edit_newRowInheritingTimestamp_correlatesToPriorCapture() {
        // Original was captured earlier (durable history), original row no longer
        // matters; the edit arrives as a new row sharing thread+sender+timestamp
        // with a different body.
        val ts = 1779669371000
        history += PriorCapture("mms:16", "2", "+15555550111", ts, BodyHash.of("Edit this #4"))
        val priorSnap = listOf(
            TelephonySnapshot("mms:16", BodyHash.of("Edit this #4"), ts, "2", "+15555550111", "{}"),
        )
        // Current scan: original still present + the new edit row.
        val scan = listOf(
            rcs(16, "2", "+15555550111", ts, "Edit this #4"),
            rcs(17, "2", "+15555550111", ts, "Edit this #4 - EDITED"),
        )
        val result = classifier().classify(scan, priorSnap)
        val edit = result.events.single { it.providerId == "mms:17" }
        assertEquals(EventType.EDITED, edit.eventType)
        val payload = JSONObject(edit.payloadJson)
        assertEquals("mms:16", payload.getString("edits_provider_id"))
        assertEquals("Edit this #4 - EDITED", payload.getString("body"))
    }

    @Test
    fun edit_originalAndEditInSameScan_coldStart() {
        // No prior history or snapshot (cold start): both rows are new in one scan.
        // The original (lower id, same timestamp) is processed first and becomes
        // in-run history the edit can correlate against.
        val ts = 1779669371000
        val scan = listOf(
            rcs(17, "2", "+15555550111", ts, "Edit this #4 - EDITED"),
            rcs(16, "2", "+15555550111", ts, "Edit this #4"),
        )
        val result = classifier().classify(scan, emptyList())
        val original = result.events.single { it.providerId == "mms:16" }
        val edit = result.events.single { it.providerId == "mms:17" }
        assertEquals(EventType.RECEIVED, original.eventType)
        assertEquals(EventType.EDITED, edit.eventType)
        assertEquals("mms:16", JSONObject(edit.payloadJson).getString("edits_provider_id"))
    }

    @Test
    fun sameTimestampSameSender_sameBody_isNotEdit() {
        // A genuine re-read (same body, same thread/sender/ts but only one row) is
        // not an edit; with identical body_hash it is just received.
        val ts = 1779669371000
        history += PriorCapture("mms:16", "2", "+15555550111", ts, BodyHash.of("same body"))
        val scan = listOf(rcs(99, "2", "+15555550111", ts, "same body"))
        val result = classifier().classify(scan, emptyList())
        // body_hash matches the prior capture -> NOT an edit; treated as received.
        assertEquals(EventType.RECEIVED, result.events[0].eventType)
        assertTrue(!JSONObject(result.events[0].payloadJson).has("edits_provider_id"))
    }
}
