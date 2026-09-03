package com.commacompliance.archiver.capture

import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

/**
 * Resolves the saved contact display name for a single address (phone number or
 * RCS handle) that already appears in a captured message.
 *
 * Privacy/scope: callers only ever pass addresses that are present in a message
 * being archived. The address book is never enumerated.
 */
fun interface ContactNameResolver {
    /**
     * The saved display name for [address], or null when none is saved, the lookup
     * fails, or the permission is missing. A blank/whitespace-only name counts as
     * "no name" and yields null.
     */
    fun displayName(address: String): String?
}

/**
 * Real resolver backed by `ContactsContract.PhoneLookup`. The address is appended
 * to [ContactsContract.PhoneLookup.CONTENT_FILTER_URI] (the provider does its own
 * number matching) and `DISPLAY_NAME` is projected.
 *
 * Graceful failure is mandatory: capture runs inside a time-boxed window and must
 * NEVER fail because contacts are unavailable. A missing READ_CONTACTS grant throws
 * SecurityException; a transient provider error throws something else. Either way
 * we log and return null so capture proceeds with the bare number.
 */
class PhoneLookupContactNameResolver(
    private val contentResolver: ContentResolver,
) : ContactNameResolver {

    // A contacts lookup can throw SecurityException (READ_CONTACTS not granted),
    // IllegalStateException, or other provider-side runtime failures; a broad catch
    // returns null so capture proceeds with the bare number rather than crashing
    // inside the time-boxed window.
    @Suppress("TooGenericExceptionCaught")
    override fun displayName(address: String): String? {
        if (address.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address),
            )
            contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "contact lookup failed; archiving number only: ${e.javaClass.simpleName}")
            null
        }
    }

    private companion object {
        const val TAG = "ContactNameResolver"
    }
}

/**
 * Wraps a [ContactNameResolver] with a per-capture-run cache so each distinct
 * address is queried at most once across all events in the run, and resolves the
 * names for a set of addresses into the `contact_names` wire map (only addresses
 * with a resolved name appear).
 *
 * One instance per capture run (the cache is intentionally short-lived: contact
 * data can change between runs, and resolving is expensive).
 */
class ContactNameEnricher(
    private val resolver: ContactNameResolver,
) {
    // null value = "looked up, no name" - distinct from "not yet looked up" (absent
    // key), so a missing contact is never re-queried within the run.
    private val cache = HashMap<String, String?>()

    /**
     * Resolve (cached) the display name for one address. Defensively swallows any
     * resolver failure (returning null) so capture can NEVER fail because contacts
     * are unavailable - even if a resolver implementation forgets to guard. A blank
     * name is normalized to null.
     */
    @Suppress("TooGenericExceptionCaught")
    fun displayName(address: String): String? {
        if (cache.containsKey(address)) return cache[address]
        val name = try {
            resolver.displayName(address)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.d(TAG, "contact lookup failed; archiving number only: ${e.javaClass.simpleName}")
            null
        }
        cache[address] = name
        return name
    }

    /**
     * Build the `contact_names` map for [addresses]: the subset of distinct
     * addresses that resolved to a non-blank display name. Never throws.
     */
    fun namesFor(addresses: Iterable<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (address in addresses) {
            if (address in out) continue
            displayName(address)?.let { out[address] = it }
        }
        return out
    }

    private companion object {
        const val TAG = "ContactNameEnricher"
    }
}
