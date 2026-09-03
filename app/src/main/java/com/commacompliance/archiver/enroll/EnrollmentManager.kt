package com.commacompliance.archiver.enroll

import com.commacompliance.archiver.config.ConfigProvider
import com.commacompliance.archiver.net.HttpsUrls

/**
 * Coordinates the enrollment lifecycle for the upload path: it returns a usable
 * enrollment, performing (or repeating) the two-step signed EMM handshake when
 * needed.
 *
 * Re-enroll is forced when:
 *   - there is no stored enrollment yet, or
 *   - the admin rotated the managed `enrollment_token` (detected by hash), or
 *   - the caller flags the current archive_token as rejected (HTTP 401).
 *
 * Because the device keys are stable and the server reissues idempotently for the
 * same device, a forced re-enroll with a still-valid managed token simply rotates
 * the archive_token (and possibly the destination key/kid), which the store
 * overwrites.
 *
 * Play Integrity attestation lives in [EnrollmentClient]: its requestHash binds to
 * the server challenge, which only exists mid-handshake, so the token must be
 * fetched between begin and complete. This manager just resolves the
 * server-advertised cloud_project_number from discovery and hands it to the client;
 * a null number (integrity unconfigured, or discovery failed) makes the enroll
 * proceed token-less, subject to server policy.
 */
class EnrollmentManager(
    private val configProvider: ConfigProvider,
    private val store: EnrollmentStore,
    private val client: EnrollmentClient,
    private val enrollmentSpecificIdProvider: () -> String?,
    private val deviceLabelProvider: () -> String?,
    // Looks up the server-advertised cloud_project_number for the managed backend;
    // null when integrity is not configured or discovery fails (-> token-less).
    private val cloudProjectNumberProvider: (backendUrl: String) -> Long? = { null },
) {
    sealed interface Outcome {
        data class Ready(val enrollment: EnrollmentStore.Enrollment) : Outcome

        /** Managed config is missing/incomplete; cannot enroll yet. */
        data object NotConfigured : Outcome

        /** The managed enrollment_token is rejected; needs admin to reprovision. */
        data object TokenRejected : Outcome

        /** Transient/recoverable failure; the caller should retry later. */
        data object Retry : Outcome
    }

    /**
     * Return a usable enrollment, enrolling if necessary. Set [forceReenroll] after
     * an upload got a 401 so a rotated/revoked archive_token is replaced.
     */
    fun ensureEnrolled(forceReenroll: Boolean = false): Outcome {
        val config = configProvider.current()
        if (!config.isComplete) {
            // No managed enrollment_token. A device provisioned via the self-serve
            // OAuth path runs on its stored archive_token (it has no managed token
            // to re-enroll against), so report it Ready. A MANAGED device whose
            // config is currently incomplete (not yet provisioned, or the admin
            // pulled the token) is NOT kept alive on a stale credential - it waits
            // for managed config to (re)appear. A device with neither is just not
            // set up yet.
            val existing = store.load()
            return if (!forceReenroll && existing != null && store.isSelfServe()) {
                Outcome.Ready(existing)
            } else {
                Outcome.NotConfigured
            }
        }
        // The begin request carries the managed enrollment_token (a bootstrap
        // credential) in its body, so gate the managed backend_url through
        // requireHttps: a non-https/malformed managed URL must NOT receive that
        // token. Treated as not-yet-configured (the admin must fix the URL) rather
        // than enrolling over cleartext.
        val backendUrl = HttpsUrls.requireHttps(config.backendUrl) ?: return Outcome.NotConfigured
        val enrollmentToken = config.enrollmentToken!!

        // Single-flight: heartbeat + upload + attachment WorkManager jobs can each
        // invoke ensureEnrolled concurrently; serialize the load -> enroll ->
        // store.save/clear section so two handshakes never clobber each other's
        // stored credential.
        return synchronized(ENROLL_LOCK) {
            reconcileEnrollment(backendUrl, enrollmentToken, forceReenroll)
        }
    }

    // The reconcile body, always run while holding ENROLL_LOCK. Double-checked: a
    // contender that blocked re-derives mustEnroll here (a fresh store.load()) and
    // reuses the winner's enrollment instead of redundantly re-running the handshake.
    private fun reconcileEnrollment(
        backendUrl: String,
        enrollmentToken: String,
        forceReenroll: Boolean,
    ): Outcome {
        val existing = store.load()
        val mustEnroll = forceReenroll || existing == null || store.enrollmentTokenChanged(enrollmentToken)
        if (!mustEnroll) return Outcome.Ready(existing!!)

        return when (val result = client.enroll(
            backendUrl = backendUrl,
            enrollmentToken = enrollmentToken,
            enrollmentSpecificId = enrollmentSpecificIdProvider(),
            deviceLabel = deviceLabelProvider(),
            cloudProjectNumber = cloudProjectNumberProvider(backendUrl),
        )) {
            is EnrollmentClient.Result.Success -> {
                store.save(result.enrollment, enrollmentToken)
                Outcome.Ready(result.enrollment)
            }
            EnrollmentClient.Result.InvalidToken,
            EnrollmentClient.Result.FingerprintMismatch,
            EnrollmentClient.Result.SigningKeyMismatch,
            -> {
                // The managed token cannot enroll this device: a dead/rotated token, a
                // single-use token already bound to another device, or a returning
                // device whose signing key no longer matches. None of these clear by
                // retrying with the same stable keys, so drop any stale archive_token
                // and wait for the admin to reprovision rather than loop. Clearing the
                // enrollment never touches the device keypair (a separate store), so a
                // fresh managed token re-presents the same identity.
                store.clear()
                Outcome.TokenRejected
            }
            is EnrollmentClient.Result.Forbidden,
            EnrollmentClient.Result.InvalidSignature,
            EnrollmentClient.Result.ChallengeExpired,
            is EnrollmentClient.Result.Failed,
            -> {
                // A recoverable or transient refusal: capture not yet enabled,
                // attestation momentarily unavailable, the reusable-token cap, rate
                // limiting (429), a transient signature/serialization fault, a
                // network/5xx, or a complete-stage challenge that expired/was consumed
                // between begin and complete. Keep any existing credential and let the
                // caller (a periodic WorkManager job with its own backoff) retry later:
                // because we never advanced the stored token/hash, the next pass re-runs
                // the full begin->complete handshake. Never a tight loop, and never nuke
                // a working enrollment over a transient.
                Outcome.Retry
            }
        }
    }

    companion object {
        // Process-wide monitor: only one enrollment handshake runs at a time across
        // all WorkManager jobs, so concurrent ensureEnrolled calls cannot race
        // store.save()/store.clear().
        private val ENROLL_LOCK = Any()
    }
}
