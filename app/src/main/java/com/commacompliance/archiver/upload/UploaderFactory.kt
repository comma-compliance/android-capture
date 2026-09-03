package com.commacompliance.archiver.upload

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import com.commacompliance.archiver.config.ConfigProvider
import com.commacompliance.archiver.crypto.EnvelopeEncryptor
import com.commacompliance.archiver.crypto.KeyManager
import com.commacompliance.archiver.crypto.LazySodiumNaclBox
import com.commacompliance.archiver.crypto.NaclBox
import com.commacompliance.archiver.data.ArchiverDatabase
import com.commacompliance.archiver.enroll.EnrollmentClient
import com.commacompliance.archiver.enroll.EnrollmentManager
import com.commacompliance.archiver.enroll.EnrollmentStore
import com.commacompliance.archiver.integrity.PlayIntegrityTokenSource
import com.commacompliance.archiver.net.HttpClient
import com.commacompliance.archiver.net.HttpsUrls
import com.commacompliance.archiver.net.UrlConnectionHttpClient
import com.commacompliance.archiver.onboarding.DiscoveryClient
import com.commacompliance.archiver.onboarding.SyncStatusStore
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid

/**
 * Assembles a real [Uploader] from Android system services. Kept separate from the
 * worker so the worker stays a thin WorkManager adapter and the upload logic itself
 * is constructed from plain dependencies in tests.
 */
object UploaderFactory {

    fun naclBox(): NaclBox = LazySodiumNaclBox(LazySodiumAndroid(SodiumAndroid()))

    /**
     * Build the managed-enrollment coordinator with its Play Integrity wiring. The
     * EMM enroll attaches an attestation token bound to the server challenge when the
     * server advertises a cloud_project_number; it degrades to token-less enrollment
     * (server policy decides) whenever Play services or the project number are
     * unavailable. The attestation lives in [EnrollmentClient] (its requestHash binds
     * to the mid-handshake challenge); the manager only resolves the project number
     * via discovery. Shared by every upload/heartbeat pass so the wiring lives in one
     * place.
     */
    private fun enrollmentManager(
        appContext: Context,
        configProvider: ConfigProvider,
        store: EnrollmentStore,
        keyManager: KeyManager,
        http: HttpClient,
    ): EnrollmentManager = EnrollmentManager(
        configProvider = configProvider,
        store = store,
        client = EnrollmentClient(http, keyManager, PlayIntegrityTokenSource(appContext)),
        enrollmentSpecificIdProvider = { enrollmentSpecificId(appContext) },
        deviceLabelProvider = { deviceLabel() },
        cloudProjectNumberProvider = { backendUrl -> DiscoveryClient(http).fetchCloudProjectNumber(backendUrl) },
    )

    /**
     * The same managed-enrollment wiring the background passes use, exposed so the
     * onboarding screen can DRIVE an EMM enrollment rather than only display it.
     * Built here so there is exactly one definition of how an EnrollmentManager is
     * assembled - a second, drifting copy in the UI is how the two paths would end
     * up attesting or labelling the device differently.
     */
    fun enrollmentManagerFor(context: Context): EnrollmentManager {
        val appContext = context.applicationContext
        val http = UrlConnectionHttpClient()
        return enrollmentManager(
            appContext = appContext,
            configProvider = ConfigProvider(appContext),
            store = EnrollmentStore(appContext),
            keyManager = KeyManager(appContext, naclBox()),
            http = http,
        )
    }

    fun create(context: Context): Uploader {
        val appContext = context.applicationContext
        val box = naclBox()
        val keyManager = KeyManager(appContext, box)
        val configProvider = ConfigProvider(appContext)
        val store = EnrollmentStore(appContext)
        val http: HttpClient = UrlConnectionHttpClient()
        val enrollmentManager = enrollmentManager(appContext, configProvider, store, keyManager, http)
        return Uploader(
            dao = ArchiverDatabase.get(appContext).capturedEventDao(),
            enrollmentManager = enrollmentManager,
            keyManager = keyManager,
            encryptor = EnvelopeEncryptor(box),
            http = http,
            configProvider = configProvider,
            // The resolved backend carries the bearer archive_token; gate it through
            // requireHttps so a token NEVER travels to a non-https URL. A null result
            // (unconfigured or non-https) is treated by the pass as "not configured".
            backendUrlResolver = {
                HttpsUrls.requireHttps(configProvider.current().backendUrl ?: store.selfServeBackendUrl())
            },
            onBatchUploaded = { SyncStatusStore(appContext).recordSync() },
        )
    }

    /**
     * Assembles the out-of-band attachment preparer: streams each captured
     * attachment's bytes to compute its plaintext content_hash, records it as a
     * pending blob, and releases its event for upload. Reads bytes via the
     * ContentResolver; runs in WorkManager, never the capture window.
     */
    fun attachmentEnqueuer(context: Context): AttachmentEnqueuer {
        val appContext = context.applicationContext
        val db = ArchiverDatabase.get(appContext)
        return AttachmentEnqueuer(
            eventDao = db.capturedEventDao(),
            pendingDao = db.pendingAttachmentDao(),
            source = ContentResolverAttachmentSource(appContext),
        )
    }

    /**
     * Assembles the attachment-binary uploader: drains the durable pending-blob
     * store, encrypting each blob into a domain-separated envelope and POSTing it
     * single-shot or chunked. Reuses the same NaCl box + envelope as the event
     * path; its own job so it never blocks the event batch drain.
     */
    fun attachmentUploader(context: Context): AttachmentUploader {
        val appContext = context.applicationContext
        val box = naclBox()
        val keyManager = KeyManager(appContext, box)
        val configProvider = ConfigProvider(appContext)
        val store = EnrollmentStore(appContext)
        val http: HttpClient = UrlConnectionHttpClient()
        val enrollmentManager = enrollmentManager(appContext, configProvider, store, keyManager, http)
        return AttachmentUploader(
            dao = ArchiverDatabase.get(appContext).pendingAttachmentDao(),
            enrollmentManager = enrollmentManager,
            keyManager = keyManager,
            encryptor = EnvelopeEncryptor(box),
            http = http,
            source = ContentResolverAttachmentSource(appContext),
            // The resolved backend carries the bearer archive_token; gate it through
            // requireHttps so a token NEVER travels to a non-https URL. A null result
            // (unconfigured or non-https) is treated by the pass as "not configured".
            backendUrlResolver = {
                HttpsUrls.requireHttps(configProvider.current().backendUrl ?: store.selfServeBackendUrl())
            },
            onUploaded = { SyncStatusStore(appContext).recordSync() },
        )
    }

    /**
     * Assembles a [HeartbeatRunner] from system services. Reconcile + flush are
     * supplied as lambdas so the worker wiring stays here and the runner logic is
     * unit-tested in isolation.
     */
    fun heartbeatRunner(context: Context): HeartbeatRunner {
        val appContext = context.applicationContext
        val box = naclBox()
        val keyManager = KeyManager(appContext, box)
        val configProvider = ConfigProvider(appContext)
        val store = EnrollmentStore(appContext)
        val http: HttpClient = UrlConnectionHttpClient()
        val enrollmentManager = enrollmentManager(appContext, configProvider, store, keyManager, http)
        return HeartbeatRunner(
            enrollmentManager = enrollmentManager,
            store = store,
            heartbeatClient = com.commacompliance.archiver.enroll.HeartbeatClient(http),
            // The heartbeat carries the bearer archive_token; gate it through
            // requireHttps so a token NEVER travels to a non-https URL. A null result
            // (unconfigured or non-https) is treated by the pass as "not configured".
            backendUrlResolver = {
                HttpsUrls.requireHttps(configProvider.current().backendUrl ?: store.selfServeBackendUrl())
            },
            reconcile = { com.commacompliance.archiver.capture.ReconciliationScan.runNow(appContext) },
            flushUploads = { UploadWorker.enqueue(appContext) },
        )
    }

    /**
     * The enrollment-specific id binds enrollment to this managed device. It is only
     * available on a device owner/profile owner; null otherwise (the server accepts
     * a null binding). The API (31+) is always present at our minSdk of 34.
     */
    private fun enrollmentSpecificId(context: Context): String? {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            dpm?.enrollmentSpecificId?.ifBlank { null }
        } catch (_: Throwable) {
            null
        }
    }

    private fun deviceLabel(): String =
        listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
}
