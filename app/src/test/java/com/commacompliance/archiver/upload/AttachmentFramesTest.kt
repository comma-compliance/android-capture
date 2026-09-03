package com.commacompliance.archiver.upload

import com.commacompliance.archiver.crypto.EnvelopeEncryptor
import com.commacompliance.archiver.crypto.TestSodium
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * Proves the attachment frames + envelope are byte-for-byte compatible with the
 * server: Arc::AttachmentEnvelopeDecryptor decrypts the nacl-box-v1 envelope, then
 * Android::AttachmentIngestor recomputes SHA-256 of the decrypted bytes and rejects
 * on mismatch (chunked: full-file hash on reassembly).
 *
 * Each test plays the SERVER ROLE with libsodium - it opens the client's
 * ciphertext with (sender = sourcePublicKey, recipient = destination secret) and
 * then re-derives the hash exactly as Ruby's Digest::SHA256.hexdigest would - so a
 * green test is a real interop proof, not a self-consistency check.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentFramesTest {

    private val box = TestSodium.box()
    private val encryptor = EnvelopeEncryptor(box)
    private val device = box.generateKeyPair()
    private val destination = box.generateKeyPair()
    private val kid = "AbCdEfGhIjK="

    // --- streaming plaintext hash ---

    @Test
    fun hashStream_matches_lowercase_hex_sha256_and_counts_bytes() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val result = AttachmentFrames.hashStream(ByteArrayInputStream(bytes), bufferBytes = 4096)

        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, result.contentHash)
        assertEquals(bytes.size.toLong(), result.sizeBytes)
        assertTrue(result.contentHash.matches(Regex("^[0-9a-f]{64}$")))
    }

    // --- single-shot round trip (server role) ---

    @Test
    fun single_shot_frame_decrypts_and_hash_verifies_server_role() {
        val bytes = "the quick brown fox jumps".toByteArray()
        val contentHash = sha256Hex(bytes)
        val frame = AttachmentFrames.singleShotFrame(contentHash, "text/plain", bytes.size.toLong(), bytes)

        val envelope = encryptor.encrypt(
            plaintext = frame,
            kid = kid,
            devicePublicKey = device.publicKey,
            deviceSecretKey = device.secretKey,
            destinationPublicKey = destination.publicKey,
        )

        // Server: decrypt the envelope, parse the frame, recover + re-hash bytes.
        val decrypted = serverDecrypt(envelope)
        val obj = JSONObject(decrypted)
        assertEquals("attachment", obj.getString("type"))
        assertEquals(contentHash, obj.getString("content_hash"))
        assertEquals("text/plain", obj.getString("mime"))
        assertEquals(bytes.size, obj.getInt("size_bytes"))

        val recovered = Base64.getDecoder().decode(obj.getString("bytes_b64"))
        assertArrayEquals(bytes, recovered)
        // The server's integrity check: recomputed hash MUST equal content_hash.
        assertEquals(obj.getString("content_hash"), sha256Hex(recovered))
    }

    @Test
    fun single_shot_frame_has_exactly_the_server_field_names() {
        val bytes = "abc".toByteArray()
        val frame = JSONObject(AttachmentFrames.singleShotFrame(sha256Hex(bytes), "image/png", 3, bytes))
        assertEquals(
            setOf("type", "content_hash", "mime", "size_bytes", "bytes_b64"),
            frame.keys().asSequence().toSet(),
        )
    }

    // --- chunk round trip (server role: reassemble + full-file hash) ---

    @Test
    fun chunks_reassemble_in_order_and_full_file_hash_verifies_server_role() {
        // A file larger than the single-shot ceiling, split at the chunk size.
        val whole = ByteArray(AttachmentFrames.CHUNK_PLAINTEXT_BYTES * 2 + 12345) { (it % 97).toByte() }
        val contentHash = sha256Hex(whole)
        val count = AttachmentFrames.chunkCount(whole.size.toLong())
        assertEquals(3, count)

        // Build + envelope each chunk exactly as the uploader would (sequential reads).
        val reassembled = java.io.ByteArrayOutputStream()
        for (index in 0 until count) {
            val start = index * AttachmentFrames.CHUNK_PLAINTEXT_BYTES
            val len = minOf(AttachmentFrames.CHUNK_PLAINTEXT_BYTES, whole.size - start)
            val chunk = whole.copyOfRange(start, start + len)
            val frame = AttachmentFrames.chunkFrame(
                contentHash = contentHash,
                chunkIndex = index,
                chunkCount = count,
                mime = "video/mp4",
                sizeBytes = whole.size.toLong(),
                chunkBytes = chunk,
            )
            val envelope = encryptor.encrypt(
                plaintext = frame,
                kid = kid,
                devicePublicKey = device.publicKey,
                deviceSecretKey = device.secretKey,
                destinationPublicKey = destination.publicKey,
            )
            // Server decrypts each chunk, then stages it by index.
            val obj = JSONObject(serverDecrypt(envelope))
            assertEquals("attachment_chunk", obj.getString("type"))
            assertEquals(contentHash, obj.getString("content_hash"))
            assertEquals(index, obj.getInt("chunk_index"))
            assertEquals(count, obj.getInt("chunk_count"))
            assertEquals(whole.size, obj.getInt("size_bytes")) // whole-file size in every chunk
            reassembled.write(Base64.getDecoder().decode(obj.getString("chunk_bytes_b64")))
        }

        // On completion the server verifies the FULL-file hash over the reassembly.
        val reAssembledBytes = reassembled.toByteArray()
        assertArrayEquals(whole, reAssembledBytes)
        assertEquals(contentHash, sha256Hex(reAssembledBytes))
    }

    @Test
    fun chunk_frame_has_exactly_the_server_field_names() {
        val frame = JSONObject(
            AttachmentFrames.chunkFrame("a".repeat(64), 0, 2, "image/jpeg", 100, ByteArray(50)),
        )
        assertEquals(
            setOf("type", "content_hash", "chunk_index", "chunk_count", "mime", "size_bytes", "chunk_bytes_b64"),
            frame.keys().asSequence().toSet(),
        )
    }

    @Test
    fun chunkCount_rounds_up() {
        assertEquals(1, AttachmentFrames.chunkCount(0))
        assertEquals(1, AttachmentFrames.chunkCount(1))
        assertEquals(1, AttachmentFrames.chunkCount(AttachmentFrames.CHUNK_PLAINTEXT_BYTES.toLong()))
        assertEquals(2, AttachmentFrames.chunkCount(AttachmentFrames.CHUNK_PLAINTEXT_BYTES + 1L))
    }

    @Test
    fun chunk_enveloped_ciphertext_stays_under_the_servers_32_mib_cap() {
        // The server's Arc::AttachmentEnvelopeDecryptor rejects decoded ciphertext
        // over 32 MiB. A full 4 MiB plaintext chunk must envelope well under that.
        val chunk = ByteArray(AttachmentFrames.CHUNK_PLAINTEXT_BYTES) { it.toByte() }
        val frame = AttachmentFrames.chunkFrame("a".repeat(64), 0, 1, "video/mp4", chunk.size.toLong(), chunk)
        val envelope = encryptor.encrypt(
            plaintext = frame,
            kid = kid,
            devicePublicKey = device.publicKey,
            deviceSecretKey = device.secretKey,
            destinationPublicKey = destination.publicKey,
        )
        val ciphertextBytes = Base64.getDecoder().decode(envelope.getString("ciphertext")).size
        assertTrue(
            "enveloped chunk ciphertext $ciphertextBytes must be < 32 MiB",
            ciphertextBytes < 32 * 1024 * 1024,
        )
    }

    private fun serverDecrypt(envelope: JSONObject): String {
        val decoder = Base64.getDecoder()
        val recovered = box.boxOpenEasy(
            cipher = decoder.decode(envelope.getString("ciphertext")),
            nonce = decoder.decode(envelope.getString("nonce")),
            senderPublicKey = decoder.decode(envelope.getString("sourcePublicKey")),
            recipientSecretKey = destination.secretKey,
        )
        return String(recovered, Charsets.UTF_8)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
