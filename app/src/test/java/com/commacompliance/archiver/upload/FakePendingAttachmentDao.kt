package com.commacompliance.archiver.upload

import com.commacompliance.archiver.data.PendingAttachment
import com.commacompliance.archiver.data.PendingAttachmentDao

/** In-memory PendingAttachmentDao for the attachment-path tests (no Room needed). */
class FakePendingAttachmentDao(seed: List<PendingAttachment> = emptyList()) : PendingAttachmentDao {
    private val rows = seed.toMutableList()
    private var nextId = (seed.maxOfOrNull { it.id } ?: 0L) + 1

    fun all(): List<PendingAttachment> = rows.toList()

    override fun insert(attachment: PendingAttachment): Long {
        // Mirror the unique (content_hash, part_uri) IGNORE-on-conflict semantics.
        if (rows.any { it.contentHash == attachment.contentHash && it.partUri == attachment.partUri }) {
            return -1
        }
        val id = if (attachment.id == 0L) nextId++ else attachment.id
        rows.add(attachment.copy(id = id))
        return id
    }

    override fun pendingUploads(limit: Int): List<PendingAttachment> =
        rows.filter { !it.uploaded }.sortedBy { it.id }.take(limit)

    override fun isUploaded(contentHash: String): Boolean =
        rows.any { it.contentHash == contentHash && it.uploaded }

    override fun markUploaded(id: Long) {
        for (i in rows.indices) {
            if (rows[i].id == id) rows[i] = rows[i].copy(uploaded = true)
        }
    }

    override fun markUploadedByHash(contentHash: String) {
        for (i in rows.indices) {
            if (rows[i].contentHash == contentHash) rows[i] = rows[i].copy(uploaded = true)
        }
    }

    override fun pendingCount(): Int = rows.count { !it.uploaded }

    override fun pruneUploadedOlderThan(cutoff: Long): Int {
        val before = rows.size
        rows.retainAll { !(it.uploaded && it.createdAt < cutoff) }
        return before - rows.size
    }
}
