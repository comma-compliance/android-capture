package com.commacompliance.archiver.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Runs under Robolectric so the real org.json (not the unmocked stub) backs the
// fixture parsing and so the SHA-256 vectors are verified against the golden files.
@RunWith(RobolectricTestRunner::class)
class BodyHashTest {

    @Test
    fun emptyBody_matchesFrozenVector() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            BodyHash.of(""),
        )
    }

    @Test
    fun nullBody_treatedAsEmpty() {
        assertEquals(BodyHash.of(""), BodyHash.of(null))
    }

    @Test
    fun knownBody_isLowercaseHex() {
        val h = BodyHash.of("Back atcha")
        assertEquals("b58ce24a5f7038be28bffd61cdd147fc72d2ec7cd92f84414c8e90ad7793506c", h)
    }

    @Test
    fun matchesGoldenFixtureHashes() {
        // The fixtures freeze the exact body_hash; recompute from the body and
        // confirm both sides agree (the server derives the same key).
        for (name in listOf(
            "sms_received", "sms_sent", "sms_long", "rcs_received", "rcs_sent",
            "rcs_group", "mms_received", "mms_group_received", "rcs_reaction",
            "rcs_location", "rcs_attachment_image",
        )) {
            val payload = Fixtures.payload(name)
            assertEquals(
                "body_hash mismatch for $name",
                Fixtures.load(name).getString("body_hash"),
                BodyHash.of(payload.getString("body")),
            )
        }
    }
}
