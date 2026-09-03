package com.commacompliance.archiver.net

import java.net.HttpURLConnection
import java.net.URL

/**
 * HttpURLConnection-backed [HttpClient]. Connect/read timeouts are bounded so a
 * stalled network surfaces as an IOException (WorkManager retry) rather than
 * pinning the worker. The response body is read whether the status is success or
 * error so the caller can inspect both.
 *
 * Redirects are NOT followed (`instanceFollowRedirects = false`). These requests
 * carry a bearer credential in an `Authorization` header; the default redirect
 * follower would re-issue the request - potentially cross-origin or downgraded to
 * cleartext - while replaying that header. Surfacing the raw 3xx instead lets the
 * callers treat it as a (non-retryable) non-success rather than silently forwarding
 * the token to wherever the `Location` points.
 */
class UrlConnectionHttpClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpClient {

    override fun postJson(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): HttpClient.Response {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", HttpClient.CONTENT_TYPE_JSON)
            setRequestProperty("Accept", HttpClient.CONTENT_TYPE_JSON)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    override fun getJson(
        url: String,
        headers: Map<String, String>,
    ): HttpClient.Response {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", HttpClient.CONTENT_TYPE_JSON)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        try {
            return readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection): HttpClient.Response {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        return HttpClient.Response(code, responseBody)
    }
}
