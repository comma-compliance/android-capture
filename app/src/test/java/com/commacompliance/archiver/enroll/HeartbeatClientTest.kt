package com.commacompliance.archiver.enroll

import com.commacompliance.archiver.net.FakeHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HeartbeatClientTest {

    private val http = FakeHttpClient()
    private val client = HeartbeatClient(http)

    @Test
    fun posts_bearer_authed_to_heartbeat_path_and_parses_backfill_days() {
        http.enqueue(200, """{"backfill_days":14}""")
        val result = client.send("https://ingest.example.com/", "tok-abc")

        assertTrue(result is HeartbeatClient.Result.Ok)
        assertEquals(14, (result as HeartbeatClient.Result.Ok).backfillDays)

        val call = http.calls.single()
        assertEquals("POST", call.method)
        assertEquals("https://ingest.example.com/api/v1/android_archiver/heartbeat", call.url)
        assertEquals("Bearer tok-abc", call.headers["Authorization"])
    }

    @Test
    fun ok_with_no_backfill_days_is_null() {
        http.enqueue(200, "{}")
        val result = client.send("https://ingest.example.com", "tok")
        assertNull((result as HeartbeatClient.Result.Ok).backfillDays)
    }

    @Test
    fun unauthorized_maps_to_unauthorized() {
        http.enqueue(401, """{"error":"unauthorized"}""")
        assertEquals(HeartbeatClient.Result.Unauthorized, client.send("https://x.example.com", "tok"))
    }

    @Test
    fun server_error_maps_to_retry() {
        http.enqueue(503, "")
        assertEquals(HeartbeatClient.Result.Retry, client.send("https://x.example.com", "tok"))
    }
}
