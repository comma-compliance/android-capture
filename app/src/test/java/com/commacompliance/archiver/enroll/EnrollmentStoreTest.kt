package com.commacompliance.archiver.enroll

import androidx.test.core.app.ApplicationProvider
import com.commacompliance.archiver.FakeSharedPreferences
import com.commacompliance.archiver.crypto.NaclBox
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

/**
 * The persisted enrollment credential store. Beyond the round-trip, this covers the
 * corruption-recovery path: a destination key that no longer decodes to a 32-byte
 * Curve25519 key makes the whole enrollment unusable, so [EnrollmentStore.load]
 * clears the entire credential bundle (rather than leave a token with no usable key)
 * and returns null, dropping the device back onto the normal not-enrolled recovery.
 */
@RunWith(RobolectricTestRunner::class)
class EnrollmentStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val prefs = FakeSharedPreferences()
    private val store = EnrollmentStore(context) { prefs }

    private fun enrollment() = EnrollmentStore.Enrollment(
        archiveToken = "arc_token",
        deviceId = "amdev_1",
        destinationPublicKey = ByteArray(NaclBox.PUBLIC_KEY_BYTES) { it.toByte() },
        kid = "KID123=",
        backfillDays = 3,
    )

    @Test
    fun save_then_load_round_trips() {
        store.saveSelfServe(enrollment())
        val loaded = store.load()!!
        assertEquals("arc_token", loaded.archiveToken)
        assertEquals("amdev_1", loaded.deviceId)
        assertEquals("KID123=", loaded.kid)
        assertEquals(3, loaded.backfillDays)
        assertArrayEquals(ByteArray(NaclBox.PUBLIC_KEY_BYTES) { it.toByte() }, loaded.destinationPublicKey)
    }

    @Test
    fun load_is_null_when_nothing_stored() {
        assertNull(store.load())
    }

    @Test
    fun corrupt_destination_key_clears_whole_enrollment_and_returns_null() {
        store.saveSelfServe(enrollment())
        // Corrupt the persisted destination key in place: a 16-byte value is not a
        // valid 32-byte Curve25519 key.
        prefs.edit().putString("destination_public_key", Base64.getEncoder().encodeToString(ByteArray(16))).commit()

        // load() detects the corruption, clears the bundle, and reports not-enrolled.
        assertNull(store.load())
        // The whole credential bundle is gone - no wedged half-state where the device
        // still looks connected but every upload fails to build an envelope.
        assertFalse(store.isConnected())
        assertNull(store.selfServeBackendUrl())
        // A subsequent load stays null (idempotent recovery).
        assertNull(store.load())
    }

    @Test
    fun non_base64_destination_key_also_clears_and_returns_null() {
        store.saveSelfServe(enrollment())
        prefs.edit().putString("destination_public_key", "!!! not base64 !!!").commit()
        assertNull(store.load())
        assertFalse(store.isConnected())
    }

    @Test
    fun decodeDestinationKey_accepts_only_32_byte_values() {
        val good = Base64.getEncoder().encodeToString(ByteArray(NaclBox.PUBLIC_KEY_BYTES))
        assertEquals(NaclBox.PUBLIC_KEY_BYTES, EnrollmentStore.decodeDestinationKey(good)!!.size)
        assertNull(EnrollmentStore.decodeDestinationKey(Base64.getEncoder().encodeToString(ByteArray(16))))
        assertNull(EnrollmentStore.decodeDestinationKey("!!! not base64 !!!"))
    }
}
