package com.commacompliance.archiver.capture

import android.content.ContentResolver
import android.content.Context
import com.commacompliance.archiver.config.ConfigProvider
import com.commacompliance.archiver.data.ArchiverDatabase
import com.commacompliance.archiver.enroll.EnrollmentStore

/**
 * Builds a [CaptureCoordinator] wired with the resolved first-run backfill policy,
 * so the per-broadcast capture, the reconciliation scan, and the self-test all run
 * the SAME pass with the SAME policy source.
 */
object CaptureCoordinatorFactory {

    fun create(context: Context, deviceNumber: String? = null): CaptureCoordinator {
        val appContext = context.applicationContext
        val configProvider = ConfigProvider(appContext)
        val store = EnrollmentStore(appContext)
        return CaptureCoordinator(
            reader = TelephonyReader(appContext.contentResolver, deviceNumber),
            db = ArchiverDatabase.get(appContext),
            backfillDaysProvider = { BackfillPolicy.resolveDays(configProvider, store) },
            contactNameResolver = PhoneLookupContactNameResolver(appContext.contentResolver),
        )
    }

    fun create(
        contentResolver: ContentResolver,
        db: ArchiverDatabase,
        configProvider: ConfigProvider,
        store: EnrollmentStore,
        deviceNumber: String? = null,
    ): CaptureCoordinator = CaptureCoordinator(
        reader = TelephonyReader(contentResolver, deviceNumber),
        db = db,
        backfillDaysProvider = { BackfillPolicy.resolveDays(configProvider, store) },
        contactNameResolver = PhoneLookupContactNameResolver(contentResolver),
    )
}
