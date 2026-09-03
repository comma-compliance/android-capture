package com.commacompliance.archiver.capture

/**
 * Pure normalization from raw provider-row projections to the frozen
 * `NormalizedEvent` shape. Kept free of Android `Cursor`/`ContentResolver`
 * dependencies so every branch (RCS vs MMS discrimination, second-vs-millisecond
 * date handling, part text/attachment split, addr role resolution, group vs 1:1)
 * is unit testable against synthetic rows.
 */
object TelephonyNormalizer {

    /**
     * An SMS row. Sender/recipient derive from the direction column, not address
     * comparison. `content://sms` has no addr table, so the local device number
     * (looked up once and passed in) supplies the side the row omits:
     * - inbound: `from` = `address` (peer), `to` = [device]
     * - outbound: `from` = device, `to` = [address] (peer)
     * `deviceNumber` may be null when the platform does not expose it; in that case
     * the device side is left out and the server resolves it from the worker.
     */
    fun fromSms(row: SmsRow, deviceNumber: String? = null): NormalizedEvent {
        val incoming = row.type == SmsConstants.TYPE_INBOX
        val direction = if (incoming) Direction.INCOMING else Direction.OUTGOING
        val peer = row.address
        val from = if (incoming) peer else deviceNumber
        val to = if (incoming) listOfNotNull(deviceNumber) else listOfNotNull(peer)
        val participants = (listOfNotNull(from) + to).distinct()

        return NormalizedEvent(
            source = Source.SMS,
            providerId = "sms:${row.id}",
            threadId = row.threadId,
            threadType = ThreadType.ONE_TO_ONE,
            direction = direction,
            timestampMs = row.dateMs,
            from = from,
            to = to,
            cc = emptyList(),
            participants = participants,
            body = row.body ?: "",
            attachments = emptyList(),
            isRcs = false,
            creator = row.creator,
        )
    }

    /**
     * An MMS or RCS row. `parts` are this message's `content://mms/part` rows and
     * `addrs` its `content://mms/<id>/addr` rows. RCS is detected by `ct_cls=135`.
     *
     * Body is the joined text of all `text/plain` parts (in `seq`, then `_id`
     * order) with "\n". Attachments are parts whose `_data` path is non-null;
     * `text/plain` parts are never attachments even when carrying a location URL.
     *
     * Group membership for RCS comes ONLY from the per-message addr rows, since an
     * RCS thread's `recipient_ids` is a single opaque `@rcs.google.com` token.
     */
    fun fromMms(
        row: MmsRow,
        parts: List<MmsPart>,
        addrs: List<MmsAddr>,
        thread: ThreadRow?,
    ): NormalizedEvent {
        val isRcs = row.ctClass == MmsConstants.RCS_CT_CLASS
        val incoming = row.msgBox == MmsConstants.MSG_BOX_INBOX
        val direction = if (incoming) Direction.INCOMING else Direction.OUTGOING

        val orderedParts = parts.sortedWith(compareBy<MmsPart> { it.seq }.thenBy { it.id })
        val body = orderedParts
            .filter { isTextPart(it) }
            .mapNotNull { it.text }
            .joinToString("\n")

        val attachments = orderedParts
            .filter { it.dataPath != null }
            .map {
                Attachment(
                    partId = it.id.toString(),
                    partUri = "content://mms/part/${it.id}",
                    contentType = it.contentType ?: "application/octet-stream",
                    filename = it.filename ?: it.name,
                    sizeBytes = it.sizeBytes,
                )
            }

        val from = addrs.firstOrNull { it.type == PduAddrType.FROM }?.address
        val to = addrs.filter { it.type == PduAddrType.TO }.mapNotNull { it.address }
        val cc = addrs.filter { it.type == PduAddrType.CC }.mapNotNull { it.address }
        val participants = (listOfNotNull(from) + to + cc).distinct()

        val threadType = resolveThreadType(isRcs, to, cc, thread)

        return NormalizedEvent(
            source = if (isRcs) Source.RCS else Source.MMS,
            providerId = "mms:${row.id}",
            threadId = row.threadId,
            threadType = threadType,
            direction = direction,
            timestampMs = row.dateSeconds * 1000L,
            from = from,
            to = to,
            cc = cc,
            participants = participants,
            body = body,
            attachments = attachments,
            isRcs = isRcs,
            creator = row.creator,
        )
    }

    private fun isTextPart(part: MmsPart): Boolean {
        val ct = part.contentType?.lowercase() ?: return false
        // text/plain parts are body content (including RCS location Maps URLs);
        // SMIL layout parts are structural and never body.
        return ct == "text/plain"
    }

    private fun resolveThreadType(
        isRcs: Boolean,
        to: List<String>,
        cc: List<String>,
        thread: ThreadRow?,
    ): ThreadType {
        // RCS group token at thread level is opaque, so membership/group-ness comes
        // from the per-message addr rows: more than one non-device participant
        // (To + Cc) means a group.
        if (cc.isNotEmpty()) return ThreadType.GROUP
        if (to.size > 1) return ThreadType.GROUP
        // Fall back to the MMS thread table for real MMS (type=1 == group), which
        // is reliable there (unlike RCS).
        if (!isRcs && thread?.type == 1 && (thread.recipientIds?.trim()?.contains(' ') == true)) {
            return ThreadType.GROUP
        }
        return ThreadType.ONE_TO_ONE
    }
}
