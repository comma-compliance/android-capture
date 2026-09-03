package com.commacompliance.archiver.onboarding

import com.commacompliance.archiver.config.ConfigProvider
import com.commacompliance.archiver.enroll.EnrollmentManager
import com.commacompliance.archiver.enroll.EnrollmentStore

/**
 * The non-UI brain of onboarding. It decides which path the device is on and
 * what the user should see, so the Activity stays a thin renderer and every
 * decision is unit-testable on a host JVM.
 *
 * Path selection:
 *   - If EMM managed config carries an enrollment_token, this is a MANAGED device
 *     and onboarding is automatic (the EnrollmentManager handles it); the UI just
 *     reflects progress/errors, never prompting the user to sign in.
 *   - Otherwise the device is on the SELF-SERVE path: the user signs in to their
 *     organization in-app (OAuth/PKCE) and registers the device.
 *
 * The OAuth/registration network legs are driven by the Activity (Custom Tab is
 * inherently UI); this class owns the state transitions and the post-OAuth
 * handshake, both of which are pure and tested.
 */
class OnboardingStateMachine(
    private val configProvider: ConfigProvider,
    private val store: EnrollmentStore,
    private val connectivity: () -> Boolean,
) {
    /** Which onboarding path applies, decided from managed config. */
    enum class Path { MANAGED, SELF_SERVE }

    sealed interface State {
        /** No credential yet and no managed token: prompt the user to connect. */
        data object NotConfigured : State

        /** A managed enrollment_token is present; enrollment proceeds automatically. */
        data object ManagedEnrolling : State

        /** Self-serve: waiting for the user to complete the Custom Tab sign-in. */
        data object AwaitingAuth : State

        /** Self-serve: OAuth done, running the begin/sign/complete handshake. */
        data object Registering : State

        /** First-run sign-in needs the network and the device is offline. */
        data object OfflineNoNetwork : State

        /** Connected and archiving for [organizationLabel] (may be null if unlabeled). */
        data class Connected(val organizationLabel: String?) : State

        /** A recoverable error with a plain, user-facing message. */
        data class Error(val reason: Reason) : State
    }

    /** User-facing error categories; the UI maps each to a plain-language string. */
    enum class Reason { FEATURE_DISABLED, REGISTRATION_EXPIRED, UNAUTHORIZED, NETWORK, DISCOVERY_UNTRUSTED, INVALID_URL }

    /** The path this device is on, derived from managed config. */
    fun path(): Path =
        if (configProvider.current().isComplete) Path.MANAGED else Path.SELF_SERVE

    /**
     * The state to show on (re)entry to the onboarding/status screen, before the
     * user takes any action. Connected wins regardless of path. A managed device
     * with no credential yet is "enrolling"; a self-serve device with no
     * credential is "not configured" (and surfaces offline if it can't reach the
     * network for first-run sign-in).
     */
    fun initialState(): State {
        if (store.isConnected()) return State.Connected(store.connectedLabel())
        return when (path()) {
            Path.MANAGED -> State.ManagedEnrolling
            Path.SELF_SERVE -> if (connectivity()) State.NotConfigured else State.OfflineNoNetwork
        }
    }

    /**
     * The state when the user taps "Connect" on the self-serve path. If offline,
     * we do NOT launch the Custom Tab (sign-in needs the network); we show the
     * offline state and the caller schedules an auto-retry on reconnect.
     */
    fun onConnectRequested(): State =
        if (connectivity()) State.AwaitingAuth else State.OfflineNoNetwork

    /**
     * Drive the post-OAuth registration handshake to a terminal state. The
     * Activity calls this once it holds an OAuth access token. On success the
     * enrollment is persisted (self-serve) and the connected label recorded.
     */
    // LongParameterList: this is the single funnel for the post-OAuth handshake, so
    // it necessarily threads every begin/complete input (the injected client, the
    // origin + token, the device-binding fields, the label, and the integrity
    // project number). Bundling them into a holder would only move the same fields
    // one layer out without making the call site clearer.
    @Suppress("LongParameterList")
    fun completeSelfServe(
        registrationClient: RegistrationClient,
        backendOrigin: String,
        accessToken: String,
        membershipId: String?,
        enrollmentSpecificId: String?,
        deviceLabel: String?,
        organizationLabel: String?,
        // Discovery's cloud_project_number, parsed to Long; null when the server has
        // not configured Play Integrity (then registration runs token-less).
        cloudProjectNumber: Long? = null,
    ): State {
        val result = registrationClient.register(
            backendOrigin = backendOrigin,
            accessToken = accessToken,
            membershipId = membershipId,
            enrollmentSpecificId = enrollmentSpecificId,
            deviceLabel = deviceLabel,
            cloudProjectNumber = cloudProjectNumber,
        )
        return when (result) {
            is RegistrationClient.Result.Success -> {
                store.saveSelfServe(result.enrollment)
                store.setConnectedLabel(organizationLabel)
                State.Connected(organizationLabel)
            }
            RegistrationClient.Result.FeatureDisabled -> State.Error(Reason.FEATURE_DISABLED)
            RegistrationClient.Result.RegistrationExpired -> State.Error(Reason.REGISTRATION_EXPIRED)
            RegistrationClient.Result.Unauthorized -> State.Error(Reason.UNAUTHORIZED)
            is RegistrationClient.Result.Failed -> State.Error(Reason.NETWORK)
        }
    }

    /**
     * Drive the managed (EMM) enrollment to a status the UI can show. Delegates to
     * the existing EnrollmentManager; offline is surfaced rather than failing.
     */
    fun completeManaged(enrollmentManager: EnrollmentManager): State {
        if (!connectivity()) return State.OfflineNoNetwork
        return when (enrollmentManager.ensureEnrolled()) {
            is EnrollmentManager.Outcome.Ready -> {
                val label = store.connectedLabel()
                State.Connected(label)
            }
            EnrollmentManager.Outcome.NotConfigured -> State.NotConfigured
            EnrollmentManager.Outcome.TokenRejected -> State.Error(Reason.UNAUTHORIZED)
            EnrollmentManager.Outcome.Retry -> State.Error(Reason.NETWORK)
        }
    }

    /**
     * Whether a connectivity-regained signal should trigger an automatic retry of
     * first-run onboarding. Only when we are not yet connected AND the network is
     * now up - so a flapping network does not thrash an already-connected device.
     */
    fun shouldAutoRetryOnReconnect(): Boolean = !store.isConnected() && connectivity()
}
