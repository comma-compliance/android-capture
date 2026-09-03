package com.commacompliance.archiver.capture

import com.commacompliance.archiver.util.Hex
import java.security.MessageDigest

/**
 * Computes the body hash that anchors both the local idempotency key and the
 * server-side dedupe index. Both sides MUST derive it identically, so the rule is
 * frozen: lowercase hex SHA-256 of the normalized body string encoded as UTF-8.
 *
 * A null/empty body hashes to SHA-256 of the empty string
 * (`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`). Hashing
 * only the body - never status columns like `read` - is what makes read/delivery
 * status churn a natural no-op instead of a spurious edit.
 */
object BodyHash {
    fun of(body: String?): String {
        val bytes = (body ?: "").toByteArray(Charsets.UTF_8)
        return Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
