package com.commacompliance.archiver.config

import android.content.Context
import android.content.RestrictionsManager

/**
 * Reads the EMM-delivered managed configuration (`backend_url`,
 * `enrollment_token`) via [RestrictionsManager]. These keys are declared in
 * res/xml/app_restrictions.xml and are set by the enterprise administrator, not
 * the user.
 *
 * Callers observe changes by registering a receiver for
 * `Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED` (see
 * ManagedConfigChangeReceiver) and re-reading via [current].
 */
open class ConfigProvider(context: Context) {
    private val appContext = context.applicationContext

    data class ManagedConfig(
        val backendUrl: String?,
        val enrollmentToken: String?,
        /**
         * Optional first-run capture policy set directly by the EMM. Null when the
         * admin did not set it, in which case the device uses the value returned by
         * enroll/registration.
         */
        val backfillDays: Int? = null,
    ) {
        /**
         * Both values must be present and non-blank before enrollment can proceed,
         * and backend_url MUST be https. Bearer tokens and the one-time enrollment
         * token travel on this URL, so a misconfigured http:// host is treated as
         * not-configured rather than churning retries against a cleartext endpoint
         * the platform blocks anyway.
         */
        val isComplete: Boolean
            get() = !enrollmentToken.isNullOrBlank() && isHttpsUrl(backendUrl)

        private fun isHttpsUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            return runCatching {
                val parsed = java.net.URI(url)
                parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank()
            }.getOrDefault(false)
        }
    }

    open fun current(): ManagedConfig {
        val rm = appContext.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager
        val bundle = rm?.applicationRestrictions
        // Only treat backfill_days as set when the admin actually provided the key;
        // an absent key must defer to the server policy, not silently mean 0.
        val backfillDays = if (bundle?.containsKey(KEY_BACKFILL_DAYS) == true) {
            bundle.getInt(KEY_BACKFILL_DAYS).coerceAtLeast(0)
        } else {
            null
        }
        return ManagedConfig(
            backendUrl = bundle?.getString(KEY_BACKEND_URL)?.trim()?.ifBlank { null },
            enrollmentToken = bundle?.getString(KEY_ENROLLMENT_TOKEN)?.trim()?.ifBlank { null },
            backfillDays = backfillDays,
        )
    }

    companion object {
        const val KEY_BACKEND_URL = "backend_url"
        const val KEY_ENROLLMENT_TOKEN = "enrollment_token"
        const val KEY_BACKFILL_DAYS = "backfill_days"
    }
}
