package com.commacompliance.archiver.onboarding

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.text.format.DateUtils
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.commacompliance.archiver.R
import com.commacompliance.archiver.capture.CaptureCoordinatorFactory
import com.commacompliance.archiver.data.ArchiverDatabase
import com.commacompliance.archiver.data.ArchiverMetaDao
import com.commacompliance.archiver.enroll.EnrollmentStore
import com.commacompliance.archiver.net.Connectivity
import com.commacompliance.archiver.upload.Uploader
import com.commacompliance.archiver.upload.UploaderFactory
import com.commacompliance.archiver.util.applySystemWindowInsetsAsPadding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/**
 * The connected-state Status/About screen: shows the organization the device is
 * archiving for, the device id, last sync time, pending-upload count, a short
 * privacy summary, and the app version. It offers a self-test (trigger a capture
 * + upload and report whether it succeeded) and a disconnect action.
 *
 * A device that is not connected is sent back to onboarding.
 */
class StatusActivity : AppCompatActivity() {

    private lateinit var store: EnrollmentStore
    private lateinit var syncStatus: SyncStatusStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status)
        findViewById<View>(android.R.id.content).applySystemWindowInsetsAsPadding()
        store = EnrollmentStore(this)
        syncStatus = SyncStatusStore(this)

        findViewById<MaterialButton>(R.id.selfTestButton).setOnClickListener { runSelfTest() }
        findViewById<MaterialButton>(R.id.disconnectButton).setOnClickListener { disconnect() }
    }

    override fun onResume() {
        super.onResume()
        if (!store.isConnected()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        renderStatus()
    }

    private fun renderStatus() {
        val enrollment = store.load()
        findViewById<TextView>(R.id.connectionState).text = getString(R.string.status_connected)
        val label = store.connectedLabel()
        findViewById<TextView>(R.id.connectedTo).text =
            if (label.isNullOrBlank()) getString(R.string.status_connected_generic)
            else getString(R.string.status_connected_to, label)
        findViewById<TextView>(R.id.deviceId).text = enrollment?.deviceId ?: EM_DASH
        findViewById<TextView>(R.id.appVersion).text = appVersion()

        // Connectivity + auth state so a user/auditor can see capture is alive even
        // when nothing is uploading. Capture is local, so "Offline" is informational,
        // not an error - the wording reassures that capture continues.
        findViewById<TextView>(R.id.connectivity).text =
            getString(if (Connectivity.isOnline(this)) R.string.status_online else R.string.status_offline)
        findViewById<TextView>(R.id.authState).text =
            getString(if (store.isConnected()) R.string.status_auth_ok else R.string.status_auth_missing)

        findViewById<TextView>(R.id.lastSync).text = relativeOrNever(syncStatus.lastSyncAtMillis())

        lifecycleScope.launch {
            // ArchiverDatabase.get() opens the encrypted queue, and that open is
            // deliberately fail-closed: it THROWS rather than silently presenting a
            // fresh empty queue when the passphrase is gone or an interrupted
            // plaintext->encrypted migration must be retried. Letting that throw
            // escape this coroutine hands it to the uncaught-exception handler and
            // kills the app, so the screen the user opens to find out what is wrong
            // is precisely the screen that cannot open. Catch it and render the
            // degraded state instead: the fail-closed posture is still honoured
            // (nothing is discarded, the open is simply refused), it is just
            // reported instead of fatal.
            val snapshot = withContext(Dispatchers.IO) { readStatusSnapshot() }
            findViewById<TextView>(R.id.pendingCount).text =
                snapshot?.let { NumberFormat.getIntegerInstance().format(it.pending) } ?: EM_DASH
            findViewById<TextView>(R.id.lastCheck).text =
                snapshot?.let { relativeOrNever(it.lastScannedAt) } ?: EM_DASH
            findViewById<TextView>(R.id.storageWarning).visibility =
                if (snapshot?.storageBlocked == true) View.VISIBLE else View.GONE
            // A null snapshot means the queue would not open at all - a strictly
            // worse condition than a full disk, and the only on-device signal the
            // user gets that this device has stopped archiving.
            findViewById<TextView>(R.id.queueWarning).visibility =
                if (snapshot == null) View.VISIBLE else View.GONE
        }
    }

    /**
     * Read the queue counters, or null if the queue could not be opened at all.
     *
     * The catch is deliberately narrow. [CancellationException] is rethrown so that
     * leaving the screen mid-read still cancels the coroutine instead of being
     * absorbed and then rendering into a destroyed view. [UnsatisfiedLinkError] is
     * named explicitly because it is a REAL condition here - the SQLCipher native is
     * loaded on this path and a missing/incompatible `.so` surfaces as an Error, not
     * an Exception - while every other Error (OOM and friends) is left to propagate
     * rather than being disguised as an unavailable queue.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun readStatusSnapshot(): StatusSnapshot? = try {
        val db = ArchiverDatabase.get(applicationContext)
        val meta = db.archiverMetaDao()
        StatusSnapshot(
            pending = db.capturedEventDao().pendingCount(),
            lastScannedAt = meta.get(ArchiverMetaDao.KEY_LAST_SCANNED_AT)?.toLongOrNull(),
            storageBlocked = meta.get(ArchiverMetaDao.KEY_STORAGE_BLOCKED) == "true",
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "queue unavailable for status render: ${e.javaClass.simpleName}")
        null
    } catch (e: UnsatisfiedLinkError) {
        Log.w(TAG, "queue unavailable for status render: ${e.javaClass.simpleName}")
        null
    }

    private data class StatusSnapshot(
        val pending: Int,
        val lastScannedAt: Long?,
        val storageBlocked: Boolean,
    )

    private fun relativeOrNever(atMillis: Long?): String =
        if (atMillis == null) getString(R.string.status_last_sync_never)
        else DateUtils.getRelativeTimeSpanString(
            atMillis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()

    private fun runSelfTest() {
        val result = findViewById<TextView>(R.id.selfTestResult)
        result.visibility = View.VISIBLE
        result.text = getString(R.string.status_self_test_running)

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // A capture pass establishes/refreshes local state, then a synchronous
                    // upload pass proves the full path (enroll/credential + envelope +
                    // POST) works end to end. DONE means the pipeline is healthy even when
                    // there is nothing new to send.
                    CaptureCoordinatorFactory.create(applicationContext).runOnce()
                    UploaderFactory.create(applicationContext).runOnce() == Uploader.Result.DONE
                }.getOrDefault(false)
            }
            result.text = getString(
                if (ok) R.string.status_self_test_ok else R.string.status_self_test_fail,
            )
            renderStatus()
        }
    }

    private fun disconnect() {
        store.clear()
        startActivity(Intent(this, OnboardingActivity::class.java))
        finish()
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: EM_DASH
    } catch (_: Throwable) {
        EM_DASH
    }

    companion object {
        private const val TAG = "StatusActivity"

        /** Placeholder shown wherever a value could not be read. */
        private const val EM_DASH = "—"
    }
}
