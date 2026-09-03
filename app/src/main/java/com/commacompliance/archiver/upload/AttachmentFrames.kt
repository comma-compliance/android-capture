package com.commacompliance.archiver.upload

import com.commacompliance.archiver.util.Hex
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * Builds the domain-separated attachment frames the ingest server decrypts with
 * `Arc::AttachmentEnvelopeDecryptor` + `Android::AttachmentIngestor`. The frame is
 * the PLAINTEXT JSON object placed inside the `nacl-box-v1` envelope; its `type`
 * field is the domain separator that stops a message-batch blob being replayed at
 * the attachment endpoint (and vice versa).
 *
 * The two wire shapes are frozen and MUST be produced exactly:
 *
 *   single-shot  {type:"attachment",       content_hash, mime, size_bytes, bytes_b64}
 *   chunk        {type:"attachment_chunk", content_hash, chunk_index, chunk_count,
 *                                          mime, size_bytes, chunk_bytes_b64}
 *
 * `content_hash` is the lowercase-hex SHA-256 of the WHOLE PLAINTEXT file (the
 * server recomputes it after decrypt; for chunks it verifies over the reassembled
 * file). `size_bytes` is the whole-file size in both shapes. Bytes are RFC 4648
 * base64 WITH padding, NO line wrapping (the server decodes with strict_decode64).
 */
object AttachmentFrames {

    const val TYPE_ATTACHMENT = "attachment"
    const val TYPE_ATTACHMENT_CHUNK = "attachment_chunk"

    private const val KIB = 1024
    private const val MIB = 1024 * 1024

    /**
     * The server's published single-shot ceiling (`PUBLISHED_THRESHOLD_BYTES`):
     * files at or under this size go in one frame, larger files MUST chunk.
     */
    const val SINGLE_SHOT_MAX_BYTES = 8L * MIB

    /**
     * Plaintext bytes per chunk. The enveloped wire size grows ~16/9 (≈1.78x) of
     * the raw chunk: chunk_bytes_b64 inside the frame is ~4/3, then the whole
     * frame is nacl-box'd (+16 byte MAC) and the ciphertext base64'd in the
     * envelope (~4/3 again). At 4 MiB that is ~7.1 MiB of ciphertext-b64, far
     * under the server's 32 MiB decoded-ciphertext cap, with headroom for the
     * JSON framing and bounded per-chunk memory.
     */
    const val CHUNK_PLAINTEXT_BYTES = 4 * MIB

    /** Streaming read buffer for [hashStream]; bounds peak memory while hashing. */
    private const val HASH_BUFFER_BYTES = 64 * KIB

    /**
     * Stream [input] through SHA-256 in [bufferBytes]-sized reads so the whole
     * file is NEVER held in memory, returning the lowercase-hex digest (the
     * server's `content_hash`) and the exact byte count read. Does not close the
     * stream; the caller owns its lifecycle.
     */
    fun hashStream(input: InputStream, bufferBytes: Int = HASH_BUFFER_BYTES): HashResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferBytes)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            total += read
        }
        return HashResult(Hex.encode(digest.digest()), total)
    }

    data class HashResult(val contentHash: String, val sizeBytes: Long)

    /** A single-shot frame carrying the entire file's bytes. */
    fun singleShotFrame(contentHash: String, mime: String, sizeBytes: Long, bytes: ByteArray): String {
        require(bytes.size.toLong() == sizeBytes) { "single-shot byte count must equal size_bytes" }
        return JSONObject().apply {
            put("type", TYPE_ATTACHMENT)
            put("content_hash", contentHash)
            put("mime", mime)
            put("size_bytes", sizeBytes)
            put("bytes_b64", Base64.getEncoder().encodeToString(bytes))
        }.toString()
    }

    /** One chunk frame; carries the whole-file content_hash + size and this chunk's bytes. */
    fun chunkFrame(
        contentHash: String,
        chunkIndex: Int,
        chunkCount: Int,
        mime: String,
        sizeBytes: Long,
        chunkBytes: ByteArray,
        chunkLength: Int = chunkBytes.size,
    ): String {
        require(chunkIndex in 0 until chunkCount) { "chunk_index out of range" }
        return JSONObject().apply {
            put("type", TYPE_ATTACHMENT_CHUNK)
            put("content_hash", contentHash)
            put("chunk_index", chunkIndex)
            put("chunk_count", chunkCount)
            put("mime", mime)
            put("size_bytes", sizeBytes)
            put("chunk_bytes_b64", Base64.getEncoder().encodeToString(chunkBytes.copyOf(chunkLength)))
        }.toString()
    }

    /** Number of [CHUNK_PLAINTEXT_BYTES] chunks a file of [sizeBytes] splits into. */
    fun chunkCount(sizeBytes: Long): Int {
        if (sizeBytes <= 0) return 1
        return ((sizeBytes + CHUNK_PLAINTEXT_BYTES - 1) / CHUNK_PLAINTEXT_BYTES).toInt()
    }
}
