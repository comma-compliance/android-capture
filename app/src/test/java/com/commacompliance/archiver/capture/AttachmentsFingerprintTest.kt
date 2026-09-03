package com.commacompliance.archiver.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AttachmentsFingerprintTest {

    private fun attachment(
        partId: String,
        contentType: String = "image/jpeg",
        filename: String? = "photo.jpg",
        sizeBytes: Long? = 1024,
        contentHash: String? = null,
        status: String = Attachment.STATUS_PENDING,
    ) = Attachment(
        partId = partId,
        partUri = "content://mms/part/$partId",
        contentType = contentType,
        filename = filename,
        sizeBytes = sizeBytes,
        contentHash = contentHash,
        status = status,
    )

    @Test
    fun emptySet_matchesTheEmptyStringDigest() {
        // Same convention BodyHash uses for an absent body, so "no attachments" has a
        // stable, non-null value rather than a special case at every call site.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            AttachmentsFingerprint.of(emptyList()),
        )
    }

    @Test
    fun mediaArriving_changesTheFingerprint() {
        assertNotEquals(
            AttachmentsFingerprint.of(emptyList()),
            AttachmentsFingerprint.of(listOf(attachment("21"))),
        )
    }

    @Test
    fun providerRowOrder_doesNotAffectTheResult() {
        // The parts cursor makes no ordering guarantee across passes; an order flip
        // must not read as a content change.
        val a = attachment("21")
        val b = attachment("22", contentType = "video/mp4", filename = "clip.mp4")
        assertEquals(
            AttachmentsFingerprint.of(listOf(a, b)),
            AttachmentsFingerprint.of(listOf(b, a)),
        )
    }

    @Test
    fun sizeIsExcluded_soAGrowingFileIsNotAChange() {
        assertEquals(
            AttachmentsFingerprint.of(listOf(attachment("21", sizeBytes = 1))),
            AttachmentsFingerprint.of(listOf(attachment("21", sizeBytes = 999999))),
        )
    }

    @Test
    fun contentHashAndStatusAreExcluded_theyAreFilledInAfterCapture() {
        // AttachmentEnqueuer patches these in out-of-band once the bytes are hashed.
        // Including them would make an unchanged row differ from its own snapshot on
        // the next pass and emit a phantom edit.
        assertEquals(
            AttachmentsFingerprint.of(listOf(attachment("21"))),
            AttachmentsFingerprint.of(
                listOf(attachment("21", contentHash = "abc123", status = "linked")),
            ),
        )
    }

    @Test
    fun fieldsAreLengthPrefixed_soAdjacentFieldsCannotCollide() {
        // Without length prefixing, ("ab","c") and ("a","bc") would serialize to the
        // same bytes and two genuinely different attachment sets would tie.
        assertNotEquals(
            AttachmentsFingerprint.of(listOf(attachment("ab", contentType = "c", filename = null))),
            AttachmentsFingerprint.of(listOf(attachment("a", contentType = "bc", filename = null))),
        )
    }

    @Test
    fun differingPartIds_differ() {
        assertNotEquals(
            AttachmentsFingerprint.of(listOf(attachment("21"))),
            AttachmentsFingerprint.of(listOf(attachment("22"))),
        )
    }
}
