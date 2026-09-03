package com.commacompliance.archiver.onboarding

import com.commacompliance.archiver.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The redirect-negotiation matrix: the server may advertise an HTTPS App Link
 * redirect, but the client only honours it when its host matches the host this
 * build was compiled to claim (BuildConfig.APP_LINK_HOST). Anything else falls
 * back to the registered private-use custom scheme - the same value the OAuth
 * client is seeded with and the destination the browser fallback page forwards to.
 *
 * These guard the security boundary (a server cannot redirect the OAuth code to an
 * arbitrary host) and the un-upgraded-server path (no advertised URI -> custom
 * scheme, exactly as before).
 */
@RunWith(RobolectricTestRunner::class)
class AuthCoordinatorTest {

    private val compiledHost = BuildConfig.APP_LINK_HOST

    private fun config(appLinkRedirectUri: String?) = OAuthConfig(
        clientId = "c",
        authorizationEndpoint = "https://acme.example.com/a",
        tokenEndpoint = "https://acme.example.com/t",
        scope = "android_archiver",
        appLinkRedirectUri = appLinkRedirectUri,
    )

    @Test
    fun uses_app_link_when_it_is_the_exact_claimable_uri() {
        val advertised = "https://$compiledHost/android/oauth2redirect"
        assertEquals(advertised, AuthCoordinator.selectRedirectUri(config(advertised)))
    }

    @Test
    fun rejects_http_app_link_so_the_code_never_travels_cleartext() {
        // Right host + path but cleartext: must NOT be honoured (would leak the code).
        val cleartext = "http://$compiledHost/android/oauth2redirect"
        assertEquals(
            AuthCoordinator.CUSTOM_SCHEME_REDIRECT_URI,
            AuthCoordinator.selectRedirectUri(config(cleartext)),
        )
    }

    @Test
    fun rejects_app_link_with_a_path_this_build_does_not_claim() {
        // Right host but a path the manifest's autoVerify filter does not declare.
        val wrongPath = "https://$compiledHost/some/other/path"
        assertEquals(
            AuthCoordinator.CUSTOM_SCHEME_REDIRECT_URI,
            AuthCoordinator.selectRedirectUri(config(wrongPath)),
        )
    }

    @Test
    fun rejects_app_link_with_embedded_userinfo() {
        val withUserInfo = "https://evil@$compiledHost/android/oauth2redirect"
        assertEquals(
            AuthCoordinator.CUSTOM_SCHEME_REDIRECT_URI,
            AuthCoordinator.selectRedirectUri(config(withUserInfo)),
        )
    }

    @Test
    fun rejects_app_link_with_a_query_string() {
        val withQuery = "https://$compiledHost/android/oauth2redirect?x=1"
        assertEquals(
            AuthCoordinator.CUSTOM_SCHEME_REDIRECT_URI,
            AuthCoordinator.selectRedirectUri(config(withQuery)),
        )
    }

    @Test
    fun falls_back_to_custom_scheme_when_no_uri_advertised() {
        // The un-upgraded / unconfigured server case: behaves exactly as before.
        assertEquals(
            AuthCoordinator.CUSTOM_SCHEME_REDIRECT_URI,
            AuthCoordinator.selectRedirectUri(config(null)),
        )
    }

    @Test
    fun falls_back_to_custom_scheme_on_host_mismatch() {
        // A server advertising a host this build does not claim must be ignored.
        val foreign = "https://attacker.example.com/android/oauth2redirect"
        assertEquals(
            AuthCoordinator.CUSTOM_SCHEME_REDIRECT_URI,
            AuthCoordinator.selectRedirectUri(config(foreign)),
        )
    }

    @Test
    fun host_match_is_case_insensitive() {
        val advertised = "https://${compiledHost.uppercase()}/android/oauth2redirect"
        assertEquals(advertised, AuthCoordinator.selectRedirectUri(config(advertised)))
    }

    @Test
    fun falls_back_to_custom_scheme_on_unparseable_uri() {
        assertEquals(
            AuthCoordinator.CUSTOM_SCHEME_REDIRECT_URI,
            AuthCoordinator.selectRedirectUri(config("not a uri ::::")),
        )
    }
}
