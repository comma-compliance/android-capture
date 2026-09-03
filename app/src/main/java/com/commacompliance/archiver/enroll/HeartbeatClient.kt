package com.commacompliance.archiver.enroll

import com.commacompliance.archiver.net.HttpClient
import org.json.JSONObject

/**
 * Calls the server heartbeat so an idle, connected device stays visible
 * (`last_seen_at`) without a message to upload, and refreshes the backfill policy.
 *
 *   POST {backendUrl}/api/v1/android_archiver/heartbeat
 *   Authorization: Bearer <archive_token>   -> 200 { "backfill_days": <int> }
 *
 * The body carries no secrets; the response's backfill_days lets the device pick up
 * a policy change the operator made after connect. A non-200 is reported so the
 * caller can react (e.g. a 401 means the token rotated and the upload path's
 * re-enroll handles it).
 */
class HeartbeatClient(private val http: HttpClient) {

    sealed interface Result {
        /** 200; carries the refreshed backfill policy when the server included it. */
        data class Ok(val backfillDays: Int?) : Result

        /** Token rejected (401) - the upload path's re-enroll recovers it. */
        data object Unauthorized : Result

        /** Transient (network/5xx); retry later. */
        data object Retry : Result
    }

    fun send(backendUrl: String, archiveToken: String): Result {
        val response = http.postJson(
            url = backendUrl.trimEnd('/') + HEARTBEAT_PATH,
            body = "{}",
            headers = mapOf("Authorization" to "Bearer $archiveToken"),
        )
        return when (response.code) {
            200 -> Result.Ok(parseBackfillDays(response.body))
            401 -> Result.Unauthorized
            else -> Result.Retry
        }
    }

    private fun parseBackfillDays(body: String): Int? = runCatching {
        val obj = JSONObject(body)
        if (obj.has("backfill_days") && !obj.isNull("backfill_days")) {
            obj.getInt("backfill_days").coerceAtLeast(0)
        } else {
            null
        }
    }.getOrNull()

    companion object {
        const val HEARTBEAT_PATH = "/api/v1/android_archiver/heartbeat"
    }
}
