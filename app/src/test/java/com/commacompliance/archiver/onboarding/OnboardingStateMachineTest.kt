package com.commacompliance.archiver.onboarding

import androidx.test.core.app.ApplicationProvider
import com.commacompliance.archiver.FakeSharedPreferences
import com.commacompliance.archiver.config.ConfigProvider
import com.commacompliance.archiver.crypto.KeyManager
import com.commacompliance.archiver.crypto.TestSodium
import com.commacompliance.archiver.enroll.EnrollmentStore
import com.commacompliance.archiver.net.FakeHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The onboarding brain: which path the device is on (EMM vs self-serve), the
 * offline-first-run state + auto-retry decision, and the post-OAuth handshake
 * outcome mapping. All pure/host-tested so the Activity stays a thin renderer.
 */
@RunWith(RobolectricTestRunner::class)
class OnboardingStateMachineTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val box = TestSodium.box()
    private val keyManager = KeyManager(context, box) { FakeSharedPreferences() }
    private val store = EnrollmentStore(context) { FakeSharedPreferences() }

    private class StubConfig(private val config: ManagedConfig) :
        ConfigProvider(ApplicationProvider.getApplicationContext()) {
        override fun current() = config
    }

    private fun machine(
        managed: ConfigProvider.ManagedConfig,
        online: Boolean,
    ) = OnboardingStateMachine(
        configProvider = StubConfig(managed),
        store = store,
        connectivity = { online },
    )

    private val noManagedConfig = ConfigProvider.ManagedConfig(null, null)
    private val managedConfig = ConfigProvider.ManagedConfig("https://h", "enroll-token")

    // A RegistrationClient that returns a scripted result without any network.
    private class FakeRegistrationClient(
        private val keyManager: KeyManager,
        private val result: Result,
    ) : RegistrationClient(FakeHttpClient(), keyManager) {
        override fun register(
            backendOrigin: String,
            accessToken: String,
            membershipId: String?,
            enrollmentSpecificId: String?,
            deviceLabel: String?,
            cloudProjectNumber: Long?,
        ): Result = result
    }

    @Test
    fun path_is_managed_when_enrollment_token_present_else_self_serve() {
        assertEquals(OnboardingStateMachine.Path.MANAGED, machine(managedConfig, true).path())
        assertEquals(OnboardingStateMachine.Path.SELF_SERVE, machine(noManagedConfig, true).path())
    }

    @Test
    fun self_serve_offline_first_run_shows_offline_state() {
        val m = machine(noManagedConfig, online = false)
        assertEquals(OnboardingStateMachine.State.OfflineNoNetwork, m.initialState())
        // Tapping connect while offline must not launch auth; it shows offline.
        assertEquals(OnboardingStateMachine.State.OfflineNoNetwork, m.onConnectRequested())
    }

    @Test
    fun self_serve_online_first_run_prompts_to_connect() {
        val m = machine(noManagedConfig, online = true)
        assertEquals(OnboardingStateMachine.State.NotConfigured, m.initialState())
        assertEquals(OnboardingStateMachine.State.AwaitingAuth, m.onConnectRequested())
    }

    @Test
    fun managed_first_run_shows_automatic_enrolling() {
        val m = machine(managedConfig, online = true)
        assertEquals(OnboardingStateMachine.State.ManagedEnrolling, m.initialState())
    }

    @Test
    fun connected_device_reports_connected_regardless_of_path() {
        seedConnected(label = "acme.example.com")
        val m = machine(noManagedConfig, online = true)
        val state = m.initialState()
        state as OnboardingStateMachine.State.Connected
        assertEquals("acme.example.com", state.organizationLabel)
    }

    @Test
    fun auto_retry_on_reconnect_only_when_unconnected_and_online() {
        // Unconnected + back online -> retry.
        assertTrue(machine(noManagedConfig, online = true).shouldAutoRetryOnReconnect())
        // Unconnected + still offline -> no retry (avoids thrashing).
        assertFalse(machine(noManagedConfig, online = false).shouldAutoRetryOnReconnect())
        // Already connected -> never auto-retry onboarding.
        seedConnected(label = "x")
        assertFalse(machine(noManagedConfig, online = true).shouldAutoRetryOnReconnect())
    }

    @Test
    fun complete_self_serve_success_persists_and_reports_connected() {
        val enrollment = EnrollmentStore.Enrollment(
            archiveToken = "arc_tok",
            deviceId = "amdev_1",
            destinationPublicKey = box.generateKeyPair().publicKey,
            kid = "KID=",
            backfillDays = 3,
        )
        val m = machine(noManagedConfig, online = true)
        val state = m.completeSelfServe(
            registrationClient = FakeRegistrationClient(keyManager, RegistrationClient.Result.Success(enrollment)),
            backendOrigin = "https://acme.example.com",
            accessToken = "tok",
            membershipId = null,
            enrollmentSpecificId = null,
            deviceLabel = "Pixel",
            organizationLabel = "acme.example.com",
        )
        state as OnboardingStateMachine.State.Connected
        assertEquals("acme.example.com", state.organizationLabel)
        // Persisted as a self-serve enrollment with the backfill policy + label.
        assertTrue(store.isConnected())
        assertEquals(3, store.load()!!.backfillDays)
        assertEquals("acme.example.com", store.connectedLabel())
    }

    @Test
    fun complete_self_serve_maps_each_error() {
        val m = machine(noManagedConfig, online = true)
        fun run(result: RegistrationClient.Result) = m.completeSelfServe(
            registrationClient = FakeRegistrationClient(keyManager, result),
            backendOrigin = "https://h",
            accessToken = "t",
            membershipId = null,
            enrollmentSpecificId = null,
            deviceLabel = null,
            organizationLabel = null,
        )
        assertEquals(
            OnboardingStateMachine.State.Error(OnboardingStateMachine.Reason.FEATURE_DISABLED),
            run(RegistrationClient.Result.FeatureDisabled),
        )
        assertEquals(
            OnboardingStateMachine.State.Error(OnboardingStateMachine.Reason.REGISTRATION_EXPIRED),
            run(RegistrationClient.Result.RegistrationExpired),
        )
        assertEquals(
            OnboardingStateMachine.State.Error(OnboardingStateMachine.Reason.UNAUTHORIZED),
            run(RegistrationClient.Result.Unauthorized),
        )
        assertEquals(
            OnboardingStateMachine.State.Error(OnboardingStateMachine.Reason.NETWORK),
            run(RegistrationClient.Result.Failed(500)),
        )
        // A failed attempt must NOT leave a credential behind.
        assertFalse(store.isConnected())
    }

    private fun seedConnected(label: String) {
        store.saveSelfServe(
            EnrollmentStore.Enrollment(
                archiveToken = "arc",
                deviceId = "dev",
                destinationPublicKey = box.generateKeyPair().publicKey,
                kid = "k",
                backfillDays = 0,
            ),
        )
        store.setConnectedLabel(label)
    }
}
