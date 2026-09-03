package com.commacompliance.archiver.upload

import androidx.test.core.app.ApplicationProvider
import com.commacompliance.archiver.FakeSharedPreferences
import com.commacompliance.archiver.config.ConfigProvider
import com.commacompliance.archiver.crypto.EnvelopeEncryptor
import com.commacompliance.archiver.crypto.KeyManager
import com.commacompliance.archiver.crypto.LazySodiumNaclBox
import com.commacompliance.archiver.crypto.TestSodium
import com.commacompliance.archiver.data.PendingAttachment
import com.commacompliance.archiver.enroll.EnrollmentClient
import com.commacompliance.archiver.enroll.EnrollmentManager
import com.commacompliance.archiver.enroll.EnrollmentStore
import com.commacompliance.archiver.net.FakeHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class AttachmentUploaderTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val box: LazySodiumNaclBox = TestSodium.box()
    private val keyManager = KeyManager(context, box) { FakeSharedPreferences() }
    private val store = EnrollmentStore(context) { FakeSharedPreferences() }
    private val destination = box.generateKeyPair()

    private class StubConfig : ConfigProvider(ApplicationProvider.getApplicationContext()) {
        override fun current() = ManagedConfig("https://ingest.example.com", "tok-1")
    }

    private fun enrollBody(token: String): String = JSONObject().apply {
        put("archive_token", token)
        put("device_id", "amdev_77")
        put("destination_public_key", Base64.getEncoder().encodeToString(destination.publicKey))
        put("kid", "AbCdEfGhIjK=")
    }.toString()

    private fun beginBody(): String = JSONObject().apply {
        put("challenge_id", "ch_1")
        put("challenge", Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        put("expires_at", "2026-01-01T00:00:00Z")
    }.toString()

    // Scripts the two-step signed enroll handshake (begin -> challenge, complete ->
    // provision) the manager runs before the first upload. Two POSTs per enroll.
    private fun enqueueEnroll(http: FakeHttpClient, token: String) {
        http.enqueue(201, beginBody())
        http.enqueue(200, enrollBody(token))
    }

    private fun uploader(
        dao: FakePendingAttachmentDao,
        http: FakeHttpClient,
        source: FakeAttachmentSource,
    ): AttachmentUploader {
        val config = StubConfig()
        val mgr = EnrollmentManager(
            configProvider = config,
            store = store,
            client = EnrollmentClient(http, keyManager),
            enrollmentSpecificIdProvider = { null },
            deviceLabelProvider = { null },
        )
        return AttachmentUploader(
            dao = dao,
            enrollmentManager = mgr,
            keyManager = keyManager,
            encryptor = EnvelopeEncryptor(box),
            http = http,
            source = source,
            backendUrlResolver = { config.current().backendUrl },
        )
    }

    private fun pending(id: Long, hash: String, uri: String, size: Long, mime: String = "image/jpeg") =
        PendingAttachment(id = id, contentHash = hash, partUri = uri, mime = mime, sizeBytes = size)

    // --- single-shot ---

    @Test
    fun small_file_uploads_single_shot_and_marks_uploaded() {
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        val hash = sha256Hex(bytes)
        val uri = "content://mms/part/1"
        val source = FakeAttachmentSource().apply { put(uri, bytes) }
        val dao = FakePendingAttachmentDao(listOf(pending(1, hash, uri, bytes.size.toLong())))
        val http = FakeHttpClient()
        enqueueEnroll(http, "comma_a") // enroll (begin + complete)
        http.enqueue(200, """{"stored":true,"duplicate":false}""")

        assertEquals(AttachmentUploader.Result.DONE, uploader(dao, http, source).runOnce())
        assertEquals(0, dao.pendingCount())

        // The upload call follows the two enroll POSTs: the envelope to the attachment endpoint.
        val call = http.calls[2]
        assertEquals("https://ingest.example.com/webhooks/incoming/android_messages_attachments", call.url)
        assertEquals("Bearer comma_a", call.headers["Authorization"])
        // Server-role decrypt recovers the single-shot frame + the exact bytes.
        val frame = JSONObject(serverDecrypt(JSONObject(call.body)))
        assertEquals("attachment", frame.getString("type"))
        assertEquals(hash, frame.getString("content_hash"))
        assertEquals(hash, sha256Hex(Base64.getDecoder().decode(frame.getString("bytes_b64"))))
    }

    // --- chunked + memory bound ---

    @Test
    fun large_file_uploads_in_chunks_reassembling_to_the_full_file() {
        // Larger than the 8 MiB single-shot ceiling -> chunked.
        val whole = ByteArray(AttachmentFrames.CHUNK_PLAINTEXT_BYTES * 2 + 1000) { (it % 97).toByte() }
        val hash = sha256Hex(whole)
        val uri = "content://mms/part/5"
        val source = FakeAttachmentSource().apply { put(uri, whole) }
        val dao = FakePendingAttachmentDao(listOf(pending(5, hash, uri, whole.size.toLong(), "video/mp4")))
        val http = FakeHttpClient()
        enqueueEnroll(http, "comma_a") // enroll (begin + complete)
        val count = AttachmentFrames.chunkCount(whole.size.toLong())
        repeat(count) { http.enqueue(200, """{"stored":false,"duplicate":false}""") }

        assertEquals(AttachmentUploader.Result.DONE, uploader(dao, http, source).runOnce())
        assertEquals(0, dao.pendingCount())

        // enroll (2 POSTs) + one POST per chunk; reassembling them yields the original file.
        assertEquals(count + 2, http.calls.size)
        val reassembled = java.io.ByteArrayOutputStream()
        for (i in 1..count) {
            val frame = JSONObject(serverDecrypt(JSONObject(http.calls[i + 1].body)))
            assertEquals("attachment_chunk", frame.getString("type"))
            assertEquals(hash, frame.getString("content_hash"))
            assertEquals(i - 1, frame.getInt("chunk_index"))
            assertEquals(count, frame.getInt("chunk_count"))
            assertEquals(whole.size, frame.getInt("size_bytes"))
            reassembled.write(Base64.getDecoder().decode(frame.getString("chunk_bytes_b64")))
        }
        assertEquals(hash, sha256Hex(reassembled.toByteArray()))
    }

    @Test
    fun large_file_never_reads_more_than_the_chunk_window_at_once() {
        // The bounded source fails the assertion if any single read pulls more than
        // one chunk window - proving the whole file is never materialized at once.
        val whole = ByteArray(AttachmentFrames.CHUNK_PLAINTEXT_BYTES * 3 + 50) { it.toByte() }
        val hash = sha256Hex(whole)
        val uri = "content://mms/part/7"
        val source = FakeAttachmentSource(maxReadBytes = AttachmentFrames.CHUNK_PLAINTEXT_BYTES)
            .apply { put(uri, whole) }
        val dao = FakePendingAttachmentDao(listOf(pending(7, hash, uri, whole.size.toLong(), "video/mp4")))
        val http = FakeHttpClient()
        enqueueEnroll(http, "comma_a")
        val count = AttachmentFrames.chunkCount(whole.size.toLong())
        repeat(count) { http.enqueue(200, """{"stored":false,"duplicate":false}""") }

        assertEquals(AttachmentUploader.Result.DONE, uploader(dao, http, source).runOnce())
        assertEquals(0, dao.pendingCount())
    }

    // --- resumability ---

    @Test
    fun mid_chunk_failure_retries_without_marking_uploaded_then_resumes() {
        // Larger than the 8 MiB single-shot ceiling so it must chunk (3 chunks).
        val whole = ByteArray(AttachmentFrames.CHUNK_PLAINTEXT_BYTES * 2 + 500) { (it % 53).toByte() }
        val hash = sha256Hex(whole)
        val uri = "content://mms/part/8"
        val source = FakeAttachmentSource().apply { put(uri, whole) }
        val dao = FakePendingAttachmentDao(listOf(pending(8, hash, uri, whole.size.toLong(), "video/mp4")))

        // First pass: enroll, chunk 0 ok, chunk 1 -> 500 (retry). Blob NOT uploaded.
        val http1 = FakeHttpClient()
        enqueueEnroll(http1, "comma_a")
        http1.enqueue(200, """{"stored":false,"duplicate":false}""") // chunk 0
        http1.enqueue(500, """{"error":"internal_error"}""") // chunk 1 fails
        assertEquals(AttachmentUploader.Result.RETRY, uploader(dao, http1, source).runOnce())
        assertEquals("not uploaded after a failed pass", 1, dao.pendingCount())

        // Second pass (resume): re-send all chunks (server no-ops the re-sent ones)
        // and complete. The re-send is idempotent server-side, so this is safe.
        val http2 = FakeHttpClient()
        val count = AttachmentFrames.chunkCount(whole.size.toLong())
        repeat(count) { http2.enqueue(200, """{"stored":false,"duplicate":false}""") }
        assertEquals(AttachmentUploader.Result.DONE, uploader(dao, http2, source).runOnce())
        assertEquals(0, dao.pendingCount())
    }

    // --- dedup by content_hash across part_uris ---

    @Test
    fun second_part_with_same_hash_is_satisfied_without_re_upload() {
        val bytes = "identical".toByteArray()
        val hash = sha256Hex(bytes)
        val source = FakeAttachmentSource().apply {
            put("content://mms/part/10", bytes)
            put("content://mms/part/11", bytes)
        }
        val dao = FakePendingAttachmentDao(
            listOf(
                pending(10, hash, "content://mms/part/10", bytes.size.toLong()),
                pending(11, hash, "content://mms/part/11", bytes.size.toLong()),
            ),
        )
        val http = FakeHttpClient()
        enqueueEnroll(http, "comma_a")
        http.enqueue(200, """{"stored":true,"duplicate":false}""") // first upload
        // No second upload scripted: the second row is marked uploaded by hash.

        assertEquals(AttachmentUploader.Result.DONE, uploader(dao, http, source).runOnce())
        assertEquals(0, dao.pendingCount())
        // enroll (2 POSTs) + exactly one upload POST (the duplicate hash was not re-uploaded).
        assertEquals(3, http.calls.size)
    }

    // --- missing source: retain, never drop ---

    @Test
    fun missing_source_retains_pending_and_backs_off() {
        val source = FakeAttachmentSource() // no bytes for the uri
        val dao = FakePendingAttachmentDao(listOf(pending(1, "a".repeat(64), "content://mms/part/x", 10)))
        val http = FakeHttpClient()
        enqueueEnroll(http, "comma_a")

        assertEquals(AttachmentUploader.Result.RETRY, uploader(dao, http, source).runOnce())
        assertEquals("never dropped", 1, dao.pendingCount())
    }

    // --- 401 re-enroll ---

    @Test
    fun upload_401_reenrolls_once_then_succeeds_under_the_fresh_token() {
        val bytes = "retry me".toByteArray()
        val hash = sha256Hex(bytes)
        val uri = "content://mms/part/3"
        val source = FakeAttachmentSource().apply { put(uri, bytes) }
        val dao = FakePendingAttachmentDao(listOf(pending(3, hash, uri, bytes.size.toLong())))
        val http = FakeHttpClient()
        enqueueEnroll(http, "comma_old") // enroll
        http.enqueue(401, """{"error":"unauthorized"}""") // token revoked mid-upload
        enqueueEnroll(http, "comma_new") // forced re-enroll
        http.enqueue(200, """{"stored":true,"duplicate":false}""") // retry succeeds

        assertEquals(AttachmentUploader.Result.DONE, uploader(dao, http, source).runOnce())
        assertEquals(0, dao.pendingCount())
        assertEquals("Bearer comma_new", http.calls.last().headers["Authorization"])
    }

    @Test
    fun nothing_pending_is_done() {
        val dao = FakePendingAttachmentDao(emptyList())
        val http = FakeHttpClient()
        enqueueEnroll(http, "comma_a")
        assertEquals(AttachmentUploader.Result.DONE, uploader(dao, http, FakeAttachmentSource()).runOnce())
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
