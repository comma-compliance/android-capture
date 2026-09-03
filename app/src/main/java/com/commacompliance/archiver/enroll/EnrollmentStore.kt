package com.commacompliance.archiver.enroll

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.commacompliance.archiver.crypto.NaclBox
import com.commacompliance.archiver.util.Hex
import java.security.MessageDigest
import java.util.Base64

/**
 * Persists the result of a successful enrollment - the device-scoped bearer
 * `archive_token`, the server-assigned `device_id`, and the destination key
 * (`destination_public_key` + `kid`) the upload envelope encrypts to.
 *
 * Stored in EncryptedSharedPreferences (Keystore-wrapped) because the
 * archive_token is a long-lived bearer credential. The hash of the
 * `enrollment_token` last consumed is also kept so a managed-config rotation of
 * the enrollment_token can be detected and force a re-enroll.
 */
// TooManyFunctions: this is the single typed accessor surface over the encrypted
// enrollment prefs (load/save/clear plus small per-field getters/setters). Each is
// a one-liner; collapsing them into a generic map would lose the type safety and
// the per-credential documentation that is the point of the class.
@Suppress("TooManyFunctions")
class EnrollmentStore(
    context: Context,
    prefsFactory: (Context) -> SharedPreferences = ::defaultPrefs,
) {
    private val prefs: SharedPreferences = prefsFactory(context.applicationContext)

    data class Enrollment(
        val archiveToken: String,
        val deviceId: String,
        val destinationPublicKey: ByteArray,
        val kid: String,
        /**
         * First-run capture policy returned by the server: 0 = forward-only
         * (default), N = capture the last N days, 9999 ≈ full history. Defaults to
         * 0 when the server omits it (e.g. an older enroll response).
         */
        val backfillDays: Int = 0,
    )

    /**
     * Returns the stored enrollment, or null when none is stored OR the persisted
     * destination key is corrupt (not valid base64, or not exactly 32 bytes).
     *
     * Corruption-recovery path: a destination key that no longer decodes to a
     * 32-byte Curve25519 key makes the WHOLE enrollment unusable - the upload
     * envelope cannot be sealed without it - so we [clear] the entire credential
     * bundle and return null rather than leave a token with no usable key (a wedged
     * half-state where `isConnected()` is true but every upload fails to build).
     * After clearing, recovery is the normal not-enrolled path: a MANAGED device
     * re-enrolls automatically from its managed `enrollment_token`; a SELF-SERVE
     * device shows disconnected and the user re-runs the one-time OAuth sign-in.
     * EncryptedSharedPreferences should never corrupt a value in practice, so this
     * is a defensive last resort, not an expected flow.
     */
    fun load(): Enrollment? {
        val token = prefs.getString(KEY_ARCHIVE_TOKEN, null) ?: return null
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        val destB64 = prefs.getString(KEY_DEST_PUBKEY, null) ?: return null
        val kid = prefs.getString(KEY_KID, null) ?: return null
        val destinationKey = decodeDestinationKey(destB64) ?: run {
            clear()
            return null
        }
        return Enrollment(
            archiveToken = token,
            deviceId = deviceId,
            destinationPublicKey = destinationKey,
            kid = kid,
            backfillDays = prefs.getInt(KEY_BACKFILL_DAYS, 0),
        )
    }

    /** True when a usable archive credential is stored. */
    fun isConnected(): Boolean = prefs.getString(KEY_ARCHIVE_TOKEN, null) != null

    /**
     * True when this device was provisioned via the self-serve OAuth path (it has
     * a credential but no consumed managed enrollment_token). Used so the upload
     * path treats a self-serve device as enrolled even with no managed config,
     * while a MANAGED device that lost its config is NOT silently kept alive.
     */
    fun isSelfServe(): Boolean =
        isConnected() && prefs.getString(KEY_ENROLL_TOKEN_HASH, null) == null

    /**
     * The backend origin a self-serve device uploads to. Managed devices read the
     * backend_url from managed config instead, so this is only set on the
     * self-serve path. Returned to the upload path when no managed config exists.
     */
    fun selfServeBackendUrl(): String? = prefs.getString(KEY_SELF_SERVE_BACKEND, null)

    // ApplySharedPref: commit() is deliberate. The self-serve backend origin is
    // the only pointer a self-serve device has to where it uploads; an async
    // apply() that lost the process before flushing would strand it. Synchronous
    // durability is worth the brief blocking write here.
    @SuppressLint("ApplySharedPref")
    fun setSelfServeBackendUrl(origin: String) {
        prefs.edit().putString(KEY_SELF_SERVE_BACKEND, origin).commit()
    }

    /**
     * A short, human-readable label for the connected organization/account shown
     * in the status UI (e.g. the signed-in user's email or team name). Best-effort
     * display only - never a credential.
     */
    fun connectedLabel(): String? = prefs.getString(KEY_CONNECTED_LABEL, null)

    fun setConnectedLabel(label: String?) {
        prefs.edit().apply {
            if (label.isNullOrBlank()) remove(KEY_CONNECTED_LABEL) else putString(KEY_CONNECTED_LABEL, label)
        }.apply()
    }

    /**
     * Persist a fresh enrollment alongside the enrollment_token it consumed.
     *
     * Uses commit() (synchronous), not apply(): these are durable credentials
     * (archive_token/device_id/destination_public_key/kid + enrollment_token_hash)
     * and an async flush that loses the process in its write window would leave the
     * device in an inconsistent state. Matches KeyManager.secretKey()'s pattern.
     */
    fun save(enrollment: Enrollment, enrollmentTokenConsumed: String) {
        commonSave(enrollment)
            .putString(KEY_ENROLL_TOKEN_HASH, sha256Hex(enrollmentTokenConsumed))
            .commit()
    }

    /**
     * Persist a fresh enrollment obtained through the self-serve OAuth +
     * device-registration handshake. There is no managed enrollment_token to track
     * (the OAuth login authorized the device, not a one-time token), so the
     * enrollment_token hash is cleared - this device is NOT on the managed path and
     * must not later re-enroll against a phantom rotated token.
     */
    fun saveSelfServe(enrollment: Enrollment) {
        commonSave(enrollment)
            .remove(KEY_ENROLL_TOKEN_HASH)
            .commit()
    }

    private fun commonSave(enrollment: Enrollment) =
        prefs.edit()
            .putString(KEY_ARCHIVE_TOKEN, enrollment.archiveToken)
            .putString(KEY_DEVICE_ID, enrollment.deviceId)
            .putString(KEY_DEST_PUBKEY, Base64.getEncoder().encodeToString(enrollment.destinationPublicKey))
            .putString(KEY_KID, enrollment.kid)
            .putInt(KEY_BACKFILL_DAYS, enrollment.backfillDays)

    /**
     * Refresh the stored first-run backfill policy from a heartbeat response. Note
     * this only governs the FIRST capture pass; once the baseline is established it
     * has no further effect, so refreshing it is bookkeeping/consistency, not a
     * retroactive re-backfill. No-op when no credential is stored.
     */
    fun updateBackfillDays(days: Int) {
        if (!isConnected()) return
        prefs.edit().putInt(KEY_BACKFILL_DAYS, days.coerceAtLeast(0)).apply()
    }

    /** Drop the persisted archive_token + destination key (e.g. on 401). Keeps the keypair. */
    fun clear() {
        prefs.edit()
            .remove(KEY_ARCHIVE_TOKEN)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_DEST_PUBKEY)
            .remove(KEY_KID)
            .remove(KEY_ENROLL_TOKEN_HASH)
            .remove(KEY_BACKFILL_DAYS)
            .remove(KEY_CONNECTED_LABEL)
            .remove(KEY_SELF_SERVE_BACKEND)
            .apply()
    }

    /**
     * True when [enrollmentToken] from managed config differs from the one this
     * device last enrolled with - meaning the admin rotated it and a re-enroll is
     * required before the next upload.
     */
    fun enrollmentTokenChanged(enrollmentToken: String): Boolean {
        val stored = prefs.getString(KEY_ENROLL_TOKEN_HASH, null) ?: return true
        return stored != sha256Hex(enrollmentToken)
    }

    private fun sha256Hex(value: String): String =
        Hex.encode(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    companion object {
        /**
         * Decode a server-provided/persisted `destination_public_key` from base64,
         * returning the bytes only if they form a valid 32-byte Curve25519 key.
         * Null on any malformed base64 OR a wrong length. Shared by the enroll and
         * registration parse paths and by [load] so a non-conforming key is rejected
         * once, consistently, before it can reach the upload envelope.
         */
        fun decodeDestinationKey(base64: String): ByteArray? {
            val decoded = runCatching { Base64.getDecoder().decode(base64) }.getOrNull() ?: return null
            return if (decoded.size == NaclBox.PUBLIC_KEY_BYTES) decoded else null
        }

        private const val PREFS_NAME = "archiver_enrollment"
        private const val KEY_ARCHIVE_TOKEN = "archive_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEST_PUBKEY = "destination_public_key"
        private const val KEY_KID = "kid"
        private const val KEY_ENROLL_TOKEN_HASH = "enrollment_token_hash"
        private const val KEY_BACKFILL_DAYS = "backfill_days"
        private const val KEY_CONNECTED_LABEL = "connected_label"
        private const val KEY_SELF_SERVE_BACKEND = "self_serve_backend_url"

        private fun defaultPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
