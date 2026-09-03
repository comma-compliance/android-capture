package com.commacompliance.archiver.onboarding

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.commacompliance.archiver.BuildConfig
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import java.net.URI

/**
 * Thin AppAuth glue for the Authorization-Code + PKCE flow. AppAuth generates and
 * verifies the PKCE code_verifier/challenge and launches the authorization
 * request in a Chrome Custom Tab - there is NO embedded WebView (an embedded
 * WebView would let the app observe the user's org credentials and is rejected by
 * RFC 8252 / Google sign-in).
 *
 * Deliberately kept minimal: the OAuth endpoints come from the backend's
 * discovery config (already origin-pinned to the typed org URL by
 * [DiscoveryClient]), and the only scope is the dedicated device-registration
 * scope. The token-exchange result is handled by the calling Activity, which holds
 * the access token in memory only.
 *
 * The redirect URI is NEGOTIATED: the server may advertise a verified HTTPS App
 * Link redirect (`app_link_redirect_uri`); the client uses it only when its host
 * matches the app's own compiled [BuildConfig.APP_LINK_HOST] (so a hostile/typoed
 * server cannot redirect the code anywhere it likes), otherwise it falls back to
 * the registered private-use [CUSTOM_SCHEME_REDIRECT_URI]. The SAME redirect URI
 * is reused for the token exchange: AppAuth derives the token request's redirect
 * from the original AuthorizationRequest, so building the request with the chosen
 * URI keeps both legs identical regardless of whether the App Link verified
 * on-device or the browser fallback caught it.
 */
class AuthCoordinator(context: Context) {
    private val authService = AuthorizationService(context.applicationContext)

    /**
     * Build the authorization intent for [config]. The caller launches it with an
     * ActivityResultLauncher and feeds the result back to AppAuth's
     * AuthorizationResponse to perform the token exchange.
     */
    fun buildAuthIntent(config: OAuthConfig): Intent {
        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse(config.authorizationEndpoint),
            Uri.parse(config.tokenEndpoint),
        )
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            config.clientId,
            ResponseTypeValues.CODE,
            Uri.parse(selectRedirectUri(config)),
        )
            .setScope(config.scope)
            .build()
        return authService.getAuthorizationRequestIntent(request)
    }

    fun service(): AuthorizationService = authService

    fun dispose() {
        authService.dispose()
    }

    companion object {
        private const val TAG = "AuthCoordinator"

        // Matches the appAuthRedirectScheme manifest placeholder and the custom-
        // scheme redirect URI seeded on the server's Doorkeeper client. The baseline
        // redirect, used whenever the server does not advertise a host-matching App
        // Link URI, and the destination the browser fallback page forwards to.
        const val CUSTOM_SCHEME_REDIRECT_URI = "com.commacompliance.archiver:/oauth2redirect"

        // The exact App Link path the manifest's autoVerify HTTPS intent-filter
        // claims. Only this path is verifiable on-device, so only this path may be
        // honoured as a redirect target.
        private const val APP_LINK_PATH = "/android/oauth2redirect"

        /**
         * Choose the OAuth redirect URI for [config].
         *
         * The server-advertised [OAuthConfig.appLinkRedirectUri] wins ONLY when it
         * is EXACTLY the App Link this build can claim: https scheme, host ==
         * [BuildConfig.APP_LINK_HOST], path == [APP_LINK_PATH], no embedded
         * user-info, and no query or fragment. Anything else (absent, unparseable,
         * http, foreign host, a different path, a credential-bearing or
         * query-laden URI) falls back to the private-use custom scheme.
         *
         * Matching the whole URI - not just the host - is the trust boundary: the
         * manifest only declares the autoVerify filter for
         * `https://${appLinkHost}/android/oauth2redirect`, so any other shape is
         * either unverifiable on-device or, worse (http), would push the OAuth code
         * over cleartext. A server cannot talk this client into redirecting the code
         * anywhere it was not built and verified to receive it. Rejections log the
         * class name only (never the URI/code/token).
         *
         * Pure and side-effect-free (bar the warning log) so the negotiation matrix
         * is unit-tested without AppAuth or an Activity.
         */
        fun selectRedirectUri(config: OAuthConfig): String {
            val advertised = config.appLinkRedirectUri ?: return CUSTOM_SCHEME_REDIRECT_URI
            return if (isClaimableAppLink(advertised)) {
                advertised
            } else {
                // Server advertised an App Link redirect this build cannot claim;
                // ignore it and use the custom scheme. Class name only - never the URI.
                Log.w(TAG, "advertised app_link_redirect_uri is not the verifiable App Link for this build; using custom scheme")
                CUSTOM_SCHEME_REDIRECT_URI
            }
        }

        // True iff [uri] is byte-for-byte the App Link the manifest's autoVerify
        // filter claims: https, host == compiled APP_LINK_HOST, path == APP_LINK_PATH,
        // no userinfo, no query, no fragment. Any parse failure is a non-match.
        private fun isClaimableAppLink(uri: String): Boolean = runCatching {
            val parsed = URI(uri)
            parsed.scheme.equals("https", ignoreCase = true) &&
                parsed.userInfo == null &&
                parsed.host != null &&
                parsed.host.equals(BuildConfig.APP_LINK_HOST, ignoreCase = true) &&
                parsed.path == APP_LINK_PATH &&
                parsed.rawQuery == null &&
                parsed.rawFragment == null
        }.getOrDefault(false)
    }
}
