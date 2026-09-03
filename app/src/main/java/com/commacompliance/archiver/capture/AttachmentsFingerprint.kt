package com.commacompliance.archiver.capture

import com.commacompliance.archiver.util.Hex
import java.security.MessageDigest

/**
 * Fingerprints the ATTACHMENT SET of a message row, so the snapshot-diff can see a
 * change that [BodyHash] structurally cannot.
 *
 * Why this exists: `body_hash` is SHA-256 of the message TEXT only, and its
 * derivation is frozen because the ingest server's idempotency index is built on
 * it. MMS and RCS routinely persist the message row FIRST and fill the media in
 * afterwards under the SAME `content://mms` `_id` - the row appears with no binary
 * part, and the picture/video/voice note lands seconds or minutes later. Because
 * the text never changes, the body hash never changes, and the classifier's
 * "same body_hash on a known provider_id is status churn" rule discards the pass.
 * The media was therefore never captured at all. This hash gives that comparison
 * an attachment dimension without touching the frozen body-hash contract.
 *
 * What it covers, and deliberately does not:
 * - Covers `partId`, `contentType` and `filename`. A [NormalizedEvent] only carries
 *   an [Attachment] for a part whose provider `_data` path is non-null, so an
 *   attachment's mere PRESENCE in the set already means its binary exists. The
 *   fingerprint therefore flips exactly once, when the media lands.
 * - Excludes `sizeBytes`. The size is read from the part's descriptor and can climb
 *   while bytes are still being written, which would produce a stream of successive
 *   "changed" verdicts for one arriving file. Only the first would survive anyway:
 *   the local and server idempotency keys are both
 *   (provider_id, event_type, event_ts, body_hash), all four unchanged between
 *   those passes, so every later one collapses onto the first and is silently
 *   dropped. Fingerprinting identity rather than size is what makes the single
 *   event we do get be the one describing the COMPLETE attachment set.
 * - Excludes `contentHash` and `status`. Both are filled in out-of-band after
 *   capture (see `AttachmentEnqueuer`), so including them would make a snapshot
 *   written at capture time differ from the same unchanged row on the next pass and
 *   emit a spurious edit.
 *
 * Entries are sorted before hashing so provider row order can never alter the
 * result, and each field is length-prefixed so that ("ab","c") and ("a","bc")
 * cannot collide. An empty set hashes to the SHA-256 of the empty string, matching
 * [BodyHash]'s convention for an absent body.
 */
object AttachmentsFingerprint {

    fun of(attachments: List<Attachment>): String {
        val canonical = attachments
            .map { "${it.partId.length}:${it.partId}" +
                "|${it.contentType.length}:${it.contentType}" +
                "|${(it.filename ?: "").length}:${it.filename ?: ""}" }
            .sorted()
            .joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return Hex.encode(digest)
    }
}
