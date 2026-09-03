package com.commacompliance.archiver.onboarding

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.commacompliance.archiver.R
import com.commacompliance.archiver.config.ConfigProvider
import com.commacompliance.archiver.crypto.KeyManager
import com.commacompliance.archiver.crypto.LazySodiumNaclBox
import com.commacompliance.archiver.enroll.EnrollmentStore
import com.commacompliance.archiver.integrity.PlayIntegrityTokenSource
import com.commacompliance.archiver.net.Connectivity
import com.commacompliance.archiver.net.UrlConnectionHttpClient
import com.commacompliance.archiver.upload.HeartbeatWorker
import com.commacompliance.archiver.upload.ReconcileWorker
import com.commacompliance.archiver.upload.UploadWorker
import com.commacompliance.archiver.upload.UploaderFactory
import com.commacompliance.archiver.util.applySystemWindowInsetsAsPadding
import io.sentry.Sentry
import io.sentry.SentryLevel
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.ClientAuthentication
import net.openid.appauth.NoClientAuthentication

/**
 * The entry/connect screen. On the self-serve path it asks for the organization
 * URL, then launches the Custom Tab sign-in and runs the device-registration
 * handshake. On the managed path it shows automatic-setup progress and never
 * prompts for sign-in. A connected device is sent straight to the Status screen.
 *
 * The OAuth access token is held only for the duration of the handshake and is
 * never persisted or logged.
 */
// TooManyFunctions: an Activity legitimately accumulates lifecycle callbacks plus
// small single-purpose UI helpers (showProgress/showMessage/showError/hideStatus/
// render/bindViews). They are each trivial and splitting them out would scatter the
// screen's wiring without improving it.
@Suppress("TooManyFunctions")
class OnboardingActivity : AppCompatActivity() {

    private lateinit var store: EnrollmentStore
    private lateinit var stateMachine: OnboardingStateMachine
    private lateinit var authCoordinator: AuthCoordinator
    private lateinit var registrationClient: RegistrationClient
    private lateinit var discoveryClient: DiscoveryClient

    private lateinit var orgUrlLayout: TextInputLayout
    private lateinit var orgUrl: TextInputEditText
    private lateinit var connectButton: MaterialButton
    private lateinit var statusMessage: TextView
    private lateinit var progress: ProgressBar
    private lateinit var intro: TextView
    private lateinit var signupHint: TextView

    // The backend origin (https, validated) the user is connecting to, captured
    // when discovery succeeds so the post-auth handshake targets the same host.
    private var pendingBackendOrigin: String? = null

    // The discovery config from the same successful discovery, held so the post-auth
    // handshake can read the server-advertised cloud_project_number for attestation.
    private var pendingConfig: OAuthConfig? = null

    // Single-flight guard for the managed enrollment, which render() would otherwise
    // re-launch on every onResume while an attempt is still running.
    private var managedEnrollmentInFlight = false

    // The last managed-enrollment outcome reported to Sentry, so a failure that does
    // not change is reported once rather than once per onResume.
    private var lastReportedEnrollmentOutcome: String? = null

    private val authLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> onAuthResult(result) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        findViewById<View>(android.R.id.content).applySystemWindowInsetsAsPadding()

        val box = LazySodiumNaclBox(LazySodiumAndroid(SodiumAndroid()))
        val keyManager = KeyManager(this, box)
        val http = UrlConnectionHttpClient()
        store = EnrollmentStore(this)
        stateMachine = OnboardingStateMachine(
            configProvider = ConfigProvider(this),
            store = store,
            connectivity = { Connectivity.isOnline(this) },
        )
        authCoordinator = AuthCoordinator(this)
        // The integrity source attests the device at the complete step. It degrades
        // to a null token when Play services are absent, so sign-in is never blocked.
        registrationClient = RegistrationClient(http, keyManager, PlayIntegrityTokenSource(this))
        discoveryClient = DiscoveryClient(http)

        bindViews()
        connectButton.setOnClickListener { onConnectClicked() }
    }

    override fun onResume() {
        super.onResume()
        // A connected device belongs on the Status screen.
        if (store.isConnected()) {
            startActivity(Intent(this, StatusActivity::class.java))
            finish()
            return
        }
        render(stateMachine.initialState())
    }

    override fun onDestroy() {
        authCoordinator.dispose()
        super.onDestroy()
    }

    private fun bindViews() {
        orgUrlLayout = findViewById(R.id.orgUrlLayout)
        orgUrl = findViewById(R.id.orgUrl)
        connectButton = findViewById(R.id.connectButton)
        statusMessage = findViewById(R.id.statusMessage)
        progress = findViewById(R.id.progress)
        intro = findViewById(R.id.intro)
        signupHint = findViewById(R.id.signupHint)
    }

    private fun onConnectClicked() {
        val typed = orgUrl.text?.toString().orEmpty()
        val origin = DiscoveryClient.httpsOrigin(typed)
        if (origin == null) {
            orgUrlLayout.error = getString(R.string.error_invalid_url)
            return
        }
        orgUrlLayout.error = null

        when (stateMachine.onConnectRequested()) {
            OnboardingStateMachine.State.OfflineNoNetwork -> {
                render(OnboardingStateMachine.State.OfflineNoNetwork)
                return
            }
            else -> Unit
        }

        showProgress(getString(R.string.connect_progress_auth))
        // Discovery + auth-intent build touch the network; do them off the main thread.
        lifecycleScope.launch {
            val discovery = withContext(Dispatchers.IO) { discoveryClient.discover(origin) }
            when (discovery) {
                is DiscoveryClient.Result.Success -> {
                    pendingBackendOrigin = origin
                    pendingConfig = discovery.config
                    launchAuth(discovery.config)
                }
                DiscoveryClient.Result.InvalidBackendUrl ->
                    showError(getString(R.string.error_invalid_url))
                DiscoveryClient.Result.Untrusted ->
                    showError(getString(R.string.error_untrusted))
                is DiscoveryClient.Result.Failed ->
                    showError(getString(R.string.error_network))
            }
        }
    }

    // TooGenericExceptionCaught: launching the auth intent can fail with
    // ActivityNotFoundException (no browser/Custom Tab) or other AppAuth errors; a
    // broad catch keeps the screen alive and shows a recoverable error instead of
    // crashing onboarding.
    @Suppress("TooGenericExceptionCaught")
    private fun launchAuth(config: OAuthConfig) {
        try {
            authLauncher.launch(authCoordinator.buildAuthIntent(config))
        } catch (t: Throwable) {
            // No browser/Custom Tab available, or AppAuth could not start.
            Log.w(TAG, "could not launch authorization: ${t.javaClass.simpleName}")
            showError(getString(R.string.error_network))
        }
    }

    private fun onAuthResult(result: ActivityResult) {
        val data = result.data
        if (data == null) {
            showError(getString(R.string.error_unauthorized))
            return
        }
        val response = AuthorizationResponse.fromIntent(data)
        val exception = AuthorizationException.fromIntent(data)
        if (response == null || exception != null) {
            showError(getString(R.string.error_unauthorized))
            return
        }

        showProgress(getString(R.string.connect_progress_registering))
        val auth: ClientAuthentication = NoClientAuthentication.INSTANCE
        authCoordinator.service().performTokenRequest(
            response.createTokenExchangeRequest(),
            auth,
        ) { tokenResponse, tokenException ->
            val accessToken = tokenResponse?.accessToken
            if (accessToken.isNullOrBlank() || tokenException != null) {
                showError(getString(R.string.error_unauthorized))
            } else {
                runHandshake(accessToken)
            }
        }
    }

    /**
     * Drive an EMM-managed enrollment to a terminal state and show the result.
     *
     * Guarded by [managedEnrollmentInFlight] because [render] runs on every
     * onResume: without it, returning to a stuck screen would stack another
     * enrollment attempt on top of the one still running.
     *
     * A non-Connected outcome is reported to Sentry as well as shown. A managed
     * device has no one sitting in front of it to describe the symptom - the whole
     * point of the path is that setup is automatic - so a silent failure here is a
     * device that never archives and never says so. Only the outcome class is sent:
     * no token, no URL, no message content.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun runManagedEnrollment() {
        if (managedEnrollmentInFlight) return
        managedEnrollmentInFlight = true
        lifecycleScope.launch {
            val state = try {
                withContext(Dispatchers.IO) {
                    stateMachine.completeManaged(UploaderFactory.enrollmentManagerFor(applicationContext))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // ensureEnrolled() maps the failures it anticipates onto an Outcome,
                // but the HTTP and keystore layers beneath it can still throw. An
                // escape here would be an unhandled exception on the main dispatcher -
                // i.e. onboarding crashes - so it is folded into the same reported
                // network error the mapped path produces.
                Log.w(TAG, "managed enrollment threw: ${e.javaClass.simpleName}")
                OnboardingStateMachine.State.Error(OnboardingStateMachine.Reason.NETWORK)
            } finally {
                // In a finally so a throw can never leave the guard stuck true, which
                // would wedge the screen with no way to retry short of a restart.
                managedEnrollmentInFlight = false
            }
            if (state is OnboardingStateMachine.State.Connected) {
                armBackgroundWork()
                startActivity(Intent(this@OnboardingActivity, StatusActivity::class.java))
                finish()
            } else {
                reportManagedEnrollmentFailure(state)
                render(state)
            }
        }
    }

    private fun reportManagedEnrollmentFailure(state: OnboardingStateMachine.State) {
        val outcome = when (state) {
            is OnboardingStateMachine.State.Error -> "error:${state.reason}"
            OnboardingStateMachine.State.OfflineNoNetwork -> "offline"
            OnboardingStateMachine.State.NotConfigured -> "not_configured"
            else -> state.javaClass.simpleName
        }
        Log.w(TAG, "managed enrollment did not connect: $outcome")

        // Not reported to Sentry:
        // - OfflineNoNetwork: no enrollment was even attempted, the screen already
        //   says so, and a device carried out of coverage would otherwise report on
        //   every glance at the app.
        // - a repeat of the outcome already reported by this screen: render() runs on
        //   every onResume, so an unchanged failure would otherwise send one event per
        //   foreground, burying the signal in duplicates of itself.
        if (state == OnboardingStateMachine.State.OfflineNoNetwork) return
        if (outcome == lastReportedEnrollmentOutcome) return
        lastReportedEnrollmentOutcome = outcome
        Sentry.captureMessage("Managed EMM enrollment did not connect: $outcome", SentryLevel.WARNING)
    }

    private fun runHandshake(accessToken: String) {
        val origin = pendingBackendOrigin
        if (origin == null) {
            showError(getString(R.string.error_network))
            return
        }
        val cloudProjectNumber = pendingConfig?.cloudProjectNumberOrNull()
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                store.setSelfServeBackendUrl(origin)
                stateMachine.completeSelfServe(
                    registrationClient = registrationClient,
                    backendOrigin = origin,
                    accessToken = accessToken,
                    membershipId = null,
                    enrollmentSpecificId = null,
                    deviceLabel = deviceLabel(),
                    organizationLabel = origin.removePrefix("https://"),
                    cloudProjectNumber = cloudProjectNumber,
                )
            }
            if (state is OnboardingStateMachine.State.Connected) {
                armBackgroundWork()
                startActivity(Intent(this@OnboardingActivity, StatusActivity::class.java))
                finish()
            } else {
                render(state)
            }
        }
    }

    /**
     * Once connected, register the periodic heartbeat and nudge the upload/reconcile
     * drains so an idle device stays visible and a first-run backfill begins
     * immediately - rather than waiting for the first message broadcast.
     */
    private fun armBackgroundWork() {
        HeartbeatWorker.schedule(this)
        ReconcileWorker.enqueue(this)
        UploadWorker.enqueue(this)
    }

    private fun render(state: OnboardingStateMachine.State) {
        val selfServe = stateMachine.path() == OnboardingStateMachine.Path.SELF_SERVE
        orgUrlLayout.visibility = if (selfServe) View.VISIBLE else View.GONE
        connectButton.visibility = if (selfServe) View.VISIBLE else View.GONE
        signupHint.visibility = if (selfServe) View.VISIBLE else View.GONE
        intro.text = getString(if (selfServe) R.string.connect_intro else R.string.connect_managed_intro)

        when (state) {
            OnboardingStateMachine.State.NotConfigured -> hideStatus()
            OnboardingStateMachine.State.ManagedEnrolling -> {
                showProgress(getString(R.string.connect_progress_registering))
                // Actually START the enrollment. Rendering this state used to only
                // show the spinner, leaving the managed path with no driver in the
                // UI at all: the screen sat on "Registering this device..." forever
                // unless a background worker happened to enroll, and if enrollment
                // failed the user was never told and nothing was reported.
                runManagedEnrollment()
            }
            OnboardingStateMachine.State.AwaitingAuth ->
                showProgress(getString(R.string.connect_progress_auth))
            OnboardingStateMachine.State.Registering ->
                showProgress(getString(R.string.connect_progress_registering))
            OnboardingStateMachine.State.OfflineNoNetwork ->
                showMessage(getString(R.string.offline_message))
            is OnboardingStateMachine.State.Connected -> {
                startActivity(Intent(this, StatusActivity::class.java))
                finish()
            }
            is OnboardingStateMachine.State.Error -> showError(messageFor(state.reason))
        }
    }

    private fun messageFor(reason: OnboardingStateMachine.Reason): String = getString(
        when (reason) {
            OnboardingStateMachine.Reason.FEATURE_DISABLED -> R.string.error_feature_disabled
            OnboardingStateMachine.Reason.REGISTRATION_EXPIRED -> R.string.error_registration_expired
            OnboardingStateMachine.Reason.UNAUTHORIZED -> R.string.error_unauthorized
            OnboardingStateMachine.Reason.NETWORK -> R.string.error_network
            OnboardingStateMachine.Reason.DISCOVERY_UNTRUSTED -> R.string.error_untrusted
            OnboardingStateMachine.Reason.INVALID_URL -> R.string.error_invalid_url
        },
    )

    private fun showProgress(message: String) {
        progress.visibility = View.VISIBLE
        statusMessage.visibility = View.VISIBLE
        statusMessage.text = message
        connectButton.isEnabled = false
    }

    private fun showMessage(message: String) {
        progress.visibility = View.GONE
        statusMessage.visibility = View.VISIBLE
        statusMessage.text = message
        connectButton.isEnabled = true
    }

    private fun showError(message: String) {
        progress.visibility = View.GONE
        statusMessage.visibility = View.VISIBLE
        statusMessage.text = message
        connectButton.isEnabled = true
    }

    private fun hideStatus() {
        progress.visibility = View.GONE
        statusMessage.visibility = View.GONE
        connectButton.isEnabled = true
    }

    private fun deviceLabel(): String =
        listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")

    companion object {
        private const val TAG = "OnboardingActivity"
    }
}
