package com.commacompliance.archiver.upload

import java.io.InputStream

/**
 * Opens the raw bytes of a captured attachment, identified by its Telephony
 * `part_uri` (`content://mms/part/<id>`). Pulled behind an interface so the
 * hashing + upload logic is exercised in plain host JVM tests with synthetic
 * byte sources, while the app wires in a ContentResolver-backed implementation.
 *
 * A `content://mms/part` row's bytes are immutable once written, so opening the
 * same uri twice (once to hash, once to upload) yields identical bytes. An opener
 * returns null when the part no longer exists (the provider hard-deleted it); the
 * caller must treat that as "retain pending, retry later", never as a silent drop.
 */
fun interface AttachmentSource {
    /** A fresh stream over the attachment bytes, or null if the part is gone. */
    fun open(partUri: String): InputStream?
}
