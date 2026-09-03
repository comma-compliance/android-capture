package com.commacompliance.archiver.config

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class ConfigProviderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun setRestrictions(bundle: Bundle?) {
        val rm = context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        Shadows.shadowOf(rm).setApplicationRestrictions(bundle)
    }

    @Test
    fun reads_backend_url_and_enrollment_token() {
        setRestrictions(
            Bundle().apply {
                putString("backend_url", "https://ingest.example.com")
                putString("enrollment_token", "tok-abc")
            },
        )
        val config = ConfigProvider(context).current()
        assertEquals("https://ingest.example.com", config.backendUrl)
        assertEquals("tok-abc", config.enrollmentToken)
        assertTrue(config.isComplete)
    }

    @Test
    fun blank_values_are_treated_as_absent() {
        setRestrictions(
            Bundle().apply {
                putString("backend_url", "   ")
                putString("enrollment_token", "")
            },
        )
        val config = ConfigProvider(context).current()
        assertNull(config.backendUrl)
        assertNull(config.enrollmentToken)
        assertFalse(config.isComplete)
    }

    @Test
    fun missing_restrictions_is_incomplete() {
        setRestrictions(Bundle())
        val config = ConfigProvider(context).current()
        assertFalse(config.isComplete)
    }

    @Test
    fun cleartext_http_backend_url_is_incomplete() {
        setRestrictions(
            Bundle().apply {
                putString("backend_url", "http://ingest.example.com")
                putString("enrollment_token", "tok-abc")
            },
        )
        val config = ConfigProvider(context).current()
        assertEquals("http://ingest.example.com", config.backendUrl)
        assertFalse(config.isComplete)
    }

    @Test
    fun malformed_backend_url_is_incomplete() {
        setRestrictions(
            Bundle().apply {
                putString("backend_url", "https://")
                putString("enrollment_token", "tok-abc")
            },
        )
        assertFalse(ConfigProvider(context).current().isComplete)
    }

    @Test
    fun config_change_is_observed_on_next_read() {
        setRestrictions(Bundle().apply { putString("backend_url", "https://a"); putString("enrollment_token", "t1") })
        val provider = ConfigProvider(context)
        assertEquals("https://a", provider.current().backendUrl)

        // Simulate the EMM pushing new managed config.
        setRestrictions(Bundle().apply { putString("backend_url", "https://b"); putString("enrollment_token", "t2") })
        assertEquals("https://b", provider.current().backendUrl)
        assertEquals("t2", provider.current().enrollmentToken)
    }
}
