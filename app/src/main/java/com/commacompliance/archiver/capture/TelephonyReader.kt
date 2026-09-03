package com.commacompliance.archiver.capture

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri

/**
 * Reads the current SMS + MMS/RCS state from the Telephony provider and normalizes
 * it to `NormalizedEvent`s. The archival broadcast is only a wake-up - it carries
 * no payload and fires multiple times per message - so on every invocation this
 * does a FULL scan of `content://sms` and `content://mms`; the delta vs the cached
 * snapshot is what the classifier reasons about.
 *
 * Every cursor is opened with `use { }` so it is closed even on error, including
 * the per-message part/addr sub-queries.
 */
/**
 * @param deviceNumber the local device's own number, looked up once by the caller
 *        (best-effort; may be null). Supplies the side an SMS row omits, since
 *        `content://sms` has no address table.
 */
open class TelephonyReader(
    private val resolver: ContentResolver,
    private val deviceNumber: String? = null,
) {

    open fun readAll(): List<NormalizedEvent> = readSms() + readMms()

    fun readSms(): List<NormalizedEvent> {
        val out = mutableListOf<NormalizedEvent>()
        resolver.query(SMS_URI, null, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndex("_id")
            val threadIdx = c.getColumnIndex("thread_id")
            val addrIdx = c.getColumnIndex("address")
            val dateIdx = c.getColumnIndex("date")
            val typeIdx = c.getColumnIndex("type")
            val bodyIdx = c.getColumnIndex("body")
            val creatorIdx = c.getColumnIndex("creator")
            while (c.moveToNext()) {
                val row = SmsRow(
                    id = c.getLong(idIdx),
                    threadId = c.getLong(threadIdx).toString(),
                    address = c.getStringOrNull(addrIdx),
                    dateMs = c.getLong(dateIdx),
                    type = c.getInt(typeIdx),
                    body = c.getStringOrNull(bodyIdx),
                    creator = c.getStringOrNull(creatorIdx),
                )
                out += TelephonyNormalizer.fromSms(row, deviceNumber)
            }
        }
        return out
    }

    fun readMms(): List<NormalizedEvent> {
        val threads = readThreads()
        val out = mutableListOf<NormalizedEvent>()
        resolver.query(MMS_URI, null, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndex("_id")
            val threadIdx = c.getColumnIndex("thread_id")
            val dateIdx = c.getColumnIndex("date")
            val boxIdx = c.getColumnIndex("msg_box")
            val ctTIdx = c.getColumnIndex("ct_t")
            val ctClsIdx = c.getColumnIndex("ct_cls")
            val creatorIdx = c.getColumnIndex("creator")
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val threadId = c.getLong(threadIdx).toString()
                val row = MmsRow(
                    id = id,
                    threadId = threadId,
                    dateSeconds = c.getLong(dateIdx),
                    msgBox = c.getInt(boxIdx),
                    ctType = c.getStringOrNull(ctTIdx),
                    ctClass = c.getIntOrNull(ctClsIdx),
                    creator = c.getStringOrNull(creatorIdx),
                )
                val parts = readParts(id)
                val addrs = readAddrs(id)
                out += TelephonyNormalizer.fromMms(row, parts, addrs, threads[threadId])
            }
        }
        return out
    }

    private fun readParts(mmsId: Long): List<MmsPart> {
        val out = mutableListOf<MmsPart>()
        resolver.query(PART_URI, null, "mid = ?", arrayOf(mmsId.toString()), null)?.use { c ->
            val idIdx = c.getColumnIndex("_id")
            val ctIdx = c.getColumnIndex("ct")
            val nameIdx = c.getColumnIndex("name")
            val fnIdx = c.getColumnIndex("fn")
            val textIdx = c.getColumnIndex("text")
            val dataIdx = c.getColumnIndex("_data")
            val seqIdx = c.getColumnIndex("seq")
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val dataPath = c.getStringOrNull(dataIdx)
                out += MmsPart(
                    id = id,
                    contentType = c.getStringOrNull(ctIdx),
                    name = c.getStringOrNull(nameIdx),
                    filename = c.getStringOrNull(fnIdx) ?: c.getStringOrNull(nameIdx),
                    text = c.getStringOrNull(textIdx),
                    dataPath = dataPath,
                    sizeBytes = if (dataPath != null) attachmentSize(id) else null,
                    seq = if (seqIdx >= 0) c.getInt(seqIdx) else 0,
                )
            }
        }
        return out
    }

    /**
     * Best-effort attachment size from the part's descriptor metadata only - never
     * reads the bytes. Reading a part stream to count bytes would be unbounded work
     * (large media) inside the time-boxed capture window, so size is taken from the
     * O(1) descriptor length when the provider reports it and left null otherwise.
     * The bytes themselves are uploaded later, out of band.
     */
    private fun attachmentSize(partId: Long): Long? = try {
        resolver.openAssetFileDescriptor(Uri.parse("content://mms/part/$partId"), "r")
            ?.use { it.length.takeIf { len -> len >= 0 } }
    } catch (_: Throwable) {
        null
    }

    private fun readAddrs(mmsId: Long): List<MmsAddr> {
        val out = mutableListOf<MmsAddr>()
        val uri = Uri.parse("content://mms/$mmsId/addr")
        resolver.query(uri, null, null, null, null)?.use { c ->
            val addrIdx = c.getColumnIndex("address")
            val typeIdx = c.getColumnIndex("type")
            while (c.moveToNext()) {
                out += MmsAddr(
                    address = c.getStringOrNull(addrIdx),
                    type = c.getInt(typeIdx),
                )
            }
        }
        return out
    }

    private fun readThreads(): Map<String, ThreadRow> {
        val out = mutableMapOf<String, ThreadRow>()
        resolver.query(THREADS_URI, null, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndex("_id")
            val typeIdx = c.getColumnIndex("type")
            val recipIdx = c.getColumnIndex("recipient_ids")
            while (c.moveToNext()) {
                val id = c.getLong(idIdx).toString()
                out[id] = ThreadRow(
                    id = id,
                    type = if (typeIdx >= 0) c.getInt(typeIdx) else 0,
                    recipientIds = if (recipIdx >= 0) c.getStringOrNull(recipIdx) else null,
                )
            }
        }
        return out
    }

    private fun Cursor.getStringOrNull(idx: Int): String? =
        if (idx < 0 || isNull(idx)) null else getString(idx)

    private fun Cursor.getIntOrNull(idx: Int): Int? =
        if (idx < 0 || isNull(idx)) null else getInt(idx)

    companion object {
        private val SMS_URI = Uri.parse("content://sms")
        private val MMS_URI = Uri.parse("content://mms")
        private val PART_URI = Uri.parse("content://mms/part")
        private val THREADS_URI = Uri.parse("content://mms-sms/conversations?simple=true")
    }
}
