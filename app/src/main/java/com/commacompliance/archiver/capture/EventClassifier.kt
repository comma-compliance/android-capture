package com.commacompliance.archiver.capture

import com.commacompliance.archiver.data.TelephonySnapshot

/** The four derived event types. Event type is never a provider column. */
enum class EventType(val wire: String) {
    RECEIVED("received"),
    SENT("sent"),
    EDITED("edited"),
    DELETED("deleted"),
}

/**
 * A classified event ready to persist: the type, the body hash, and the payload
 * JSON. For an edit, `editsProviderId` carries the correlated original row id so
 * the server can update rather than re-create. For a delete, `payloadJson` is the
 * cached last-known payload (no live row remains to read).
 */
data class ClassifiedEvent(
    val providerId: String,
    val eventType: EventType,
    val eventTs: Long,
    val bodyHash: String,
    val threadId: String,
    val sender: String?,
    val payloadJson: String,
)

/**
 * The minimal projection of a prior capture the classifier needs to apply the
 * edit-correlation rule. Backed by the durable `captured_events` history, NOT
 * merely the live snapshot, because an original message may have been seen many
 * broadcasts ago and is the thing an edit must correlate to.
 */
data class PriorCapture(
    val providerId: String,
    val threadId: String,
    val sender: String?,
    val timestampMs: Long,
    val bodyHash: String,
)

/**
 * Diffs the current full provider scan against the cached prior snapshot and the
 * durable capture history to derive event types, exactly per the observed contract:
 *
 * - A `provider_id` not previously seen is `received`/`sent` (by the direction
 *   column) UNLESS it correlates to an already-captured row by
 *   `(thread_id + sender + timestamp)` with a different `body_hash`, in which case
 *   it is an `edited` row (edits surface as a brand-new row that inherits the
 *   original's timestamp; without this rule the edit would masquerade as new).
 * - A `provider_id` in the prior snapshot but absent from the current scan is
 *   `deleted` (hard deletes leave no tombstone; absence is the only signal). The
 *   cached last-known payload is emitted.
 * - A `provider_id` present in both with an unchanged `body_hash` AND an unchanged
 *   attachment fingerprint is status churn (e.g. a read-flag flip) and produces no
 *   event.
 * - A `provider_id` present in both whose ATTACHMENT SET changed is media that the
 *   provider filled in after the row was first written - the ordinary MMS/RCS
 *   arrival pattern. It emits `edited` carrying the now-complete `attachments[]`.
 *   The text is identical in this case, so `body_hash` cannot detect it; see
 *   `AttachmentsFingerprint` for why the attachment dimension is a separate hash
 *   rather than a change to the frozen body-hash rule.
 *
 * Reactions need no special path: they arrive as ordinary new received rows.
 *
 * The classifier is pure. It returns the events to persist and the resulting
 * snapshot, but performs no IO; the caller serializes "read snapshot -> scan ->
 * classify -> persist events -> overwrite snapshot" as one unit, and crucially
 * classifies deletes (which require the prior snapshot) before overwriting it.
 */
class EventClassifier(
    /**
     * Looks up already-captured rows in a thread from a sender at a timestamp.
     * Used only for edit-correlation. Backed by the captured_events DAO in
     * production; a simple in-memory list in tests.
     */
    private val priorCaptureLookup: (threadId: String, sender: String?, timestampMs: Long) -> List<PriorCapture>,
) {

    data class Result(
        val events: List<ClassifiedEvent>,
        val snapshotUpserts: List<TelephonySnapshot>,
        val snapshotDeletes: List<String>,
    )

    /**
     * Governs first-run EMISSION only - never snapshot coverage. On the very first
     * capture (empty prior snapshot) the provider already holds the device's whole
     * message history; emitting all of it would dump pre-existing messages into the
     * archive against the operator's backfill choice.
     *
     * - [ForwardOnly] (backfill_days == 0, the default): emit nothing, but still
     *   snapshot the ENTIRE provider so subsequent diffs are forward-only.
     * - [Window] (backfill_days == N): emit only rows whose timestamp is within the
     *   last N days, but still snapshot the ENTIRE provider.
     * - [None]: not a first run; classify normally (every new row emits).
     *
     * CRITICAL: the snapshot ALWAYS covers every scanned row regardless of the
     * emission window. If a first run snapshotted only the emitted window, a later
     * reconciliation scan would see the un-snapshotted older rows as brand-new and
     * wrongly emit them. Coverage and emission are deliberately decoupled.
     */
    sealed interface FirstRunPolicy {
        data object None : FirstRunPolicy
        data object ForwardOnly : FirstRunPolicy
        data class Window(val sinceTimestampMs: Long) : FirstRunPolicy
    }

    fun classify(
        currentScan: List<NormalizedEvent>,
        priorSnapshot: List<TelephonySnapshot>,
        firstRunPolicy: FirstRunPolicy = FirstRunPolicy.None,
    ): Result {
        val priorById = priorSnapshot.associateBy { it.providerId }
        val currentById = currentScan.associateBy { it.providerId }

        val events = mutableListOf<ClassifiedEvent>()
        val upserts = mutableListOf<TelephonySnapshot>()
        val deletes = mutableListOf<String>()

        // Deletes FIRST, while the prior snapshot still proves the rows existed.
        detectDeletes(priorSnapshot, currentById, events, deletes)

        // Process new/changed rows in a stable order (timestamp, then provider id)
        // so that within a single scan an original row is emitted before an edit
        // that inherits its timestamp, letting same-run history participate in
        // correlation.
        val inRunCaptures = mutableListOf<PriorCapture>()
        val ordered = currentScan.sortedWith(
            compareBy<NormalizedEvent> { it.timestampMs }.thenBy { it.providerId },
        )

        for (event in ordered) {
            val prior = priorById[event.providerId]
            val bodyHash = BodyHash.of(event.body)

            if (prior != null) {
                classifyKnownRow(event, prior, bodyHash, firstRunPolicy, events, upserts)
                continue
            }

            val payloadJson = event.toPayloadJson().toString()

            // New provider_id: classify as edited vs received/sent.
            val correlated = findEditOriginal(event, bodyHash, inRunCaptures)
            val type = when {
                correlated != null -> EventType.EDITED
                event.direction == Direction.OUTGOING -> EventType.SENT
                else -> EventType.RECEIVED
            }

            val payloadForEvent = if (type == EventType.EDITED && correlated != null) {
                // Annotate the payload with the correlated original's id so the
                // server can update the existing message rather than insert a new one.
                event.toPayloadJson()
                    .put("edits_provider_id", correlated.providerId)
                    .toString()
            } else {
                payloadJson
            }

            // Snapshot coverage is unconditional; emission obeys the first-run policy.
            // The row is always recorded in the baseline snapshot below so future
            // diffs never re-discover it as new - even when it is not emitted here.
            if (shouldEmitOnFirstRun(firstRunPolicy, event.timestampMs)) {
                events += ClassifiedEvent(
                    providerId = event.providerId,
                    eventType = type,
                    eventTs = event.timestampMs,
                    bodyHash = bodyHash,
                    threadId = event.threadId,
                    sender = event.sender,
                    payloadJson = payloadForEvent,
                )
            }
            upserts += snapshotOf(event, bodyHash, payloadJson)
            inRunCaptures += PriorCapture(
                providerId = event.providerId,
                threadId = event.threadId,
                sender = event.sender,
                timestampMs = event.timestampMs,
                bodyHash = bodyHash,
            )
        }

        return Result(events, upserts, deletes)
    }

    /**
     * Handle a row already present in the prior snapshot.
     *
     * Unchanged body AND unchanged attachment set is status churn (e.g. a read-flag
     * flip): no event, and no snapshot rewrite either, so an ordinary broadcast does
     * not rewrite the whole table.
     *
     * A changed ATTACHMENT SET is the MMS/RCS late-media case: the provider persists
     * the message row first and fills the binary in afterwards under the same
     * provider id. The text is untouched, so `body_hash` is identical across both
     * passes and cannot see it.
     *
     * A changed BODY on the same provider id is NOT an edit per the capture contract
     * (a real edit arrives as a brand-new row inheriting the original's timestamp),
     * so it only refreshes the snapshot.
     */
    private fun classifyKnownRow(
        event: NormalizedEvent,
        prior: TelephonySnapshot,
        bodyHash: String,
        firstRunPolicy: FirstRunPolicy,
        events: MutableList<ClassifiedEvent>,
        upserts: MutableList<TelephonySnapshot>,
    ) {
        val attachmentsHash = AttachmentsFingerprint.of(event.attachments)
        val bodyChanged = prior.bodyHash != bodyHash
        // A NULL prior fingerprint predates the attachments_hash column, so there is
        // nothing to compare against. Record it silently rather than guessing:
        // treating unknown as "changed" would emit an edit for every media-bearing
        // message already on the device the first time an upgraded build runs.
        val fingerprintUnknown = prior.attachmentsHash == null
        val attachmentsChanged = !fingerprintUnknown && prior.attachmentsHash != attachmentsHash

        if (attachmentsChanged && shouldEmitOnFirstRun(firstRunPolicy, event.timestampMs)) {
            // Emitted as EDITED, not as a repeat of the original received/sent,
            // because the local and server idempotency keys are both
            // (provider_id, event_type, event_ts, body_hash) - re-sending the original
            // type would collide on all four and be discarded as a duplicate. EDITED
            // differs in event_type, so it lands. The payload carries the now-complete
            // attachments[], and the event is held back from upload until their
            // content hashes are computed (see CapturedEvent.readyForUpload), so the
            // server never receives attachment metadata it cannot link.
            events += ClassifiedEvent(
                // The EVENT id carries a fingerprint discriminator; the PAYLOAD keeps
                // the true provider id. Both idempotency keys (local and server) are
                // (provider_id, event_type, event_ts, body_hash), and for an
                // attachment-only change the other three are identical to any earlier
                // attachment event on this row - so a bare provider id would collide
                // and be dropped the SECOND time a row's attachments change (an RCS
                // message whose two file transfers complete in different scans, or
                // media landing on a row already classified as edited). The snapshot
                // advances regardless, so that drop would lose the media permanently.
                //
                // The discriminator is derived from the attachment fingerprint, so it
                // is DETERMINISTIC: re-scanning an unchanged state re-derives the same
                // id and is correctly deduped, while a genuinely new attachment set
                // always produces a new id and lands.
                //
                // Safe because the server resolves the message from the PAYLOAD:
                // message_params, record_attachment_metadata, apply_delete and
                // correlated_message all read `payload["provider_id"]` first and only
                // fall back to the event column. The event column is the raw-event
                // idempotency discriminator, which is exactly what is being varied.
                providerId = attachmentRevisionEventId(event.providerId, attachmentsHash),
                eventType = EventType.EDITED,
                eventTs = event.timestampMs,
                bodyHash = bodyHash,
                threadId = event.threadId,
                sender = event.sender,
                // Correlates to ITSELF: the media completed on an existing row, so the
                // server must update that message rather than insert a second one.
                payloadJson = event.toPayloadJson()
                    .put("edits_provider_id", event.providerId)
                    .toString(),
            )
        }

        if (bodyChanged || attachmentsChanged || fingerprintUnknown) {
            upserts += snapshotOf(event, bodyHash, event.toPayloadJson().toString(), attachmentsHash)
        }
    }

    /**
     * Emit a DELETED event for every prior-snapshot row no longer present in the
     * current scan. Run before the upsert pass, while the prior snapshot still
     * proves the rows existed. Appends into [events] and [deletes].
     */
    private fun detectDeletes(
        priorSnapshot: List<TelephonySnapshot>,
        currentById: Map<String, NormalizedEvent>,
        events: MutableList<ClassifiedEvent>,
        deletes: MutableList<String>,
    ) {
        for (prior in priorSnapshot) {
            if (!currentById.containsKey(prior.providerId)) {
                events += ClassifiedEvent(
                    providerId = prior.providerId,
                    eventType = EventType.DELETED,
                    eventTs = prior.timestampMs,
                    bodyHash = prior.bodyHash,
                    threadId = prior.threadId,
                    sender = prior.sender,
                    payloadJson = prior.rawJson,
                )
                deletes += prior.providerId
            }
        }
    }

    /**
     * Whether a NEW row should be emitted given the first-run policy. Non-first-run
     * passes (the common case) always emit. On first run, ForwardOnly emits nothing
     * and Window emits only rows at/after its cutoff. Either way the caller still
     * snapshots the row, so coverage is unaffected.
     */
    private fun shouldEmitOnFirstRun(policy: FirstRunPolicy, timestampMs: Long): Boolean =
        when (policy) {
            FirstRunPolicy.None -> true
            FirstRunPolicy.ForwardOnly -> false
            is FirstRunPolicy.Window -> timestampMs >= policy.sinceTimestampMs
        }

    /**
     * The raw-event id for an attachment-set change: the row's provider id plus a
     * short, deterministic slice of the attachment fingerprint.
     *
     * A prefix of the hash is enough - this only has to distinguish the successive
     * attachment states of ONE message row, not be globally collision-free, and the
     * full provider id is still present as the prefix. It stays inside the
     * `captured_events.provider_id` / `android_message_events.provider_id` string
     * column and never reaches `payload["provider_id"]`, which remains the message's
     * real identity.
     */
    private fun attachmentRevisionEventId(providerId: String, attachmentsHash: String) =
        "$providerId#att:${attachmentsHash.take(ATTACHMENT_REVISION_ID_HASH_CHARS)}"

    private fun findEditOriginal(
        event: NormalizedEvent,
        bodyHash: String,
        inRunCaptures: List<PriorCapture>,
    ): PriorCapture? {
        val candidates = priorCaptureLookup(event.threadId, event.sender, event.timestampMs) +
            inRunCaptures.filter {
                it.threadId == event.threadId &&
                    it.sender == event.sender &&
                    it.timestampMs == event.timestampMs
            }
        // An edit shares (thread, sender, timestamp) with the original but differs
        // in body, and is a DIFFERENT provider row. Sender equality already implies
        // the same direction (an edit inherits the original's sender), so the
        // remaining false-positive risk is two genuinely distinct messages from the
        // same sender at the identical millisecond with different bodies - vanishing
        // in practice. Note: if a stable per-message key ever surfaces in the
        // provider, correlate on it instead of the (thread, sender, timestamp) heuristic.
        return candidates.firstOrNull {
            it.providerId != event.providerId && it.bodyHash != bodyHash
        }
    }

    private fun snapshotOf(
        event: NormalizedEvent,
        bodyHash: String,
        payloadJson: String,
        attachmentsHash: String = AttachmentsFingerprint.of(event.attachments),
    ) =
        TelephonySnapshot(
            providerId = event.providerId,
            bodyHash = bodyHash,
            timestampMs = event.timestampMs,
            threadId = event.threadId,
            sender = event.sender,
            rawJson = payloadJson,
            attachmentsHash = attachmentsHash,
        )

    private companion object {
        /**
         * Hex characters of the attachment fingerprint kept in the raw-event id. 12
         * hex chars is 48 bits, which only has to separate the handful of successive
         * attachment states of a single message row.
         */
        const val ATTACHMENT_REVISION_ID_HASH_CHARS = 12
    }
}
