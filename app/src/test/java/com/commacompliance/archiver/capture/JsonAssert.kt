package com.commacompliance.archiver.capture

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals

/**
 * Asserts that the produced normalized payload matches the fixture on every FROZEN
 * key the capture layer is responsible for. Fixture payloads additionally carry
 * server-/annotation-only keys (e.g. rcs_group_token, content_subtype,
 * detected_by) the capture layer does not emit; those are not compared here.
 */
object JsonAssert {
    private val FROZEN_KEYS = listOf(
        "source", "provider_id", "thread_id", "thread_type", "direction",
        "timestamp_ms", "from", "to", "cc", "participants", "body",
        "attachments", "is_rcs", "creator",
    )

    fun matchesFrozen(expected: JSONObject, actual: JSONObject) {
        for (key in FROZEN_KEYS) {
            if (!expected.has(key)) continue
            val e = expected.get(key)
            val a = actual.get(key)
            when (e) {
                is JSONArray -> assertEquals("key=$key", e.toString(), (a as JSONArray).toString())
                else -> assertEquals("key=$key", e.toString(), a.toString())
            }
        }
    }
}
