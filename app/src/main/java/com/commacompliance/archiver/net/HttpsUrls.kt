package com.commacompliance.archiver.net

import java.net.URI

/**
 * Transport guard for any URL a bearer credential will travel to.
 *
 * The upload, attachment-upload, and heartbeat passes all carry the long-lived
 * `archive_token` in an `Authorization: Bearer` header. That credential MUST NOT
 * leave the device over anything but HTTPS, so the backend URL is run through
 * [requireHttps] before it is ever used to build a request: a non-https, hostless,
 * or credential-embedding URL collapses to null, which every call-site already
 * treats as "not configured" (no request, retry/no-op) rather than posting in the
 * clear.
 *
 * This is deliberately distinct from the discovery anti-phishing origin pin
 * (`DiscoveryClient.httpsOrigin`, which reduces to a bare scheme+host[:port]
 * origin). Here the full URL - INCLUDING any path component - is preserved
 * unchanged, because the upload endpoints append their own paths to a configured
 * base (`backendUrl.trimEnd('/') + "/webhooks/..."`). A self-serve device whose
 * stored backend is `https://host/base` must keep working.
 */
object HttpsUrls {

    /**
     * Returns the TRIMMED [url] if and only if it is a usable HTTPS endpoint:
     * it parses, its scheme is `https` (case-insensitive), it has a non-blank
     * host, and it carries no embedded user-info, query, or fragment. Otherwise
     * null.
     *
     * The success value is the surrounding-whitespace-stripped string, NOT the raw
     * input. A managed backend URL can arrive with leading/trailing whitespace (an
     * MDM-pushed config value is easy to mis-enter), and the callers feed this value
     * straight into `URL(value).openConnection()`. A stray space there throws
     * `MalformedURLException` - the loud failure path - instead of the graceful
     * "not configured" (null) one. Validating the trimmed form but returning the raw
     * input would leak that whitespace to callers, so we return what we validated.
     *
     * Path is intentionally preserved (it is part of a self-serve backend base).
     * Query and fragment are rejected: the callers concatenate a path onto this
     * value, and a `?`/`#` in the base would push the appended path into the query
     * string and silently target the wrong endpoint.
     */
    fun requireHttps(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        return runCatching {
            val uri = URI(trimmed)
            if (!isHttpsWithHost(uri)) return null
            if (uri.userInfo != null) return null
            if (uri.rawQuery != null || uri.rawFragment != null) return null
            trimmed
        }.getOrNull()
    }

    /**
     * Shared validation core: the [uri] uses the https scheme (case-insensitive)
     * and has a non-blank host. `DiscoveryClient.httpsOrigin` reuses this so the
     * "is this an https endpoint with a host" rule lives in exactly one place,
     * while keeping its own origin-reduction (scheme+host+port) on top.
     */
    fun isHttpsWithHost(uri: URI): Boolean {
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        return !uri.host.isNullOrBlank()
    }
}
