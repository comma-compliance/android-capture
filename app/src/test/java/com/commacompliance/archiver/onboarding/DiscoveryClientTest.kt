package com.commacompliance.archiver.onboarding

import com.commacompliance.archiver.net.FakeHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The discovery client is the anti-phishing gate for the user-typed org URL: it
 * must reject non-HTTPS backends and any config whose OAuth endpoints do not live
 * on the same origin as the typed URL (which would let a hostile/typoed backend
 * redirect the authorization code to an attacker).
 */
@RunWith(RobolectricTestRunner::class)
class DiscoveryClientTest {

    private val origin = "https://acme.example.com"

    private fun config(auth: String, tokenEp: String): String = JSONObject().apply {
        put("client_id", "comma-android-messages-archiver")
        put("authorization_endpoint", auth)
        put("token_endpoint", tokenEp)
        put("scope", "android_archiver")
    }.toString()

    @Test
    fun success_when_endpoints_share_the_typed_origin() {
        val http = FakeHttpClient()
        http.enqueue(200, config("$origin/oauth/authorize", "$origin/oauth/token"))
        val result = DiscoveryClient(http).discover(origin)
        result as DiscoveryClient.Result.Success
        assertEquals("comma-android-messages-archiver", result.config.clientId)
        assertEquals("android_archiver", result.config.scope)
        // It fetches the config from the typed origin's discovery path.
        assertEquals(origin + DiscoveryClient.CONFIG_PATH, http.calls[0].url)
    }

    @Test
    fun rejects_non_https_backend_without_a_network_call() {
        val http = FakeHttpClient()
        val result = DiscoveryClient(http).discover("http://acme.example.com")
        assertEquals(DiscoveryClient.Result.InvalidBackendUrl, result)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun rejects_endpoints_on_a_different_origin() {
        val http = FakeHttpClient()
        // authorization_endpoint points at an attacker host - must be rejected.
        http.enqueue(200, config("https://evil.example.com/oauth/authorize", "$origin/oauth/token"))
        assertEquals(DiscoveryClient.Result.Untrusted, DiscoveryClient(http).discover(origin))
    }

    @Test
    fun rejects_token_endpoint_on_a_different_port() {
        val http = FakeHttpClient()
        http.enqueue(200, config("$origin/oauth/authorize", "https://acme.example.com:8443/oauth/token"))
        assertEquals(DiscoveryClient.Result.Untrusted, DiscoveryClient(http).discover(origin))
    }

    @Test
    fun missing_fields_are_untrusted() {
        val http = FakeHttpClient()
        http.enqueue(200, """{"client_id":"x"}""")
        assertEquals(DiscoveryClient.Result.Untrusted, DiscoveryClient(http).discover(origin))
    }

    @Test
    fun non_2xx_is_failed_with_status() {
        val http = FakeHttpClient()
        http.enqueue(404, "")
        assertEquals(DiscoveryClient.Result.Failed(404), DiscoveryClient(http).discover(origin))
    }

    @Test
    fun parses_optional_integrity_and_app_link_fields_when_present() {
        val http = FakeHttpClient()
        val body = JSONObject().apply {
            put("client_id", "comma-android-messages-archiver")
            put("authorization_endpoint", "$origin/oauth/authorize")
            put("token_endpoint", "$origin/oauth/token")
            put("scope", "android_archiver")
            put("app_link_redirect_uri", "https://app.commacompliance.com/android/oauth2redirect")
            put("cloud_project_number", "123456789012")
            put("integrity_mode", "monitor")
        }.toString()
        http.enqueue(200, body)

        val result = DiscoveryClient(http).discover(origin) as DiscoveryClient.Result.Success
        assertEquals("https://app.commacompliance.com/android/oauth2redirect", result.config.appLinkRedirectUri)
        assertEquals("123456789012", result.config.cloudProjectNumber)
        assertEquals(123456789012L, result.config.cloudProjectNumberOrNull())
        assertEquals("monitor", result.config.integrityMode)
    }

    @Test
    fun absent_optional_fields_are_null_so_un_upgraded_server_behaves_as_before() {
        val http = FakeHttpClient()
        // Exactly today's body shape - none of the new fields.
        http.enqueue(200, config("$origin/oauth/authorize", "$origin/oauth/token"))
        val result = DiscoveryClient(http).discover(origin) as DiscoveryClient.Result.Success
        assertNull(result.config.appLinkRedirectUri)
        assertNull(result.config.cloudProjectNumber)
        assertNull(result.config.cloudProjectNumberOrNull())
        assertNull(result.config.integrityMode)
    }

    @Test
    fun fetch_cloud_project_number_returns_value_when_present() {
        val http = FakeHttpClient()
        val body = JSONObject().apply {
            put("client_id", "x")
            put("authorization_endpoint", "$origin/a")
            put("token_endpoint", "$origin/t")
            put("scope", "s")
            put("cloud_project_number", "999888777666")
        }.toString()
        http.enqueue(200, body)
        assertEquals(999888777666L, DiscoveryClient(http).fetchCloudProjectNumber(origin))
        // It reads the same discovery path.
        assertEquals(origin + DiscoveryClient.CONFIG_PATH, http.calls[0].url)
    }

    @Test
    fun fetch_cloud_project_number_is_null_when_absent_or_unreachable() {
        // Absent field -> null (EMM enroll then proceeds token-less).
        val absent = FakeHttpClient()
        absent.enqueue(200, config("$origin/a", "$origin/t"))
        assertNull(DiscoveryClient(absent).fetchCloudProjectNumber(origin))

        // Non-2xx -> null, no throw.
        val failing = FakeHttpClient()
        failing.enqueue(503, "")
        assertNull(DiscoveryClient(failing).fetchCloudProjectNumber(origin))

        // Non-https backend -> null without a network call.
        val nonHttps = FakeHttpClient()
        assertNull(DiscoveryClient(nonHttps).fetchCloudProjectNumber("http://acme.example.com"))
        assertTrue(nonHttps.calls.isEmpty())
    }

    @Test
    fun https_origin_canonicalizes_and_rejects_bad_input() {
        assertEquals("https://acme.example.com", DiscoveryClient.httpsOrigin("https://ACME.example.com/some/path"))
        assertEquals("https://acme.example.com:9000", DiscoveryClient.httpsOrigin("https://acme.example.com:9000"))
        assertNull(DiscoveryClient.httpsOrigin("http://acme.example.com"))
        assertNull(DiscoveryClient.httpsOrigin("ftp://acme.example.com"))
        assertNull(DiscoveryClient.httpsOrigin("https://user:pass@acme.example.com"))
        assertNull(DiscoveryClient.httpsOrigin("not a url"))
        assertNull(DiscoveryClient.httpsOrigin(""))
        assertNull(DiscoveryClient.httpsOrigin(null))
    }
}
