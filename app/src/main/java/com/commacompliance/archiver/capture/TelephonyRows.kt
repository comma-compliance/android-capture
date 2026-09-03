package com.commacompliance.archiver.capture

/**
 * Plain-data projections of the Telephony provider rows the capture layer reads,
 * decoupled from the Android `Cursor` API so the normalization logic is unit
 * testable against synthetic inputs. `TelephonyReader` fills these from real
 * cursors; `TelephonyNormalizer` turns them into `NormalizedEvent`s.
 */

/** A `content://sms` row. `date` is epoch milliseconds; `type` 1=inbox/2=sent. */
data class SmsRow(
    val id: Long,
    val threadId: String,
    val address: String?,
    val dateMs: Long,
    val type: Int,
    val body: String?,
    val creator: String?,
)

/**
 * A `content://mms` row (covers MMS and RCS). `date` is epoch SECONDS (must be
 * `* 1000`). `msgBox` 1=inbox/2=sent. RCS is marked by `ctCls == 135`.
 */
data class MmsRow(
    val id: Long,
    val threadId: String,
    val dateSeconds: Long,
    val msgBox: Int,
    val ctType: String?,
    val ctClass: Int?,
    val creator: String?,
)

/** A `content://mms/part` row linked by `mid` = mms `_id`. */
data class MmsPart(
    val id: Long,
    val contentType: String?,
    val name: String?,
    val filename: String?,
    val text: String?,
    /** Provider-private file path; non-null marks a binary attachment. */
    val dataPath: String?,
    val sizeBytes: Long?,
    val seq: Int,
)

/** A `content://mms/<id>/addr` row. `type` is a WAP PduHeaders role. */
data class MmsAddr(
    val address: String?,
    val type: Int,
)

/** A thread row from `content://mms-sms/conversations?simple=true`. */
data class ThreadRow(
    val id: String,
    val type: Int,
    val recipientIds: String?,
)

object PduAddrType {
    const val FROM = 137
    const val TO = 151
    const val CC = 130
}

object MmsConstants {
    /** `ct_cls = 135` distinguishes an RCS row from a real MMS (NULL there). */
    const val RCS_CT_CLASS = 135
    const val MSG_BOX_INBOX = 1
    const val MSG_BOX_SENT = 2
}

object SmsConstants {
    const val TYPE_INBOX = 1
    const val TYPE_SENT = 2
}
