package com.commacompliance.archiver.net

/**
 * A tiny POST-only HTTP surface. Kept behind an interface (rather than pulling in
 * a full client library) so the enrollment + upload logic is exercised in plain
 * JVM unit tests with a fake, and the production path is a thin
 * HttpURLConnection.
 */
interface HttpClient {
    data class Response(val code: Int, val body: String)

    /**
     * POST [body] (already-serialized) to [url] with [contentType] and optional
     * extra [headers]. Implementations must not throw on non-2xx; they return the
     * status code so the caller decides. A transport failure (no response) throws
     * [java.io.IOException] so WorkManager can retry.
     */
    fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): Response

    /**
     * GET [url] with optional extra [headers]. Used for unauthenticated OAuth
     * client discovery. Same contract as [postJson]: must not throw on non-2xx
     * (return the code); a transport failure throws [java.io.IOException].
     */
    fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Response

    companion object {
        const val CONTENT_TYPE_JSON = "application/json"
    }
}
