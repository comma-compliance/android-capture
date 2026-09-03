package com.commacompliance.archiver.upload

import com.commacompliance.archiver.data.CapturedEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BatchBuilderTest {

    private fun event(id: Long, providerId: String, payload: String) = CapturedEvent(
        id = id,
        providerId = providerId,
        eventType = "received",
        eventTs = 1_700_000_000_000L + id,
        bodyHash = "hash$id",
        threadId = "t1",
        sender = "+15551234567",
        rawJson = payload,
    )

    @Test
    fun builds_the_exact_plaintext_batch_shape() {
        val json = BatchBuilder.build(
            deviceId = "amdev_42",
            events = listOf(event(1, "sms:1", """{"source":"sms","body":"hi"}""")),
            batchId = "batch-uuid",
        )
        val obj = JSONObject(json)
        assertEquals("batch-uuid", obj.getString("batch_id"))
        assertEquals("amdev_42", obj.getString("device_id"))

        val evt = obj.getJSONArray("events").getJSONObject(0)
        assertEquals(setOf("provider_id", "event_type", "event_ts", "body_hash", "payload"), evt.keys().asSequence().toSet())
        assertEquals("sms:1", evt.getString("provider_id"))
        assertEquals("received", evt.getString("event_type"))
        assertEquals(1_700_000_000_001L, evt.getLong("event_ts"))
        assertEquals("hash1", evt.getString("body_hash"))
        // payload nests as a JSON object, not a quoted string.
        assertEquals("sms", evt.getJSONObject("payload").getString("source"))
    }

    @Test
    fun rejects_a_batch_over_the_max() {
        val tooMany = (1L..(BatchBuilder.MAX_BATCH_EVENTS + 1)).map { event(it, "sms:$it", "{}") }
        assertThrows(IllegalArgumentException::class.java) {
            BatchBuilder.build("amdev_1", tooMany)
        }
    }

    @Test
    fun accepts_a_batch_at_the_max() {
        val exactly = (1L..BatchBuilder.MAX_BATCH_EVENTS.toLong()).map { event(it, "sms:$it", "{}") }
        val obj = JSONObject(BatchBuilder.build("amdev_1", exactly))
        assertEquals(BatchBuilder.MAX_BATCH_EVENTS, obj.getJSONArray("events").length())
    }

    @Test
    fun max_batch_size_matches_the_wire_contract() {
        assertTrue(BatchBuilder.MAX_BATCH_EVENTS == 500)
    }

    @Test
    fun rejects_a_payload_that_is_not_a_json_object() {
        assertThrows(IllegalArgumentException::class.java) {
            BatchBuilder.build("amdev_1", listOf(event(1, "sms:1", """[1,2,3]""")))
        }
    }
}
