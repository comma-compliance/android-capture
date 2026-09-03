package com.commacompliance.archiver.capture

import android.util.Log
import com.commacompliance.archiver.data.ArchiverDatabase
import com.commacompliance.archiver.data.ArchiverMetaDao
import com.commacompliance.archiver.data.CapturedEvent
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Runs one capture pass as a single serialized unit:
 *   read prior snapshot -> full provider scan -> classify -> persist classified
 *   events -> overwrite snapshot -> update markers,
 * all in one Room transaction.
 *
 * The SAME pass backs both triggers: the per-broadcast capture and the periodic
 * full reconciliation scan (heartbeat / connectivity-regained). They are identical
 * snapshot-diffs over the whole provider; only the trigger differs. The whole pass
 * is guarded by a process-wide lock so the two can never run concurrently, read the
 * same snapshot, and double-emit. A pass over unchanged provider state emits zero
 * events and only advances `last_scanned_at` - re-running on unchanged state is a
 * no-op.
 *
 * First-run baseline: on the very first pass (no `first_run_done` marker) the
 * provider already holds the device's whole message history. [backfillDaysProvider]
 * decides EMISSION: 0 = emit nothing (forward-only default), N = emit only the last
 * N days, 9999 ≈ all. Regardless of the emission window the baseline snapshot covers
 * the ENTIRE provider, so a later reconciliation scan never mistakes pre-existing
 * older rows for new ones. The first-run decision, the full baseline snapshot, and
 * the `first_run_done` marker all commit in one transaction; a crash before commit
 * leaves no marker, so the next pass safely re-establishes the baseline rather than
 * treating everything as new.
 *
 * Honest completeness limit: capture derives event type purely by diffing the
 * provider against the last committed snapshot. A change that fully occurs between
 * two passes and leaves NO surviving provider trace (e.g. a message added and then
 * hard-deleted entirely within one gap, so it is absent from both snapshots) is
 * inherently unobservable - there is nothing left to diff. Per-broadcast capture
 * plus periodic reconciliation shrink that gap to near zero, but cannot eliminate it.
 *
 * No network or crypto happens here; that is the upload pipeline's job.
 */
class CaptureCoordinator(
    private val reader: TelephonyReader,
    private val db: ArchiverDatabase,
    /**
     * Resolved first-run backfill window in days, consulted ONLY on the first pass.
     * 0 = forward-only (default). Negative is coerced to 0. Defaults to 0 so a pass
     * with no policy wired is forward-only rather than an accidental full dump.
     */
    private val backfillDaysProvider: () -> Int = { 0 },
    private val now: () -> Long = { System.currentTimeMillis() },
    /**
     * Resolves saved contact display names for addresses that appear in captured
     * messages. Defaults to a no-op resolver (no names) so a coordinator built
     * without one - e.g. in tests - behaves exactly as before. The production
     * factory supplies a [PhoneLookupContactNameResolver].
     */
    private val contactNameResolver: ContactNameResolver = ContactNameResolver { null },
) {

    data class Outcome(val newEvents: Int, val deletes: Int, val scanned: Int, val firstRun: Boolean)

    fun runOnce(): Outcome {
        lock.lock()
        try {
            val eventDao = db.capturedEventDao()
            val snapshotDao = db.telephonySnapshotDao()
            val metaDao = db.archiverMetaDao()

            val firstRun = metaDao.get(ArchiverMetaDao.KEY_FIRST_RUN_DONE) != "true"
            val firstRunPolicy = if (firstRun) resolveFirstRunPolicy() else EventClassifier.FirstRunPolicy.None

            val currentScan = enrichContactNames(reader.readAll())
            val priorSnapshot = snapshotDao.all()

            // On first run the captured_events history is empty, so the durable
            // edit-correlation lookup can only return nothing. Skip the per-row DB
            // query entirely - a first-run baseline can scan the device's whole
            // message history (potentially thousands of rows) inside a time-boxed
            // window, and a point query per row would be needless work.
            val correlationLookup: (String, String?, Long) -> List<PriorCapture> = if (firstRun) {
                { _, _, _ -> emptyList() }
            } else {
                { threadId, sender, ts ->
                    eventDao.findByCorrelation(threadId, sender, ts).map { prior ->
                        PriorCapture(
                            providerId = prior.providerId,
                            threadId = prior.threadId,
                            sender = prior.sender,
                            timestampMs = prior.eventTs,
                            bodyHash = prior.bodyHash,
                        )
                    }
                }
            }
            val classifier = EventClassifier(correlationLookup)

            val result = classifier.classify(currentScan, priorSnapshot, firstRunPolicy)

            try {
                commit(eventDao, snapshotDao, metaDao, result, firstRun)
            } catch (e: android.database.sqlite.SQLiteFullException) {
                // Out of storage. We do NOT drop or trim un-uploaded events to make
                // room - that would silently lose archive data. Instead surface a
                // blocked state for the UI and rethrow so the pass fails loudly and
                // the next trigger retries once the upload drain has freed space.
                metaDao.putValue(ArchiverMetaDao.KEY_STORAGE_BLOCKED, "true")
                Log.e(TAG, "storage full; capture blocked (no events dropped)")
                throw e
            }
            // A successful commit means we have room again; clear any prior block.
            if (metaDao.get(ArchiverMetaDao.KEY_STORAGE_BLOCKED) == "true") {
                metaDao.putValue(ArchiverMetaDao.KEY_STORAGE_BLOCKED, "false")
            }

            return Outcome(
                newEvents = result.events.count { it.eventType != EventType.DELETED },
                deletes = result.snapshotDeletes.size,
                scanned = currentScan.size,
                firstRun = firstRun,
            )
        } finally {
            lock.unlock()
        }
    }

    private fun commit(
        eventDao: com.commacompliance.archiver.data.CapturedEventDao,
        snapshotDao: com.commacompliance.archiver.data.TelephonySnapshotDao,
        metaDao: ArchiverMetaDao,
        result: EventClassifier.Result,
        firstRun: Boolean,
    ) {
        db.runInTransaction {
                for (event in result.events) {
                    // An event carrying attachments is held back from upload until the
                    // out-of-band hasher computes each attachment's content_hash and
                    // patches it into the payload; the server links attachment bytes by
                    // content_hash, so the event must not drain before its hashes exist.
                    // Hashing streams the bytes and so must never run in the capture
                    // window - hence the gate rather than hashing here.
                    val ready = !hasAttachments(event.payloadJson)
                    eventDao.insert(
                        CapturedEvent(
                            providerId = event.providerId,
                            eventType = event.eventType.wire,
                            eventTs = event.eventTs,
                            bodyHash = event.bodyHash,
                            threadId = event.threadId,
                            sender = event.sender,
                            rawJson = event.payloadJson,
                            readyForUpload = ready,
                        ),
                    )
                }
                if (result.snapshotDeletes.isNotEmpty()) {
                    snapshotDao.deleteByProviderIds(result.snapshotDeletes)
                }
                if (result.snapshotUpserts.isNotEmpty()) {
                    snapshotDao.upsertAll(result.snapshotUpserts)
                }
                // Markers commit in the SAME transaction as the snapshot. The
                // first_run_done flag must never be set without the baseline it
                // attests to, or a crash would orphan the flag against an empty
                // snapshot and re-dump the whole provider next pass.
                if (firstRun) {
                    metaDao.putValue(ArchiverMetaDao.KEY_FIRST_RUN_DONE, "true")
                }
                metaDao.putValue(ArchiverMetaDao.KEY_LAST_SCANNED_AT, now().toString())
            }
    }

    // True when the normalized payload carries at least one attachment, so the
    // event must wait for out-of-band hashing before it can upload. A malformed
    // payload (should never happen for our own writes) is treated as no
    // attachments so it can still drain rather than wedge forever.
    private fun hasAttachments(payloadJson: String): Boolean = try {
        org.json.JSONObject(payloadJson)
            .optJSONArray("attachments")
            ?.let { it.length() > 0 } ?: false
    } catch (_: org.json.JSONException) {
        false
    }

    /**
     * Populate each event's `contactNames` with the saved display names for the
     * union of its `from + to + cc + participants` addresses, before the classifier
     * serializes the payload. A single [ContactNameEnricher] is reused for the whole
     * run so each distinct address is queried at most once, and a denied/throwing
     * resolver yields empty maps rather than failing the pass.
     */
    private fun enrichContactNames(scan: List<NormalizedEvent>): List<NormalizedEvent> {
        val enricher = ContactNameEnricher(contactNameResolver)
        return scan.map { event ->
            val addresses = (listOfNotNull(event.from) + event.to + event.cc + event.participants)
            val names = enricher.namesFor(addresses)
            if (names.isEmpty()) event else event.copy(contactNames = names)
        }
    }

    private fun resolveFirstRunPolicy(): EventClassifier.FirstRunPolicy {
        val days = backfillDaysProvider().coerceAtLeast(0)
        return when {
            days <= 0 -> EventClassifier.FirstRunPolicy.ForwardOnly
            // A very large window (9999 ≈ "all history") yields a cutoff far in the
            // past, so every existing row is in-window without special-casing.
            else -> {
                val cutoff = now() - TimeUnit.DAYS.toMillis(days.toLong())
                Log.i(TAG, "first-run backfill window: $days day(s)")
                EventClassifier.FirstRunPolicy.Window(cutoff.coerceAtLeast(0))
            }
        }
    }

    companion object {
        private const val TAG = "CaptureCoordinator"

        // One in-flight capture at a time across all broadcasts AND reconciliation
        // scans in this process: the per-broadcast and periodic diffs are serialized
        // so they cannot race the snapshot and double-emit. All capture work runs in
        // this single app process, so an in-process lock is sufficient.
        private val lock = ReentrantLock()
    }
}
