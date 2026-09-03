package com.commacompliance.archiver.upload

import com.commacompliance.archiver.data.CapturedEvent
import com.commacompliance.archiver.data.CapturedEventDao

/** In-memory CapturedEventDao for the upload path tests (no Room/sqlite needed). */
class FakeCapturedEventDao(seed: List<CapturedEvent> = emptyList()) : CapturedEventDao {
    private val rows = seed.toMutableList()

    override fun insert(event: CapturedEvent): Long {
        rows.add(event)
        return event.id
    }

    override fun findByCorrelation(threadId: String, sender: String?, eventTs: Long): List<CapturedEvent> =
        rows.filter { it.threadId == threadId && it.eventTs == eventTs && it.sender == sender }

    override fun pendingUpload(limit: Int): List<CapturedEvent> =
        rows.filter { !it.uploaded && it.readyForUpload }.sortedBy { it.id }.take(limit)

    override fun markUploaded(ids: List<Long>) {
        val idSet = ids.toSet()
        for (i in rows.indices) {
            if (rows[i].id in idSet) rows[i] = rows[i].copy(uploaded = true)
        }
    }

    override fun pendingAttachmentHashing(limit: Int): List<CapturedEvent> =
        rows.filter { !it.uploaded && !it.readyForUpload }.sortedBy { it.id }.take(limit)

    override fun updatePayload(id: Long, rawJson: String, readyForUpload: Boolean) {
        for (i in rows.indices) {
            if (rows[i].id == id) rows[i] = rows[i].copy(rawJson = rawJson, readyForUpload = readyForUpload)
        }
    }

    /** The stored payload, ready or not - lets a test inspect a held-back event. */
    fun rawJsonOf(id: Long): String = rows.first { it.id == id }.rawJson

    override fun count(): Int = rows.size

    override fun pendingCount(): Int = rows.count { !it.uploaded }

    override fun pendingUploadCount(): Int = rows.count { !it.uploaded && it.readyForUpload }

    // The real query also excludes rows whose provider_id is still in
    // telephony_snapshot (edit-correlation protection); this fake has no snapshot, so
    // it prunes by uploaded+age only. Snapshot-aware pruning is covered by the
    // Room-backed QueueRetentionTest.
    override fun pruneUploadedOlderThan(cutoff: Long): Int {
        val before = rows.size
        rows.retainAll { !(it.uploaded && it.createdAt < cutoff) }
        return before - rows.size
    }
}
