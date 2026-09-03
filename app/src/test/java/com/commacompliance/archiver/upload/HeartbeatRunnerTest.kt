package com.commacompliance.archiver.upload

import androidx.test.core.app.ApplicationProvider
import com.commacompliance.archiver.FakeSharedPreferences
import com.commacompliance.archiver.config.ConfigProvider
import com.commacompliance.archiver.crypto.KeyManager
import com.commacompliance.archiver.crypto.TestSodium
import com.commacompliance.archiver.enroll.EnrollmentClient
import com.commacompliance.archiver.enroll.EnrollmentManager
import com.commacompliance.archiver.enroll.EnrollmentStore
import com.commacompliance.archiver.enroll.HeartbeatClient
import com.commacompliance.archiver.net.FakeHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HeartbeatRunnerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val box = TestSodium.box()
    private val keyManager = KeyManager(context, box) { FakeSharedPreferences() }
    private val store = EnrollmentStore(context) { FakeSharedPreferences() }

    private class StubConfig : ConfigProvider(ApplicationProvider.getApplicationContext()) {
        // No managed config: a self-serve device runs on its stored token.
        override fun current() = ManagedConfig(null, null)
    }

    private class ManagedStubConfig : ConfigProvider(ApplicationProvider.getApplicationContext()) {
        // A managed device whose enrollment_token drives the EMM handshake.
        override fun current() = ManagedConfig("https://ingest.example.com", "tok-1")
    }

    private fun seedSelfServe(token: String = "tok-1", backfillDays: Int = 0) {
        store.saveSelfServe(
            EnrollmentStore.Enrollment(
                archiveToken = token,
                deviceId = "amdev_1",
                destinationPublicKey = box.generateKeyPair().publicKey,
                kid = "AbCdEfGhIjK=",
                backfillDays = backfillDays,
            ),
        )
        store.setSelfServeBackendUrl("https://ingest.example.com")
    }

    private fun runner(
        http: FakeHttpClient,
        reconciled: () -> Unit,
        flushed: () -> Unit,
    ): HeartbeatRunner {
        val config = StubConfig()
        val mgr = EnrollmentManager(
            configProvider = config,
            store = store,
            client = EnrollmentClient(http, keyManager),
            enrollmentSpecificIdProvider = { null },
            deviceLabelProvider = { null },
        )
        return HeartbeatRunner(
            enrollmentManager = mgr,
            store = store,
            heartbeatClient = HeartbeatClient(http),
            backendUrlResolver = { config.current().backendUrl ?: store.selfServeBackendUrl() },
            reconcile = reconciled,
            flushUploads = flushed,
        )
    }

    private fun managedRunner(http: FakeHttpClient): HeartbeatRunner {
        val config = ManagedStubConfig()
        val mgr = EnrollmentManager(
            configProvider = config,
            store = store,
            client = EnrollmentClient(http, keyManager),
            enrollmentSpecificIdProvider = { null },
            deviceLabelProvider = { null },
        )
        return HeartbeatRunner(
            enrollmentManager = mgr,
            store = store,
            heartbeatClient = HeartbeatClient(http),
            backendUrlResolver = { config.current().backendUrl ?: store.selfServeBackendUrl() },
            reconcile = {},
            flushUploads = {},
        )
    }

    @Test
    fun terminal_token_rejection_stops_the_heartbeat_retry_loop() {
        // A returning device whose signing key no longer matches gets a terminal 409.
        // The heartbeat must surface TOKEN_REJECTED (-> WorkManager failure()), NOT
        // RETRY, so it does not spin the impossible enrollment under backoff forever -
        // matching how the upload path treats a terminal rejection.
        val http = FakeHttpClient()
        http.enqueue(409, """{"error":"signing_key_mismatch"}""")
        val result = managedRunner(http).runOnce()
        assertEquals(HeartbeatRunner.Result.TOKEN_REJECTED, result)
    }

    @Test
    fun heartbeats_then_reconciles_then_flushes_and_refreshes_policy() {
        seedSelfServe(backfillDays = 0)
        val http = FakeHttpClient()
        http.enqueue(200, """{"backfill_days":21}""")
        var reconciled = false
        var flushed = false

        val result = runner(http, { reconciled = true }, { flushed = true }).runOnce()

        assertEquals(HeartbeatRunner.Result.DONE, result)
        assertTrue("reconcile ran", reconciled)
        assertTrue("flush ran", flushed)
        // The heartbeat POST went to the heartbeat endpoint, bearer-authed.
        val call = http.calls.single()
        assertEquals("https://ingest.example.com/api/v1/android_archiver/heartbeat", call.url)
        assertEquals("Bearer tok-1", call.headers["Authorization"])
        // Policy refreshed from the response.
        assertEquals(21, store.load()!!.backfillDays)
    }

    @Test
    fun not_connected_is_done_without_network() {
        // No enrollment stored: nothing to heartbeat, not an error.
        val http = FakeHttpClient()
        var reconciled = false
        val result = runner(http, { reconciled = true }, {}).runOnce()
        assertEquals(HeartbeatRunner.Result.DONE, result)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun heartbeat_401_still_reconciles_and_flushes() {
        seedSelfServe()
        val http = FakeHttpClient()
        http.enqueue(401, """{"error":"unauthorized"}""")
        var reconciled = false
        var flushed = false

        // A 401 is non-fatal: the upload flush re-enrolls. We still reconcile + flush.
        runner(http, { reconciled = true }, { flushed = true }).runOnce()
        assertTrue(reconciled)
        assertTrue(flushed)
    }

    @Test
    fun server_5xx_returns_retry_but_local_reconcile_still_runs() {
        seedSelfServe()
        val http = FakeHttpClient()
        http.enqueue(500, "")
        var reconciled = false

        val result = runner(http, { reconciled = true }, {}).runOnce()
        assertEquals(HeartbeatRunner.Result.RETRY, result)
        // Local reconcile is independent of the network heartbeat.
        assertTrue(reconciled)
    }
}
