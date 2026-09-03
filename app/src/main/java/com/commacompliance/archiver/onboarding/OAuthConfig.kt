package com.commacompliance.archiver.onboarding

import com.commacompliance.archiver.net.HttpClient
import com.commacompliance.archiver.net.HttpsUrls
import com.commacompliance.archiver.util.anyBlank
import org.json.JSONObject
import java.net.URI

/**
 * The OAuth client bootstrap the app fetches from the backend's discovery
 * endpoint so it never hardcodes a client_id:
 *
 *   GET {backend_url}/api/v1/android_archiver/config
 *     -> { client_id, authorization_endpoint, token_endpoint, scope,
 *          app_link_redirect_uri?, cloud_project_number?, integrity_mode? }
 *
 * Treated like an OpenID discovery document: HTTPS is mandatory, and the
 * authorization/token endpoints MUST live on the SAME origin as the org URL the
 * user typed. That pin is the anti-phishing guard - a typoed or hostile backend
 * URL can otherwise hand back endpoints on an attacker's domain and capture the
 * authorization code / token.
 *
 * The last three fields are OPTIONAL and version-negotiated: an older or
 * unconfigured server omits them and the client behaves exactly as before
 * (custom-scheme redirect, no integrity token). They are deliberately NOT subject
 * to the origin pin - [appLinkRedirectUri] legitimately points at the verified App
 * Link host (a different host from the org URL), which the redirect-selection code
 * validates against the app's own compiled `APP_LINK_HOST`, not the typed origin.
 */
data class OAuthConfig(
    val clientId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val scope: String,
    /**
     * Server-advertised HTTPS App Link redirect URI, or null when the server does
     * not advertise one. Used as the OAuth redirect_uri only when its host matches
     * the app's compiled App Link host (see AuthCoordinator); otherwise the client
     * falls back to the private-use custom scheme.
     */
    val appLinkRedirectUri: String? = null,
    /**
     * Google Cloud project number for Play Integrity standard requests, or null
     * when the server has not configured integrity. Null -> the client skips
     * integrity-token acquisition entirely.
     */
    val cloudProjectNumber: String? = null,
    /** Server integrity policy ("off" | "monitor" | "enforce"); informational to the client. */
    val integrityMode: String? = null,
) {
    /**
     * [cloudProjectNumber] parsed to the Long the Play Integrity API expects, or
     * null when absent or not a valid number. A malformed value degrades to
     * token-less rather than crashing the handshake.
     */
    fun cloudProjectNumberOrNull(): Long? = cloudProjectNumber?.toLongOrNull()
}

class DiscoveryClient(private val http: HttpClient) {

    sealed interface Result {
        data class Success(val config: OAuthConfig) : Result

        /** The backend URL is not a usable https origin. */
        data object InvalidBackendUrl : Result

        /** 2xx but the body is missing fields or the endpoints fail the origin pin. */
        data object Untrusted : Result

        /** Non-2xx (e.g. feature not available); carries the status for diagnostics only. */
        data class Failed(val code: Int) : Result
    }

    fun discover(backendUrl: String): Result {
        val origin = httpsOrigin(backendUrl) ?: return Result.InvalidBackendUrl

        val response = http.getJson(url = origin + CONFIG_PATH)
        if (response.code !in 200..299) return Result.Failed(response.code)

        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: return Result.Untrusted
        val clientId = json.optString("client_id", "")
        val authEndpoint = json.optString("authorization_endpoint", "")
        val tokenEndpoint = json.optString("token_endpoint", "")
        val scope = json.optString("scope", "")
        if (anyBlank(clientId, authEndpoint, tokenEndpoint, scope)) {
            return Result.Untrusted
        }

        // Anti-phishing: the OAuth endpoints must be on the same https origin the
        // user typed. Reject endpoints that point anywhere else.
        if (!sameHttpsOrigin(origin, authEndpoint) || !sameHttpsOrigin(origin, tokenEndpoint)) {
            return Result.Untrusted
        }

        return Result.Success(
            OAuthConfig(
                clientId = clientId,
                authorizationEndpoint = authEndpoint,
                tokenEndpoint = tokenEndpoint,
                scope = scope,
                // Optional, version-negotiated fields: absent -> null, so an
                // un-upgraded server yields exactly today's OAuthConfig shape.
                appLinkRedirectUri = json.optNullableString("app_link_redirect_uri"),
                cloudProjectNumber = json.optNullableString("cloud_project_number"),
                integrityMode = json.optNullableString("integrity_mode"),
            ),
        )
    }

    /**
     * Fetch ONLY the Play Integrity cloud project number from the backend's
     * discovery config, for the EMM enroll path (which has no OAuth leg and so does
     * not need the anti-phishing origin pin). Returns null on ANY failure - non-https
     * backend, non-2xx, malformed body, absent/unparseable cloud_project_number - so
     * the caller proceeds token-less rather than blocking enroll. The managed
     * backend_url is already https-gated by EnrollmentManager before this is called.
     */
    fun fetchCloudProjectNumber(backendUrl: String): Long? {
        val origin = httpsOrigin(backendUrl) ?: return null
        val response = runCatching { http.getJson(url = origin + CONFIG_PATH) }.getOrNull() ?: return null
        if (response.code !in 200..299) return null
        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: return null
        return json.optNullableString("cloud_project_number")?.toLongOrNull()
    }

    companion object {
        const val CONFIG_PATH = "/api/v1/android_archiver/config"

        /**
         * Canonical `scheme://host[:port]` of [url] if and only if it is a valid
         * HTTPS URL with a host - else null. Cleartext, hosts-with-credentials, and
         * malformed inputs are rejected so a bearer/OAuth credential never travels
         * over a plaintext or ambiguous endpoint.
         */
        fun httpsOrigin(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return runCatching {
                val uri = URI(url.trim())
                // Shared "is this an https endpoint with a host" predicate; this
                // method layers its own origin-reduction (scheme+host+port,
                // credentials rejected) on top for the anti-phishing pin.
                if (!HttpsUrls.isHttpsWithHost(uri)) return null
                if (uri.userInfo != null) return null
                val host = uri.host
                val portPart = if (uri.port == -1) "" else ":${uri.port}"
                "https://${host.lowercase()}$portPart"
            }.getOrNull()
        }

        private fun sameHttpsOrigin(origin: String, candidate: String): Boolean {
            val candidateOrigin = httpsOrigin(candidate) ?: return false
            return candidateOrigin == origin
        }
    }
}

/**
 * Read an optional string field, returning null (not "") when the key is absent,
 * explicitly JSON null, or blank - so an un-upgraded server that omits the field
 * is indistinguishable from one that sends an empty value.
 */
private fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name, "").ifBlank { null }
