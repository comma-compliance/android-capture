package com.commacompliance.archiver.upload

import androidx.test.core.app.ApplicationProvider
import com.commacompliance.archiver.FakeSharedPreferences
import com.commacompliance.archiver.config.ConfigProvider
import com.commacompliance.archiver.crypto.EnvelopeEncryptor
import com.commacompliance.archiver.crypto.KeyManager
import com.commacompliance.archiver.crypto.LazySodiumNaclBox
import com.commacompliance.archiver.crypto.TestSodium
import com.commacompliance.archiver.data.CapturedEvent
import com.commacompliance.archiver.enroll.EnrollmentClient
import com.commacompliance.archiver.enroll.EnrollmentManager
import com.commacompliance.archiver.enroll.EnrollmentStore
import com.commacompliance.archiver.net.FakeHttpClient
import com.commacompliance.archiver.net.HttpsUrls
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class UploaderTest {

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

    private fun event(id: Long) = CapturedEvent(
        id = id,
        providerId = "sms:$id",
        eventType = "received",
        eventTs = 1_700_000_000_000L + id,
        bodyHash = "h$id",
        threadId = "t",
        sender = "+1",
        rawJson = """{"source":"sms","body":"m$id"}""",
    )

    private fun uploader(dao: FakeCapturedEventDao, http: FakeHttpClient): Uploader {
        val config = StubConfig()
        val mgr = EnrollmentManager(
            configProvider = config,
            store = store,
            client = EnrollmentClient(http, keyManager),
            enrollmentSpecificIdProvider = { null },
            deviceLabelProvider = { null },
        )
        return Uploader(dao, mgr, keyManager, EnvelopeEncryptor(box), http, config)
    }

    @Test
    fun uploads_pending_and_marks_uploaded_on_200() {
        val dao = FakeCapturedEventDao(listOf(event(1), event(2)))
        val http = FakeHttpClient()
        enqueueEnroll(http, "comma_test_a") // enroll (begin + complete)
        http.enqueue(200, """{"accepted":2,"duplicates":0}""") // batch

        assertEquals(Uploader.Result.DONE, uploader(dao, http).runOnce())
        assertTrue(dao.pendingUpload(500).isEmpty())

        // The upload POST follows the two enroll POSTs, bearer-authed, to the webhook path.
        val uploadCall = http.calls[2]
        assertEquals("https://ingest.example.com/webhooks/incoming/android_messages_webhooks", uploadCall.url)
        assertEquals("Bearer comma_test_a", uploadCall.headers["Authorization"])
        // Body is the envelope, not the plaintext.
        val envelope = JSONObject(uploadCall.body)
        assertEquals("nacl-box-v1", envelope.getString("version"))
        assertEquals("AbCdEfGhIjK=", envelope.getString("kid"))
    }

    @Test
    fun splits_pending_into_batches_of_at_most_500() {
        val dao = FakeCapturedEventDao((1L..600L).map { event(it) })
        val http = FakeHttpClient()
        enqueueEnroll(http, "comma_test_a") // enroll (begin + complete)
        http.enqueue(200, """{"accepted":500,"duplicates":0}""") // batch 1
        http.enqueue(200, """{"accepted":100,"duplicates":0}""") // batch 2

        assertEquals(Uploader.Result.DONE, uploader(dao, http).runOnce())
        assertTrue(dao.pendingUpload(1000).isEmpty())

        // enroll (2 POSTs) + two upload POSTs; first upload batch carries exactly 500 events.
        assertEquals(4, http.calls.size)
        val firstBatchEnvelope = JSONObject(http.calls[2].body)
        // The plaintext is encrypted; assert batch sizing via the server's decrypt role.
        val plaintext = decryptForServer(firstBatchEnvelope)
        assertEquals(500, JSONObject(plaintext).getJSONArray("events").length())
    }

    @Test
    fun re_enrolls_on_401_then_retries_same_batch() {
        val dao = FakeCapturedEventDao(listOf(event(1)))
        val http = FakeHttpClient()
        enqueueEnroll(http, "comma_test_old") // initial enroll
        http.enqueue(401, """{"error":"unauthorized"}""") // token revoked
        enqueueEnroll(http, "comma_test_new") // forced re-enroll
        http.enqueue(200, """{"accepted":1,"duplicates":0}""") // retry batch

        assertEquals(Uploader.Result.DONE, uploader(dao, http).runOnce())
        assertTrue(dao.pendingUpload(500).isEmpty())
        // Final upload used the rotated token.
        assertEquals("Bearer comma_test_new", http.calls.last().headers["Authorization"])
    }

    @Test
    fun second_401_after_reenroll_backs_off_instead_of_looping() {
        val dao = FakeCapturedEventDao(listOf(event(1)))
        val http = FakeHttpClient()
        enqueueEnroll(http, "comma_test_old") // initial enroll
        http.enqueue(401, """{"error":"unauthorized"}""") // first 401
        enqueueEnroll(http, "comma_test_new") // forced re-enroll
        http.enqueue(401, """{"error":"unauthorized"}""") // STILL 401 with the fresh token

        // Bails to RETRY (WorkManager backoff) rather than re-enrolling again.
        assertEquals(Uploader.Result.RETRY, uploader(dao, http).runOnce())
        assertEquals(1, dao.pendingUpload(500).size)
        assertEquals(6, http.calls.size) // 2 enroll handshakes (2 POSTs each) + 2 batch attempts, no third enroll
    }

    @Test
    fun transport_failure_propagates_for_worker_backoff() {
        val dao = FakeCapturedEventDao(listOf(event(1)))
        val http = FakeHttpClient()
        enqueueEnroll(http, "comma_test_a") // enroll (begin + complete)
        http.enqueueTransportFailure() // POST throws IOException

        assertThrows(java.io.IOException::class.java) { uploader(dao, http).runOnce() }
        // Nothing marked uploaded.
        assertEquals(1, dao.pendingUpload(500).size)
    }

    @Test
    fun server_5xx_on_batch_is_retry() {
        val dao = FakeCapturedEventDao(listOf(event(1)))
        val http = FakeHttpClient()
        enqueueEnroll(http, "comma_test_a")
        http.enqueue(500, """{"error":"internal_error"}""")
        assertEquals(Uploader.Result.RETRY, uploader(dao, http).runOnce())
        assertEquals(1, dao.pendingUpload(500).size)
    }

    @Test
    fun non_retryable_4xx_backs_off_without_dropping_events() {
        val dao = FakeCapturedEventDao(listOf(event(1)))
        val http = FakeHttpClient()
        enqueueEnroll(http, "comma_test_a")
        http.enqueue(400, """{"error":"invalid_batch"}""")
        // A 400 will not heal on retry, but the events must NOT be marked uploaded
        // (no silent data loss); the pass backs off for a human to investigate.
        assertEquals(Uploader.Result.RETRY, uploader(dao, http).runOnce())
        assertEquals(1, dao.pendingUpload(500).size)
    }

    @Test
    fun offline_then_reconnect_drains_full_backlog_across_a_restart_without_dropping() {
        // A long offline window ("airplane for 5 hours") accumulated a large backlog.
        // The durable DAO stands in for Room across an app restart: the SAME row set
        // is shared by two separate Uploader instances below.
        val dao = FakeCapturedEventDao((1L..1700L).map { event(it) })

        // First reconnect attempt: enroll succeeds, but the network drops on the
        // first batch POST (transport failure) - nothing is marked uploaded.
        val http1 = FakeHttpClient()
        enqueueEnroll(http1, "comma_test_a")
        http1.enqueueTransportFailure()
        assertThrows(java.io.IOException::class.java) { uploader(dao, http1).runOnce() }
        assertEquals("no events lost on a failed pass", 1700, dao.pendingCount())

        // Simulate an app restart (a brand-new Uploader over the SAME durable rows
        // and the SAME persisted enrollment), now with stable connectivity. It must
        // resume and drain ALL 1700 events in 500-event batches (4 batches) with
        // nothing dropped or double-committed. The credential persisted on pass 1 is
        // reused, so no re-enroll POST is expected.
        val http2 = FakeHttpClient()
        repeat(4) { http2.enqueue(200, """{"accepted":500,"duplicates":0}""") }

        assertEquals(Uploader.Result.DONE, uploader(dao, http2).runOnce())
        assertEquals("entire backlog drained", 0, dao.pendingCount())
        assertEquals("all 1700 rows retained, none dropped", 1700, dao.count())

        // Exactly 4 upload batches (500+500+500+200), no re-enroll needed.
        assertEquals(4, http2.calls.size)
        val lastBatch = JSONObject(http2.calls[3].body)
        assertEquals(200, JSONObject(decryptForServer(lastBatch)).getJSONArray("events").length())
    }

    @Test
    fun nothing_to_upload_is_done() {
        val dao = FakeCapturedEventDao(emptyList())
        val http = FakeHttpClient()
        enqueueEnroll(http, "comma_test_a")
        assertEquals(Uploader.Result.DONE, uploader(dao, http).runOnce())
    }

    @Test
    fun non_https_resolved_backend_retries_and_uploads_nothing() {
        // The production resolver wraps the backend in HttpsUrls.requireHttps, which
        // collapses a non-https URL to null. The pass must then treat the device as
        // not-configured (RETRY) and NEVER POST the bearer-authed batch in the clear.
        val dao = FakeCapturedEventDao(listOf(event(1)))
        val http = FakeHttpClient()
        enqueueEnroll(http, "comma_test_a") // enroll still uses the https config URL
        val config = StubConfig()
        val mgr = EnrollmentManager(
            configProvider = config,
            store = store,
            client = EnrollmentClient(http, keyManager),
            enrollmentSpecificIdProvider = { null },
            deviceLabelProvider = { null },
        )
        val uploader = Uploader(
            dao = dao,
            enrollmentManager = mgr,
            keyManager = keyManager,
            encryptor = EnvelopeEncryptor(box),
            http = http,
            configProvider = config,
            backendUrlResolver = { HttpsUrls.requireHttps("http://ingest.example.com") },
        )

        assertEquals(Uploader.Result.RETRY, uploader.runOnce())
        // Enroll (begin + complete) were the only POSTs; no batch upload happened.
        assertEquals(2, http.calls.size)
        assertEquals(1, dao.pendingUpload(500).size)
    }

    /** Replay the server's decrypt to read a batch's plaintext for assertions. */
    private fun decryptForServer(envelope: JSONObject): String {
        val decoder = Base64.getDecoder()
        val recovered = box.boxOpenEasy(
            cipher = decoder.decode(envelope.getString("ciphertext")),
            nonce = decoder.decode(envelope.getString("nonce")),
            senderPublicKey = decoder.decode(envelope.getString("sourcePublicKey")),
            recipientSecretKey = destination.secretKey,
        )
        return String(recovered, Charsets.UTF_8)
    }
}
