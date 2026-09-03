package com.commacompliance.archiver.util

/**
 * Lowercase-hex encoding of raw bytes - the single source of truth for the hex
 * idiom that anchors every digest the server re-derives (body_hash,
 * device_pubkey_fingerprint, attachment content_hash). Centralized so the three
 * call sites cannot drift; the server matches on the exact lowercase-hex form.
 */
object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    private const val BYTE_MASK = 0xFF
    private const val LOW_NIBBLE_MASK = 0x0F
    private const val NIBBLE_BITS = 4

    /** Lowercase-hex SHA-256-style digest string of [bytes]. */
    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and BYTE_MASK
            out.append(DIGITS[v ushr NIBBLE_BITS])
            out.append(DIGITS[v and LOW_NIBBLE_MASK])
        }
        return out.toString()
    }
}
