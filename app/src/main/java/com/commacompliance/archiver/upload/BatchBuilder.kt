package com.commacompliance.archiver.upload

import com.commacompliance.archiver.data.CapturedEvent
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

/**
 * Builds the decrypted-plaintext batch the server expects inside the envelope:
 *
 *   { "batch_id": "<uuid>",
 *     "device_id": "<str>",
 *     "events": [ { "provider_id", "event_type", "event_ts" (epoch_ms),
 *                   "body_hash", "payload": { ...NormalizedEvent... } } ] }
 *
 * Each captured row already stores the normalized payload as a JSON string; it is
 * re-parsed (not re-stringified naively) so it nests as a JSON object, not a quoted
 * string. The server upserts on (worker, provider_id, event_type, event_ts,
 * body_hash) - all carried here at the top level of each event.
 */
object BatchBuilder {

    const val MAX_BATCH_EVENTS = 500

    fun build(
        deviceId: String,
        events: List<CapturedEvent>,
        batchId: String = UUID.randomUUID().toString(),
    ): String {
        require(events.size <= MAX_BATCH_EVENTS) { "batch exceeds $MAX_BATCH_EVENTS events" }
        val array = JSONArray()
        for (event in events) {
            // The stored payload must be a JSON object (the NormalizedEvent shape). Parse
            // and require an object so a corrupt/non-object row can never be sent as a
            // bare array/scalar `payload` and trip the server's plaintext_data guard.
            val payload = JSONTokener(event.rawJson).nextValue()
            require(payload is JSONObject) { "captured payload is not a JSON object: ${event.providerId}" }
            array.put(
                JSONObject().apply {
                    put("provider_id", event.providerId)
                    put("event_type", event.eventType)
                    put("event_ts", event.eventTs)
                    put("body_hash", event.bodyHash)
                    put("payload", payload)
                },
            )
        }
        return JSONObject().apply {
            put("batch_id", batchId)
            put("device_id", deviceId)
            put("events", array)
        }.toString()
    }
}
