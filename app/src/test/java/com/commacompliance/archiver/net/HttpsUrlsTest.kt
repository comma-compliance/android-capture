package com.commacompliance.archiver.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The transport guard for any URL a bearer credential travels to. It must reject
 * cleartext, hostless, credential-embedding, and query/fragment-bearing URLs (any
 * of which could leak a token or misdirect an appended endpoint path) while
 * preserving a valid https URL UNCHANGED - including its path, since self-serve
 * backends may sit under a base path the upload endpoints append to.
 */
class HttpsUrlsTest {

    @Test
    fun returns_https_url_unchanged() {
        assertEquals("https://ingest.example.com", HttpsUrls.requireHttps("https://ingest.example.com"))
        assertEquals(
            "https://ingest.example.com:8443",
            HttpsUrls.requireHttps("https://ingest.example.com:8443"),
        )
    }

    @Test
    fun preserves_a_path_component() {
        // Self-serve backends can live under a base path; the upload code appends its
        // own path to this, so the base path MUST survive validation unchanged.
        assertEquals(
            "https://ingest.example.com/tenant/acme",
            HttpsUrls.requireHttps("https://ingest.example.com/tenant/acme"),
        )
        assertEquals(
            "https://ingest.example.com/base/",
            HttpsUrls.requireHttps("https://ingest.example.com/base/"),
        )
    }

    @Test
    fun trims_surrounding_whitespace_on_success() {
        // A managed/MDM config value can arrive with stray surrounding whitespace.
        // The validated value flows into URL(value).openConnection(), so the success
        // path must hand back the trimmed string - not the raw input - or the space
        // turns the graceful null path into a MalformedURLException at the call site.
        assertEquals(
            "https://ingest.example.com",
            HttpsUrls.requireHttps("  https://ingest.example.com  "),
        )
        // Path component survives the trim too.
        assertEquals(
            "https://ingest.example.com/tenant/acme",
            HttpsUrls.requireHttps("\thttps://ingest.example.com/tenant/acme\n"),
        )
        // Other validations still hold on padded input: cleartext is still rejected,
        // and an embedded credential is still rejected.
        assertNull(HttpsUrls.requireHttps("  http://ingest.example.com  "))
        assertNull(HttpsUrls.requireHttps("  https://user:pass@ingest.example.com  "))
    }

    @Test
    fun rejects_http_cleartext() {
        assertNull(HttpsUrls.requireHttps("http://ingest.example.com"))
    }

    @Test
    fun accepts_mixed_case_https_scheme() {
        // Scheme comparison is case-insensitive; the value is returned verbatim.
        assertEquals("HTTPS://ingest.example.com", HttpsUrls.requireHttps("HTTPS://ingest.example.com"))
    }

    @Test
    fun rejects_embedded_user_info() {
        assertNull(HttpsUrls.requireHttps("https://user:pass@ingest.example.com"))
        assertNull(HttpsUrls.requireHttps("https://user@ingest.example.com"))
    }

    @Test
    fun rejects_query_or_fragment() {
        // A '?'/'#' in the base would push an appended endpoint path into the query
        // string and silently target the wrong endpoint.
        assertNull(HttpsUrls.requireHttps("https://ingest.example.com/?x=1"))
        assertNull(HttpsUrls.requireHttps("https://ingest.example.com/#frag"))
    }

    @Test
    fun rejects_hostless_and_malformed_and_blank() {
        assertNull(HttpsUrls.requireHttps("https:///nohost"))
        assertNull(HttpsUrls.requireHttps("ftp://ingest.example.com"))
        assertNull(HttpsUrls.requireHttps("not a url"))
        assertNull(HttpsUrls.requireHttps(""))
        assertNull(HttpsUrls.requireHttps("   "))
        assertNull(HttpsUrls.requireHttps(null))
    }
}
