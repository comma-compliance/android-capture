package com.commacompliance.archiver.capture

import com.commacompliance.archiver.config.ConfigProvider
import com.commacompliance.archiver.enroll.EnrollmentStore

/**
 * Resolves the first-run `backfill_days` in the contract's precedence:
 *   1. the managed-config `backfill_days` key, if the EMM set it directly;
 *   2. otherwise the value the server returned at enroll/registration (persisted
 *      on the enrollment).
 * Falls back to 0 (forward-only) when neither is available - never an accidental
 * full dump. Consulted ONLY on the first capture pass.
 */
object BackfillPolicy {

    fun resolveDays(configProvider: ConfigProvider, store: EnrollmentStore): Int {
        val managed = configProvider.current().backfillDays
        if (managed != null) return managed.coerceAtLeast(0)
        return (store.load()?.backfillDays ?: 0).coerceAtLeast(0)
    }
}
