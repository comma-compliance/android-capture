package com.commacompliance.archiver.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the contact-name resolution feature end to end at the unit level:
 * `toPayloadJson()` emits `contact_names` (populated + empty), the per-run enricher
 * queries each distinct address at most once, and a denied/throwing resolver yields
 * an empty map without raising.
 */
@RunWith(RobolectricTestRunner::class)
class ContactNameResolutionTest {

    private fun event(
        from: String? = "+15555550111",
        to: List<String> = listOf("+15555550100"),
        cc: List<String> = emptyList(),
        participants: List<String> = listOf("+15555550111", "+15555550100"),
        contactNames: Map<String, String> = emptyMap(),
    ) = NormalizedEvent(
        source = Source.RCS,
        providerId = "mms:1",
        threadId = "2",
        threadType = ThreadType.ONE_TO_ONE,
        direction = Direction.INCOMING,
        timestampMs = 1779668768000,
        from = from,
        to = to,
        cc = cc,
        participants = participants,
        body = "hi",
        attachments = emptyList(),
        isRcs = true,
        creator = "com.google.android.apps.messaging",
        contactNames = contactNames,
    )

    @Test
    fun toPayloadJson_emitsContactNames_whenResolved() {
        val json = event(
            contactNames = mapOf(
                "+15555550111" to "Jordan Rivera",
                "+15555550100" to "Me",
            ),
        ).toPayloadJson()

        val names = json.getJSONObject("contact_names")
        assertEquals(2, names.length())
        assertEquals("Jordan Rivera", names.getString("+15555550111"))
        assertEquals("Me", names.getString("+15555550100"))
    }

    @Test
    fun toPayloadJson_emitsEmptyObject_whenNoNamesResolved() {
        val json = event(contactNames = emptyMap()).toPayloadJson()
        // The key is always present for shape stability and is an empty object.
        assertTrue(json.has("contact_names"))
        assertEquals(0, json.getJSONObject("contact_names").length())
    }

    @Test
    fun toPayloadJson_keysAreRawAddresses_notReNormalized() {
        // The map key must match the address byte-for-byte as it appears in `from`.
        val raw = "+1 (555) 555-0111"
        val json = event(from = raw, contactNames = mapOf(raw to "Jordan")).toPayloadJson()
        assertEquals("Jordan", json.getJSONObject("contact_names").getString(raw))
    }

    /** A resolver that records every address it is asked about. */
    private class CountingResolver(
        private val names: Map<String, String?> = emptyMap(),
    ) : ContactNameResolver {
        val calls = mutableListOf<String>()
        override fun displayName(address: String): String? {
            calls += address
            return names[address]
        }
    }

    @Test
    fun enricher_queriesEachDistinctAddressAtMostOnce_acrossEvents() {
        val resolver = CountingResolver(
            mapOf("+15555550111" to "Jordan", "+15555550100" to "Me"),
        )
        val enricher = ContactNameEnricher(resolver)

        // Two events sharing the same addresses (also repeated within from+participants).
        enricher.namesFor(listOf("+15555550111", "+15555550100", "+15555550111"))
        enricher.namesFor(listOf("+15555550100", "+15555550111"))
        // An address with no saved contact is still cached so it is queried once.
        enricher.namesFor(listOf("+15555559999"))
        enricher.namesFor(listOf("+15555559999"))

        assertEquals(
            "each distinct address queried exactly once",
            listOf("+15555550111", "+15555550100", "+15555559999"),
            resolver.calls,
        )
    }

    @Test
    fun enricher_namesFor_includesOnlyResolvedNonBlankNames() {
        val resolver = CountingResolver(
            mapOf(
                "+15555550111" to "Jordan",
                "+15555550100" to null, // no saved contact
            ),
        )
        val names = ContactNameEnricher(resolver)
            .namesFor(listOf("+15555550111", "+15555550100"))
        assertEquals(mapOf("+15555550111" to "Jordan"), names)
    }

    @Test
    fun enricher_throwingResolver_yieldsEmptyMapWithoutRaising() {
        // A resolver that throws on every lookup (e.g. READ_CONTACTS denied at
        // runtime). The enricher guards defensively so capture can never fail.
        val throwing = ContactNameResolver { throw SecurityException("READ_CONTACTS denied") }
        val names = ContactNameEnricher(throwing).namesFor(listOf("+15555550111", "+15555550100"))
        assertTrue("denied resolver yields empty map without raising", names.isEmpty())
    }

    @Test
    fun enricher_blankResolvedName_countsAsNoName() {
        val blank = ContactNameResolver { "   " }
        val names = ContactNameEnricher(blank).namesFor(listOf("+15555550111"))
        assertTrue("whitespace-only name is treated as no name", names.isEmpty())
    }
}
