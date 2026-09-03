package com.commacompliance.archiver.enroll

import androidx.test.core.app.ApplicationProvider
import com.commacompliance.archiver.FakeSharedPreferences
import com.commacompliance.archiver.config.ConfigProvider
import com.commacompliance.archiver.crypto.KeyManager
import com.commacompliance.archiver.crypto.TestSodium
import com.commacompliance.archiver.integrity.FakeIntegrityTokenSource
import com.commacompliance.archiver.integrity.RequestHash
import com.commacompliance.archiver.net.FakeHttpClient
import com.commacompliance.archiver.net.HttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class EnrollmentManagerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val box = TestSodium.box()
    private val keyManager = KeyManager(context, box) { FakeSharedPreferences() }
    private val store = EnrollmentStore(context) { FakeSharedPreferences() }

    private class StubConfig(private var config: ManagedConfig) : ConfigProvider(ApplicationProvider.getApplicationContext()) {
        override fun current() = config
        fun set(c: ManagedConfig) { config = c }
    }

    private fun beginBody(challenge: ByteArray = ByteArray(32) { 7 }): String = JSONObject().apply {
        put("challenge_id", "ch_1")
        put("challenge", Base64.getEncoder().encodeToString(challenge))
        put("expires_at", "2026-01-01T00:00:00Z")
    }.toString()

    private fun completeBody(token: String): String {
        val destPub = box.generateKeyPair().publicKey
        return JSONObject().apply {
            put("archive_token", token)
            put("device_id", "amdev_1")
            put("destination_public_key", Base64.getEncoder().encodeToString(destPub))
            put("kid", "AbCdEfGhIjK=")
        }.toString()
    }

    // The full two-step handshake the server scripts for one successful enroll.
    private fun enqueueHandshake(http: FakeHttpClient, token: String, challenge: ByteArray = ByteArray(32) { 7 }) {
        http.enqueue(201, beginBody(challenge))
        http.enqueue(200, completeBody(token))
    }

    private fun manager(
        config: ConfigProvider,
        http: FakeHttpClient,
        integrity: FakeIntegrityTokenSource? = null,
        cloudProjectNumber: Long? = null,
    ) = EnrollmentManager(
        configProvider = config,
        store = store,
        client = EnrollmentClient(http, keyManager, integrity),
        enrollmentSpecificIdProvider = { "esid" },
        deviceLabelProvider = { "label" },
        cloudProjectNumberProvider = { cloudProjectNumber },
    )

    @Test
    fun not_configured_when_managed_config_incomplete() {
        val http = FakeHttpClient()
        val mgr = manager(StubConfig(ConfigProvider.ManagedConfig(null, null)), http)
        assertEquals(EnrollmentManager.Outcome.NotConfigured, mgr.ensureEnrolled())
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun non_https_managed_backend_is_not_configured_and_sends_no_enroll_token() {
        // The begin request carries the managed enrollment_token; a non-https managed
        // backend_url must NOT receive it. Treated as not-configured.
        val http = FakeHttpClient()
        val mgr = manager(StubConfig(ConfigProvider.ManagedConfig("http://h", "tok-1")), http)
        assertEquals(EnrollmentManager.Outcome.NotConfigured, mgr.ensureEnrolled())
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun enrolls_when_no_prior_state_then_caches() {
        val http = FakeHttpClient()
        enqueueHandshake(http, "comma_test_first")
        val mgr = manager(StubConfig(ConfigProvider.ManagedConfig("https://h", "tok-1")), http)

        val first = mgr.ensureEnrolled() as EnrollmentManager.Outcome.Ready
        assertEquals("comma_test_first", first.enrollment.archiveToken)

        // Second call with the same token reuses the cached enrollment - no new POSTs
        // beyond the two from the one handshake.
        val second = mgr.ensureEnrolled() as EnrollmentManager.Outcome.Ready
        assertEquals("comma_test_first", second.enrollment.archiveToken)
        assertEquals(2, http.calls.size)
    }

    @Test
    fun re_enrolls_when_managed_enrollment_token_rotates() {
        val http = FakeHttpClient()
        enqueueHandshake(http, "comma_test_a")
        enqueueHandshake(http, "comma_test_b")
        val config = StubConfig(ConfigProvider.ManagedConfig("https://h", "tok-1"))
        val mgr = manager(config, http)

        mgr.ensureEnrolled()
        config.set(ConfigProvider.ManagedConfig("https://h", "tok-2"))
        val after = mgr.ensureEnrolled() as EnrollmentManager.Outcome.Ready
        assertEquals("comma_test_b", after.enrollment.archiveToken)
        assertEquals(4, http.calls.size)
    }

    @Test
    fun force_reenroll_rotates_archive_token() {
        val http = FakeHttpClient()
        enqueueHandshake(http, "comma_test_a")
        enqueueHandshake(http, "comma_test_rotated")
        val mgr = manager(StubConfig(ConfigProvider.ManagedConfig("https://h", "tok-1")), http)

        mgr.ensureEnrolled()
        val rotated = mgr.ensureEnrolled(forceReenroll = true) as EnrollmentManager.Outcome.Ready
        assertEquals("comma_test_rotated", rotated.enrollment.archiveToken)
    }

    @Test
    fun token_rejected_clears_state() {
        val http = FakeHttpClient()
        http.enqueue(401, """{"error":"invalid_token"}""")
        val mgr = manager(StubConfig(ConfigProvider.ManagedConfig("https://h", "tok-1")), http)

        assertEquals(EnrollmentManager.Outcome.TokenRejected, mgr.ensureEnrolled())
        assertEquals(null, store.load())
    }

    @Test
    fun signing_key_mismatch_clears_state_and_is_token_rejected() {
        val http = FakeHttpClient()
        http.enqueue(409, """{"error":"signing_key_mismatch"}""")
        val mgr = manager(StubConfig(ConfigProvider.ManagedConfig("https://h", "tok-1")), http)

        assertEquals(EnrollmentManager.Outcome.TokenRejected, mgr.ensureEnrolled())
        assertEquals(null, store.load())
    }

    @Test
    fun complete_stage_401_is_retry_and_preserves_stored_enrollment() {
        // begin succeeds, then the challenge expires/was consumed before complete (a
        // 401 on COMPLETE). This is the Finding-1 scenario: a still-valid stored
        // enrollment must NOT be cleared and the device must NOT give up - it reports
        // Retry and keeps its credential so a later pass can re-run the handshake.
        store.save(
            EnrollmentStore.Enrollment(
                archiveToken = "arc-old",
                deviceId = "dev",
                destinationPublicKey = box.generateKeyPair().publicKey,
                kid = "k",
            ),
            enrollmentTokenConsumed = "tok-1",
        )
        val http = FakeHttpClient()
        http.enqueue(201, beginBody())
        http.enqueue(401, """{"error":"invalid_token"}""")
        // The managed token rotated (tok-1 -> tok-2), so a re-enroll is attempted.
        val mgr = manager(StubConfig(ConfigProvider.ManagedConfig("https://h", "tok-2")), http)

        assertEquals(EnrollmentManager.Outcome.Retry, mgr.ensureEnrolled())
        // The still-valid enrollment is untouched.
        assertEquals("arc-old", store.load()!!.archiveToken)
        assertEquals(2, http.calls.size)
    }

    @Test
    fun complete_stage_401_then_next_pass_re_runs_the_full_handshake() {
        // First pass: begin ok, complete 401 (challenge expired) -> Retry, nothing
        // stored. Because the creds were never advanced, the NEXT pass re-runs the
        // entire begin->complete handshake and succeeds.
        val http = FakeHttpClient()
        http.enqueue(201, beginBody())
        http.enqueue(401, """{"error":"invalid_token"}""")
        http.enqueue(201, beginBody())
        http.enqueue(200, completeBody("comma_test_recovered"))
        val mgr = manager(StubConfig(ConfigProvider.ManagedConfig("https://h", "tok-1")), http)

        assertEquals(EnrollmentManager.Outcome.Retry, mgr.ensureEnrolled())
        assertEquals(null, store.load())
        val recovered = mgr.ensureEnrolled() as EnrollmentManager.Outcome.Ready
        assertEquals("comma_test_recovered", recovered.enrollment.archiveToken)
        assertEquals(4, http.calls.size)
    }

    @Test
    fun capture_disabled_403_is_retry() {
        // A policy refusal (capture not yet enabled) is recoverable: keep retrying via
        // the periodic worker rather than clearing credentials.
        val http = FakeHttpClient()
        http.enqueue(403, """{"error":"capture_disabled"}""")
        val mgr = manager(StubConfig(ConfigProvider.ManagedConfig("https://h", "tok-1")), http)
        assertEquals(EnrollmentManager.Outcome.Retry, mgr.ensureEnrolled())
    }

    @Test
    fun invalid_signature_422_is_retry() {
        val http = FakeHttpClient()
        http.enqueue(201, beginBody())
        http.enqueue(422, """{"error":"invalid_signature"}""")
        val mgr = manager(StubConfig(ConfigProvider.ManagedConfig("https://h", "tok-1")), http)
        assertEquals(EnrollmentManager.Outcome.Retry, mgr.ensureEnrolled())
    }

    @Test
    fun server_failure_is_retry() {
        val http = FakeHttpClient()
        http.enqueue(503, "")
        val mgr = manager(StubConfig(ConfigProvider.ManagedConfig("https://h", "tok-1")), http)
        assertEquals(EnrollmentManager.Outcome.Retry, mgr.ensureEnrolled())
    }

    @Test
    fun emm_enroll_attaches_integrity_token_bound_to_the_challenge() {
        val challenge = ByteArray(32) { (it + 5).toByte() }
        val challengeB64 = Base64.getEncoder().encodeToString(challenge)
        val http = FakeHttpClient()
        http.enqueue(201, beginBody(challenge))
        http.enqueue(200, completeBody("comma_test_a"))
        val integrity = FakeIntegrityTokenSource(token = "emm-itok")
        val mgr = manager(
            StubConfig(ConfigProvider.ManagedConfig("https://h", "tok-1")),
            http,
            integrity = integrity,
            cloudProjectNumber = 555L,
        )

        mgr.ensureEnrolled() as EnrollmentManager.Outcome.Ready

        // The complete body carries the token (begin does not).
        assertEquals("emm-itok", JSONObject(http.calls[1].body).getString("integrity_token"))
        assertFalse(JSONObject(http.calls[0].body).has("integrity_token"))
        // The requestHash binds to the challenge STRING the device received.
        val call = integrity.calls.single()
        assertEquals(555L, call.cloudProjectNumber)
        assertEquals(RequestHash.forChallenge(challengeB64), call.requestHash)
    }

    @Test
    fun emm_enroll_proceeds_token_less_when_no_cloud_project_number() {
        // Discovery advertised no cloud_project_number -> skip acquisition entirely.
        val http = FakeHttpClient()
        enqueueHandshake(http, "comma_test_a")
        val integrity = FakeIntegrityTokenSource(token = "emm-itok")
        val mgr = manager(
            StubConfig(ConfigProvider.ManagedConfig("https://h", "tok-1")),
            http,
            integrity = integrity,
            cloudProjectNumber = null,
        )

        mgr.ensureEnrolled() as EnrollmentManager.Outcome.Ready
        assertTrue(integrity.calls.isEmpty())
        assertFalse(JSONObject(http.calls[1].body).has("integrity_token"))
    }

    @Test
    fun emm_enroll_proceeds_token_less_when_play_services_absent() {
        // cloud_project_number present but the source yields null (Play absent): the
        // enroll still succeeds, the complete body simply omits the token.
        val http = FakeHttpClient()
        enqueueHandshake(http, "comma_test_a")
        val integrity = FakeIntegrityTokenSource(token = null)
        val mgr = manager(
            StubConfig(ConfigProvider.ManagedConfig("https://h", "tok-1")),
            http,
            integrity = integrity,
            cloudProjectNumber = 555L,
        )

        mgr.ensureEnrolled() as EnrollmentManager.Outcome.Ready
        assertEquals(1, integrity.calls.size)
        assertFalse(JSONObject(http.calls[1].body).has("integrity_token"))
    }

    @Test
    fun self_serve_enrollment_is_ready_with_no_managed_config() {
        // A device provisioned via the self-serve path (no managed enrollment_token)
        // must remain Ready so its uploads keep flowing without managed config.
        store.saveSelfServe(
            EnrollmentStore.Enrollment(
                archiveToken = "arc",
                deviceId = "dev",
                destinationPublicKey = box.generateKeyPair().publicKey,
                kid = "k",
            ),
        )
        val http = FakeHttpClient()
        val mgr = manager(StubConfig(ConfigProvider.ManagedConfig(null, null)), http)
        val outcome = mgr.ensureEnrolled() as EnrollmentManager.Outcome.Ready
        assertEquals("arc", outcome.enrollment.archiveToken)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun managed_enrollment_without_config_is_not_kept_alive() {
        // A device that consumed a managed enrollment_token but whose config is now
        // incomplete (token pulled / transient read miss) is NOT silently kept
        // running on a stale archive_token - it reports NotConfigured.
        store.save(
            EnrollmentStore.Enrollment(
                archiveToken = "arc",
                deviceId = "dev",
                destinationPublicKey = box.generateKeyPair().publicKey,
                kid = "k",
            ),
            enrollmentTokenConsumed = "tok-managed",
        )
        val http = FakeHttpClient()
        val mgr = manager(StubConfig(ConfigProvider.ManagedConfig(null, null)), http)
        assertEquals(EnrollmentManager.Outcome.NotConfigured, mgr.ensureEnrolled())
        assertTrue(http.calls.isEmpty())
    }

    // Blocks the FIRST POST (begin) on a latch so a second concurrent caller is forced
    // to wait on the enrollment lock while the first handshake is mid-flight.
    private class GatingHttpClient(private val beginBody: String, private val completeBody: String) : HttpClient {
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        val firstCallEntered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)

        override fun postJson(url: String, body: String, headers: Map<String, String>): HttpClient.Response {
            val n = callCount.incrementAndGet()
            if (n == 1) {
                firstCallEntered.countDown()
                release.await()
                return HttpClient.Response(201, beginBody)
            }
            return HttpClient.Response(200, completeBody)
        }

        override fun getJson(url: String, headers: Map<String, String>): HttpClient.Response =
            HttpClient.Response(200, "{}")
    }

    @Test
    fun concurrent_ensureEnrolled_runs_a_single_handshake() {
        // Two WorkManager jobs hit ensureEnrolled at once. Without the single-flight
        // lock both would run begin/complete and clobber each other's stored token;
        // with it, the first enrolls and the second reuses the cached enrollment.
        val client = GatingHttpClient(beginBody(), completeBody("comma_test_single"))
        val mgr = EnrollmentManager(
            configProvider = StubConfig(ConfigProvider.ManagedConfig("https://h", "tok-1")),
            store = store,
            client = EnrollmentClient(client, keyManager, null),
            enrollmentSpecificIdProvider = { "esid" },
            deviceLabelProvider = { "label" },
            cloudProjectNumberProvider = { null },
        )

        val outcomes = java.util.concurrent.ConcurrentHashMap<Int, EnrollmentManager.Outcome>()
        val a = Thread { outcomes[0] = mgr.ensureEnrolled() }
        a.start()
        client.firstCallEntered.await() // A is inside the lock, blocked mid-begin
        val b = Thread { outcomes[1] = mgr.ensureEnrolled() }
        b.start()
        Thread.sleep(100) // let B reach (and block on) the enrollment lock
        client.release.countDown() // let A finish the handshake and release the lock
        a.join(5000)
        b.join(5000)

        // Exactly one handshake ran (A's begin + complete); B reused A's enrollment.
        assertEquals(2, client.callCount.get())
        assertEquals("comma_test_single", (outcomes[0] as EnrollmentManager.Outcome.Ready).enrollment.archiveToken)
        assertEquals("comma_test_single", (outcomes[1] as EnrollmentManager.Outcome.Ready).enrollment.archiveToken)
    }
}
