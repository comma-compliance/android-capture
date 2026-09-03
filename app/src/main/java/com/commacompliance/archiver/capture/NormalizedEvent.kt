package com.commacompliance.archiver.capture

import org.json.JSONArray
import org.json.JSONObject

/**
 * A single captured attachment referenced by a Telephony `content://mms/part` row
 * whose `_data` column is non-null (i.e. a binary payload, not inline text).
 *
 * The bytes are NOT copied here; only a stable reference (`partUri`) plus metadata
 * is recorded. The upload pipeline reads the bytes lazily via
 * `contentResolver.openInputStream(partUri)`.
 *
 * `contentHash` is the lowercase-hex SHA-256 of the PLAINTEXT attachment bytes -
 * the content-addressed key the server uses to link the uploaded binary to this
 * message. It is NOT computed during capture (hashing streams the bytes, which
 * must never happen inside the time-boxed capture window); it is filled in
 * out-of-band before the event becomes eligible for upload, so the message event's
 * `attachments[].content_hash` always matches the blob that is uploaded.
 * `status` mirrors the server's metadata: "pending" until the bytes are stored.
 */
data class Attachment(
    val partId: String,
    val partUri: String,
    val contentType: String,
    val filename: String?,
    val sizeBytes: Long?,
    val contentHash: String? = null,
    val status: String = STATUS_PENDING,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("part_id", partId)
        put("part_uri", partUri)
        put("content_type", contentType)
        put("filename", filename ?: JSONObject.NULL)
        put("size_bytes", sizeBytes ?: JSONObject.NULL)
        // content_hash is the server's link key; emit it only once known so a
        // not-yet-hashed attachment never claims a blank hash on the wire.
        if (contentHash != null) put("content_hash", contentHash)
        put("status", status)
    }

    companion object {
        const val STATUS_PENDING = "pending"
    }
}

enum class Source(val wire: String) {
    SMS("sms"),
    MMS("mms"),
    RCS("rcs"),
}

enum class ThreadType(val wire: String) {
    ONE_TO_ONE("one_to_one"),
    GROUP("group"),
}

enum class Direction(val wire: String) {
    INCOMING("incoming"),
    OUTGOING("outgoing"),
}

/**
 * The normalized, source-agnostic representation of a single message row read from
 * the Telephony provider. This is the canonical capture shape: one structure for
 * SMS, MMS, and RCS. It serializes to exactly the `payload` object the ingest
 * server expects, and it is the unit the classifier reasons about.
 *
 * `providerId` is table-namespaced (`sms:7` / `mms:23`) so the per-table monotonic
 * `_id` spaces can never collide in the idempotency key.
 */
data class NormalizedEvent(
    val source: Source,
    val providerId: String,
    val threadId: String,
    val threadType: ThreadType,
    val direction: Direction,
    val timestampMs: Long,
    val from: String?,
    val to: List<String>,
    val cc: List<String>,
    val participants: List<String>,
    val body: String,
    val attachments: List<Attachment>,
    val isRcs: Boolean,
    val creator: String?,
    /**
     * Saved contact display names keyed by the RAW address string as it appears in
     * `from` / `to` / `cc` / `participants` (byte-for-byte, NOT re-normalized) so
     * the server can look an address up directly. An address with no resolved name
     * is simply absent. Defaulted to empty so the pure normalizer never resolves
     * names; the [CaptureCoordinator] (where a ContentResolver is available)
     * enriches this map before serialization.
     */
    val contactNames: Map<String, String> = emptyMap(),
) {
    /**
     * The sender used for edit-correlation. For an outgoing message the provider
     * `addr` 137 row is the device itself; either way `from` is the authoritative
     * per-message sender.
     */
    val sender: String? get() = from

    fun toPayloadJson(): JSONObject = JSONObject().apply {
        put("source", source.wire)
        put("provider_id", providerId)
        put("thread_id", threadId)
        put("thread_type", threadType.wire)
        put("direction", direction.wire)
        put("timestamp_ms", timestampMs)
        put("from", from ?: JSONObject.NULL)
        put("to", JSONArray(to))
        put("cc", JSONArray(cc))
        put("participants", JSONArray(participants))
        put("body", body)
        put("attachments", JSONArray().apply { attachments.forEach { put(it.toJson()) } })
        put("is_rcs", isRcs)
        put("creator", creator ?: JSONObject.NULL)
        // Always emit the key for shape stability (matching how `to`/`cc` are always
        // present); an empty object means "no names resolved" and the server falls
        // back to the raw number.
        put("contact_names", JSONObject(contactNames.toMap()))
    }
}
