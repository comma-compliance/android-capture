package com.commacompliance.archiver.upload

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.InputStream

/**
 * The production [AttachmentSource]: opens a Telephony `content://mms/part/<id>`
 * stream via the ContentResolver. Returns null when the part is gone or
 * unreadable so the caller retains it pending and retries rather than dropping it.
 */
class ContentResolverAttachmentSource(context: Context) : AttachmentSource {
    private val resolver = context.applicationContext.contentResolver

    // Opening an arbitrary content:// URI can throw any of
    // SecurityException/IllegalStateException/FileNotFoundException/etc.; a broad
    // catch returns null so the caller keeps the part pending and retries.
    @Suppress("TooGenericExceptionCaught")
    override fun open(partUri: String): InputStream? = try {
        resolver.openInputStream(Uri.parse(partUri))
    } catch (e: Exception) {
        Log.w(TAG, "could not open attachment stream: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        private const val TAG = "AttachmentSource"
    }
}
