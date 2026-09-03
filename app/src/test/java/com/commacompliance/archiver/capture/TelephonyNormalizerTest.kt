package com.commacompliance.archiver.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives the normalizer with synthetic provider rows reconstructed from the
 * observed schema and asserts the produced payload equals the golden fixture's
 * payload on every frozen key. Each fixture row shape is derived from
 * telephony-schema.md (sms date=ms/type; mms date=seconds/msg_box/ct_cls;
 * part text vs _data; addr 137/151/130).
 */
@RunWith(RobolectricTestRunner::class)
class TelephonyNormalizerTest {

    @Test
    fun smsReceived_matchesFixture() {
        val ev = TelephonyNormalizer.fromSms(
            SmsRow(
                id = 2,
                threadId = "2",
                address = "+15555550111",
                dateMs = 1779656022221,
                type = SmsConstants.TYPE_INBOX,
                body = "Oh hey, is this coming in as the archive?",
                creator = "com.google.android.apps.messaging",
            ),
            deviceNumber = "+15555550100",
        )
        JsonAssert.matchesFrozen(Fixtures.payload("sms_received"), ev.toPayloadJson())
    }

    @Test
    fun smsSent_matchesFixture() {
        // Outbound SMS: the addr peer is the recipient; the device (its known
        // number) is the sender.
        val ev = TelephonyNormalizer.fromSms(
            SmsRow(
                id = 3,
                threadId = "2",
                address = "+15555550111",
                dateMs = 1779656130345,
                type = SmsConstants.TYPE_SENT,
                body = "Back atcha",
                creator = "com.google.android.apps.messaging",
            ),
            deviceNumber = "+15555550100",
        )
        assertEquals(Direction.OUTGOING, ev.direction)
        JsonAssert.matchesFrozen(Fixtures.payload("sms_sent"), ev.toPayloadJson())
    }

    @Test
    fun smsLong_singleRow_matchesFixture() {
        val fx = Fixtures.payload("sms_long")
        val ev = TelephonyNormalizer.fromSms(
            SmsRow(
                id = 11,
                threadId = "2",
                address = "+15555550111",
                dateMs = 1779672220821,
                type = SmsConstants.TYPE_INBOX,
                body = fx.getString("body"),
                creator = "com.google.android.apps.messaging",
            ),
            deviceNumber = "+15555550100",
        )
        JsonAssert.matchesFrozen(fx, ev.toPayloadJson())
    }

    @Test
    fun mmsReceivedImage_secondsToMs_andAttachment() {
        val ev = TelephonyNormalizer.fromMms(
            MmsRow(
                id = 4, threadId = "2", dateSeconds = 1779663295, msgBox = 1,
                ctType = "application/vnd.wap.multipart.related", ctClass = null,
                creator = "com.google.android.apps.messaging",
            ),
            parts = listOf(
                MmsPart(99, "text/plain", null, null, "Check out this photo", null, null, 0),
                MmsPart(5, "image/jpeg", "image000001.jpg", "image000001.jpg", null,
                    "/data/.../PART_x", 643025, 1),
            ),
            addrs = listOf(
                MmsAddr("+15555550111", PduAddrType.FROM),
                MmsAddr("+15555550100", PduAddrType.TO),
            ),
            thread = null,
        )
        // date in seconds normalized to ms
        assertEquals(1779663295000L, ev.timestampMs)
        assertEquals(Source.MMS, ev.source)
        JsonAssert.matchesFrozen(Fixtures.payload("mms_received"), ev.toPayloadJson())
    }

    @Test
    fun mmsGroup_ccMember_makesGroup() {
        val ev = TelephonyNormalizer.fromMms(
            MmsRow(8, "3", 1779663378, 1, "application/vnd.wap.multipart.related", null,
                "com.google.android.apps.messaging"),
            parts = listOf(
                MmsPart(50, "text/plain", null, null, "Group pic for everyone", null, null, 0),
                MmsPart(10, "image/jpeg", "image000001.jpg", "image000001.jpg", null,
                    "/data/.../PART_y", 417178, 1),
            ),
            addrs = listOf(
                MmsAddr("+15555550111", PduAddrType.FROM),
                MmsAddr("+15555550100", PduAddrType.TO),
                MmsAddr("+15555550122", PduAddrType.CC),
            ),
            thread = ThreadRow("3", 1, "2 3"),
        )
        assertEquals(ThreadType.GROUP, ev.threadType)
        JsonAssert.matchesFrozen(Fixtures.payload("mms_group_received"), ev.toPayloadJson())
    }

    @Test
    fun rcsReceivedText_ctCls135_isRcs() {
        val ev = TelephonyNormalizer.fromMms(
            MmsRow(12, "2", 1779668768, 1, "text/plain", 135,
                "com.google.android.apps.messaging"),
            parts = listOf(
                MmsPart(60, "text/plain", null, null,
                    "This shows as rcs chat, hopefully it works", null, null, 0),
            ),
            addrs = listOf(
                MmsAddr("+15555550111", PduAddrType.FROM),
                MmsAddr("+15555550100", PduAddrType.TO),
            ),
            thread = null,
        )
        assertEquals(Source.RCS, ev.source)
        JsonAssert.matchesFrozen(Fixtures.payload("rcs_received"), ev.toPayloadJson())
    }

    @Test
    fun rcsGroup_membershipFromAddrNotThreadToken() {
        // Thread recipient_ids is the opaque @rcs.google.com token (type=0);
        // membership must come from the addr rows.
        val ev = TelephonyNormalizer.fromMms(
            MmsRow(15, "5", 1779669200, 1, "text/plain", 135,
                "com.google.android.apps.messaging"),
            parts = listOf(
                MmsPart(70, "text/plain", null, null,
                    "Another group to ignore. This is test #3", null, null, 0),
            ),
            addrs = listOf(
                MmsAddr("+15555550111", PduAddrType.FROM),
                MmsAddr("+15555550100", PduAddrType.TO),
                MmsAddr("+15555550122", PduAddrType.CC),
            ),
            thread = ThreadRow("5", 0, "aaaabbbbccccddddeeeeffffgggghhhh@rcs.example.invalid"),
        )
        assertEquals(ThreadType.GROUP, ev.threadType)
        JsonAssert.matchesFrozen(Fixtures.payload("rcs_group"), ev.toPayloadJson())
    }

    @Test
    fun rcsAttachmentImage_captionPartIsBody_imagePartIsAttachment() {
        val ev = TelephonyNormalizer.fromMms(
            MmsRow(14, "2", 1779669102, 1, "image/jpeg", 135,
                "com.google.android.apps.messaging"),
            parts = listOf(
                MmsPart(80, "text/plain", "body", "body", "#2 on test list", null, null, 0),
                MmsPart(21, "image/jpeg", "8589636563775843298.jpg",
                    "8589636563775843298.jpg", null, "/data/.../PART_z", 319048, 1),
            ),
            addrs = listOf(
                MmsAddr("+15555550111", PduAddrType.FROM),
                MmsAddr("+15555550100", PduAddrType.TO),
            ),
            thread = null,
        )
        JsonAssert.matchesFrozen(Fixtures.payload("rcs_attachment_image"), ev.toPayloadJson())
    }

    @Test
    fun rcsAttachmentVoice_emptyBody_hashesEmptyString() {
        val ev = TelephonyNormalizer.fromMms(
            MmsRow(35, "2", 1779671975, 1, "audio/mp4", 135,
                "com.google.android.apps.messaging"),
            parts = listOf(
                MmsPart(40, "audio/mp4", "3523027917471784401.m4a",
                    "3523027917471784401.m4a", null, "/data/.../PART_a", 26977, 0),
            ),
            addrs = listOf(
                MmsAddr("+15555550111", PduAddrType.FROM),
                MmsAddr("+15555550100", PduAddrType.TO),
            ),
            thread = null,
        )
        assertEquals("", ev.body)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            BodyHash.of(ev.body),
        )
        JsonAssert.matchesFrozen(Fixtures.payload("rcs_attachment_voice"), ev.toPayloadJson())
    }

    @Test
    fun rcsAttachmentVcard_emptyBody() {
        val ev = TelephonyNormalizer.fromMms(
            MmsRow(38, "2", 1779672020, 1, "text/x-vcard", 135,
                "com.google.android.apps.messaging"),
            parts = listOf(
                MmsPart(42, "text/x-vcard", "2610591480060212474.vcf",
                    "2610591480060212474.vcf", null, "/data/.../PART_v", 7928, 0),
            ),
            addrs = listOf(
                MmsAddr("+15555550111", PduAddrType.FROM),
                MmsAddr("+15555550100", PduAddrType.TO),
            ),
            thread = null,
        )
        JsonAssert.matchesFrozen(Fixtures.payload("rcs_attachment_vcard"), ev.toPayloadJson())
    }

    @Test
    fun rcsAttachmentVideo_matchesFixture() {
        val ev = TelephonyNormalizer.fromMms(
            MmsRow(36, "2", 1779671992, 1, "video/mp4", 135,
                "com.google.android.apps.messaging"),
            parts = listOf(
                MmsPart(41, "video/mp4", "7880574017863692589.mp4",
                    "7880574017863692589.mp4", null, "/data/.../PART_m", 2552210, 0),
            ),
            addrs = listOf(
                MmsAddr("+15555550111", PduAddrType.FROM),
                MmsAddr("+15555550100", PduAddrType.TO),
            ),
            thread = null,
        )
        JsonAssert.matchesFrozen(Fixtures.payload("rcs_attachment_video"), ev.toPayloadJson())
    }

    @Test
    fun rcsLocation_textPlainUrlIsBody_notAttachment() {
        val fx = Fixtures.payload("rcs_location")
        val ev = TelephonyNormalizer.fromMms(
            MmsRow(39, "2", 1779672030, 1,
                "application/vnd.gsma.rcspushlocation+xml", 135,
                "com.google.android.apps.messaging"),
            // The Maps URL is a text/plain part (no _data) -> body, not attachment.
            parts = listOf(
                MmsPart(90, "text/plain", null, null, fx.getString("body"), null, null, 0),
            ),
            addrs = listOf(
                MmsAddr("+15555550111", PduAddrType.FROM),
                MmsAddr("+15555550100", PduAddrType.TO),
            ),
            thread = null,
        )
        assertEquals(0, ev.attachments.size)
        JsonAssert.matchesFrozen(fx, ev.toPayloadJson())
    }

    @Test
    fun rcsReaction_synthesizedText_isOrdinaryReceived() {
        val fx = Fixtures.payload("rcs_reaction")
        val ev = TelephonyNormalizer.fromMms(
            MmsRow(19, "2", 1779669913, 1, "text/plain", 135,
                "com.google.android.apps.messaging"),
            parts = listOf(
                MmsPart(95, "text/plain", null, null, fx.getString("body"), null, null, 0),
            ),
            addrs = listOf(
                MmsAddr("+15555550111", PduAddrType.FROM),
                MmsAddr("+15555550100", PduAddrType.TO),
            ),
            thread = null,
        )
        JsonAssert.matchesFrozen(fx, ev.toPayloadJson())
    }

    @Test
    fun mmsSent_outboundGroup_matchesFixtureStructure() {
        val ev = TelephonyNormalizer.fromMms(
            MmsRow(11, "3", 1779665243, 2,
                "application/vnd.wap.multipart.related", null,
                "com.google.android.apps.messaging"),
            parts = listOf(
                MmsPart(100, "text/plain", null, null, "Response with photo", null, null, 0),
                MmsPart(15, "image/jpeg", "image000001.jpg", "image000001.jpg", null,
                    "/data/.../PART_s", 199878, 1),
                MmsPart(101, "application/smil", null, null, "<smil/>", null, null, 2),
            ),
            addrs = listOf(
                MmsAddr("+15555550100", PduAddrType.FROM),
                MmsAddr("+15555550111", PduAddrType.TO),
                MmsAddr("+15555550122", PduAddrType.TO),
            ),
            thread = ThreadRow("3", 1, "2 3"),
        )
        assertEquals(Direction.OUTGOING, ev.direction)
        assertEquals(ThreadType.GROUP, ev.threadType)
        // SMIL part must not leak into body or attachments.
        assertEquals("Response with photo", ev.body)
        assertEquals(1, ev.attachments.size)
        JsonAssert.matchesFrozen(Fixtures.payload("mms_sent"), ev.toPayloadJson())
    }
}
