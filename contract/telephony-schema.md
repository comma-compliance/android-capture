# Telephony archival contract - observed schema

Frozen-by-observation schema for the message-archival capture layer, produced by
the hands-on contract spike on a real fully-managed (device-owner) device. The
full event matrix - SMS, MMS, **and RCS** (1:1, group, attachment, edit,
reaction, delete, RCS->SMS fallback) - was captured live and is documented here.

## Coverage

| Event | State |
|------|-------|
| SMS send / receive | Observed |
| MMS send / receive (+ image attachment) | Observed |
| MMS group | Observed |
| RCS 1:1 send / receive | Observed |
| RCS attachment (image) | Observed |
| RCS group | Observed |
| RCS edit (sent and received) | Observed |
| RCS reaction / tapback | Observed |
| RCS -> SMS fallback | Observed |
| Delete (SMS / MMS / RCS) | Observed |
| Broadcast `GOOGLE_MESSAGES_ARCHIVAL_UPDATE` auto-fire | Observed (build-gated - see below) |
| Capture while locked / deep Doze | Observed - no message loss |
| Attachment types (audio/video/image/vCard/location) | Observed |
| Status-update (read/delivery) stability | Observed - body_hash stable |
| Long multipart SMS | Observed - single reassembled row |
| Per-OEM variance (Pixel / Samsung) | **Pending** - this capture is Motorola only |

## Test environment

- **Device:** Motorola `moto g - 2025` (codename `kansas`), **Android 15 / SDK 35**,
  fully-managed via TestDPC 9.0.12 device owner (`Device Owner Type: 0`,
  `testOnlyAdmin=false`).
- **Default SMS app:** Google Messages (`com.google.android.apps.messaging`),
  already default on this OEM (behaves like the Pixel path, not the Samsung
  "can it be default" path).
- **Google Messages build matters (see Finding 1):**
  - `messages.android_20250225_00_RC03` (Feb 2025) - did **NOT** fire the archival broadcast.
  - `messages.android_20260428_00_RC02` (Apr 2026, via Play update) - **fires it**, and is also the build on which RCS provisioned.
- **Managed config:** `messages_archival = com.commacompliance.archiver` set on
  Google Messages via the device owner (TestDPC "Apps -> Manage configurations" -
  the raw key/value bundle editor; TestDPC's schema-driven restrictions UI will
  NOT expose this undocumented key).
- **RCS:** provisioned over Google's Jibe backbone built into the updated
  Messages app (this device has no `com.google.android.ims` Carrier Services and
  the SIM's carrier config has RCS disabled - the new Messages does OTT RCS
  itself). Numbers/bodies in fixtures are anonymized; columns/codes/encodings are
  reproduced exactly as observed.

---

## DESIGN-CRITICAL FINDINGS (Phases 2-4 must build to these)

1. **The broadcast is a wake-up, not a payload.** Over 9 rapid-fire messages,
   22 broadcasts fired but only ONE carried an `EXTRA_ARCHIVAL_URI`; the rest
   were bare. Multiple broadcasts fire per logical message (send + delivery +
   read status), and read receipts fire broadcasts with no provider row. **The
   capture layer MUST, on each broadcast, scan `content://sms` + `content://mms`
   for the delta vs the prior snapshot - it cannot rely on the broadcast count or
   the extra.** Every message reliably lands as a provider row regardless.

2. **`EXTRA_ARCHIVAL_URI` is an unreliable hint.**
   - It arrives as a **Parcelable `Uri`, NOT a String** - read it with
     `intent.getParcelableExtra(EXTRA_ARCHIVAL_URI, Uri::class.java)`;
     `getStringExtra()` returns null.
   - When present it may point at the exact row (`content://mms/sent/23`,
     `content://sms/7`), a box view (`content://mms/inbox/24`), a bare table
     (`content://sms`), a **stale/old** row (observed pointing at `content://mms/6`
     for a brand-new message), or a **Google Messages-internal media URI**
     (`content://com.google.android.apps.messaging.shared.datamodel.MediaScratchFileProvider/...`
     for an attachment). Treat it as a best-effort hint to prioritize a row;
     always reconcile against an actual provider scan.

3. **RCS lands in the standard Telephony provider as `content://mms` rows** - it
   is NOT in a separate/undocumented store. This is the make-or-break unknown,
   resolved positively: RCS is capturable exactly like MMS. **RCS rows are
   distinguished from real MMS by `ct_cls = 135`** (NULL on real MMS), plus
   `m_id`/`ct_l` NULL and `m_size = 0`. (`ct_cls` is an MMS-table-only column; it
   does not exist on `content://sms`, so SMS/fallback vs RCS is unambiguous by
   table.)

4. **Edits surface as a NEW row, not an in-place change.** Editing a message
   (sent or received) creates a new `content://mms` row with the edited body; the
   original row is **retained unchanged**. There is no `edited` flag and no
   in-row back-reference (the `tr_id` proto holds a fresh per-message id with no
   decodable pointer to the original). **The one reliable correlation signal: the
   edit row inherits the original's `date`/`date_sent` (identical timestamp),
   same `thread_id`, same sender.** A pure snapshot-diff would mis-read the edit
   as a new message (duplicate). The classifier must apply an explicit
   `(thread_id + sender + timestamp) match + new provider_id + changed body_hash
   => edited` rule. Verified symmetric for inbound and outbound edits.

5. **Reactions are synthesized pseudo-messages.** An RCS reaction/tapback is a
   new inbound `content://mms` row whose body is a localized human string,
   `"<emoji> to \"<quoted target text>\""` (with zero-width spaces around the
   emoji), carrying its own timestamp. It does NOT modify or structurally
   reference the target row. Captured as an ordinary received message; optional
   processor-side pattern-match if reactions need distinct treatment.

6. **Delete is a hard delete with no tombstone** (SMS, MMS, RCS alike). The row -
   and for MMS/RCS its `part` and `addr` children - is removed entirely; `_id`s
   are monotonic and never reused. Detectable ONLY by snapshot-diff (a
   `provider_id` present in the prior snapshot, absent now), so a prior snapshot
   MUST be retained.

7. **RCS->SMS fallback lands as a plain `content://sms` row** (no `ct_cls`),
   indistinguishable from any other SMS and correctly so - it IS an SMS at that
   point.

---

## Provider URIs

| URI | Use | Notes |
|-----|-----|-------|
| `content://sms` | SMS rows | `type` 1=inbox/2=sent; `date` in **milliseconds** |
| `content://mms` | MMS **and RCS** message rows | `msg_box` 1=inbox/2=sent; `date`/`date_sent` in **SECONDS**; RCS marked by `ct_cls=135` |
| `content://mms/part` | parts (text + media + smil) | linked by `mid` = mms `_id`; media bytes via `openInputStream(content://mms/part/<_id>)` |
| `content://mms/<id>/addr` | per-message addresses | PduHeaders: `137`=From, `151`=To, `130`=Cc |
| `content://mms-sms/conversations?simple=true` | thread table | `recipient_ids`, `type`, `has_attachment` |
| `content://mms-sms/canonical-addresses` | address-id -> number | thread `recipient_ids` resolve here |
| `content://mms-sms/` (bare) | **NOT queryable** | throws `Unrecognized URI`; use the sub-paths above |

---

## `content://sms`

Columns (observed order):
```
_id, thread_id, address, person, date, date_sent, protocol, read, status, type,
reply_path_present, subject, body, service_center, locked, sub_id, error_code,
creator, seen
```
- `type`: **1 = received, 2 = sent** (direction).
- `date`: **epoch milliseconds**. `date_sent`: ms inbound, `0` outbound.
- `body`: text. `address`: peer (E.164 for 1:1).
- `subject`: usually null; outbound Messages rows carry a base64 `proto:` blob (metadata, not user content).
- `protocol`/`reply_path_present`/`service_center`: populated inbound, null outbound.
- `creator`: `com.google.android.apps.messaging`. No edit concept for SMS.

## `content://mms` (MMS and RCS)

Columns (observed order):
```
_id, thread_id, date, date_sent, msg_box, read, m_id, sub, sub_cs, ct_t, ct_l,
exp, m_cls, m_type, v, m_size, pri, rr, rpt_a, resp_st, st, tr_id, retr_st,
retr_txt, retr_txt_cs, read_status, ct_cls, resp_txt, d_tm, d_rpt, locked,
sub_id, seen, creator, text_only
```
- `msg_box`: **1 = received, 2 = sent** (direction).
- `date`/`date_sent`: **epoch SECONDS** (NOT ms - differs from SMS; normalize `* 1000`).
- `m_type`: `132` retrieve-conf (incoming), `128` send-req (outgoing).
- **`ct_cls`**: **`135` for RCS**, `NULL` for real MMS - the RCS discriminator.
- Real MMS: `ct_t=application/vnd.wap.multipart.related`, `m_id` = WAP id, `ct_l` = retrieval URL, real `m_size`, parts include a SMIL layout.
- RCS: `m_id=NULL`, `ct_l=NULL`, `m_size=0`; row-level `ct_t` just reflects the primary content (`text/plain` for a text RCS, `image/jpeg` for an image RCS); received RCS text has no SMIL part, sent RCS does.
- **`text_only` is unreliable for RCS** (observed `1` even with an image attachment) - determine media presence by inspecting parts, not this flag.
- `tr_id`: opaque base64 Google proto (do not depend on parsing).

## `content://mms/part`

```
_id, mid, seq, ct, name, chset, cd, fn, cid, cl, ctt_s, ctt_t, _data, text, sub_id
```
- `mid` -> mms `_id`.
- Text parts (`ct=text/plain`): content inline in **`text`**, `_data` null.
- Media parts (`ct=image/jpeg`, `image/gif`, ...): `text` null, **`_data` = provider-private file path** (`/data/user_de/0/com.android.providers.telephony/app_parts/PART_...`). Read bytes via `openInputStream(content://mms/part/<_id>)`, not the raw path.
- RCS attachments use this exact mechanism (an image RCS = a `text/plain` caption part named `body` + an `image/jpeg` part with `_data`).
- Real MMS additionally has an `application/smil` layout part; received RCS does not.

## `content://mms/<id>/addr`

`address, type, charset` (`106` = UTF-8). `type` is a WAP PduHeaders role:

| `type` | Role |
|--------|------|
| `137` | **From** (authoritative sender) |
| `151` | **To** |
| `130` | **Cc** (additional group members) |

- Inbound 1:1: From=peer, To=device. Inbound group: From=sender, To=device, Cc=other members.
- Outbound group: From=device, all other members=To.
- **The `137` (From) row is the authoritative per-message sender** - more reliable than thread-level address, and the real participant list for RCS groups (see below).

## Threads and addresses

`content://mms-sms/conversations?simple=true`:
```
_id, date, message_count, recipient_ids, snippet, snippet_cs, read, archived,
type, error, has_attachment, sub_id
```
`content://mms-sms/canonical-addresses`: `_id, address` (thread `recipient_ids` resolve here).

**MMS group vs RCS group differ sharply at the thread level:**
- **MMS group:** thread `type=1`, `recipient_ids` = space-separated ids of the real member numbers (e.g. `"2 3"`).
- **RCS group:** thread `type=0`, `recipient_ids` = a **single** id resolving to an **opaque `...@rcs.google.com` group token** - NOT the members. Thread-level membership is useless for RCS groups; **derive RCS group membership from the per-message `addr` rows (137/151/130).**

---

## Event-type classification (snapshot diff + rules)

Event type is derived, not a column. Maintain a prior snapshot of
`provider_id -> {key columns, body_hash, timestamp, thread, sender}`.

- **received / sent** - a `provider_id` not in the prior snapshot. received vs
  sent from the direction column (`sms.type` / `mms.msg_box`).
- **deleted** - a `provider_id` in the prior snapshot, absent now (Finding 6).
  Emit with the cached last-known payload.
- **edited** - a NEW `provider_id` whose `(thread_id + sender + timestamp)` match
  an already-captured row, with a different `body_hash` (Finding 4). Without this
  rule it would be mis-classified as a new received/sent message.
- **reactions** arrive as normal received rows (Finding 5); no special classifier
  path required.
- Coalesce the multiple broadcasts per logical message (Finding 1); dedupe by the
  idempotency key (below) so re-reads are safe.

---

## Normalized event payload (FROZEN - all event types)

Matches the wire-contract `events[]` element; one shape for SMS, MMS, and RCS:

```jsonc
{
  "provider_id": "<table>:<row_id>",      // "sms:7" | "mms:23"  (table-namespaced; never reused)
  "event_type": "received|sent|edited|deleted",
  "event_ts": 1779656022221,               // epoch MILLISECONDS (SMS date as-is; MMS/RCS date * 1000)
  "body_hash": "<lowercase hex sha256 of normalized_body>",
  "payload": {
    "source": "sms|mms|rcs",               // rcs when content://mms row has ct_cls=135
    "provider_id": "mms:23",
    "thread_id": "2",
    "thread_type": "one_to_one|group",
    "direction": "incoming|outgoing",      // sms.type 1->in/2->out ; mms.msg_box 1->in/2->out
    "timestamp_ms": 1779656022221,
    "from": "+15555550111",                // addr type 137 (mms/rcs); sms.address when type=1 else device
    "to": ["+15555550100"],                // addr type 151
    "cc": ["+15555550122"],                // addr type 130 (group members); omit/[] if none
    "participants": ["+15555550111","+15555550100","+15555550122"],
    "body": "message text",                // sms.body, or concatenated mms/rcs text/plain parts ("\n")
    "attachments": [
      { "part_id": "21", "part_uri": "content://mms/part/21",
        "content_type": "image/jpeg", "filename": "image000001.jpg", "size_bytes": 319048 }
    ],
    "is_rcs": true,                        // ct_cls == 135
    "creator": "com.google.android.apps.messaging"
  }
}
```

**Frozen normalization rules:**
- `provider_id` is **table-namespaced** (`sms:` / `mms:`) so `_id`-spaces never collide in the idempotency key.
- `event_ts` / `timestamp_ms` are **epoch milliseconds**: SMS `date` is already ms; **MMS/RCS `date` is seconds and MUST be `* 1000`.**
- `normalized_body` (input to `body_hash`) = the `body` string above as UTF-8, or `""` if none. `body_hash` = lowercase-hex SHA-256 of those bytes. (SHA-256 of `""` = `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.)
- For an **edited** event, `provider_id` is the NEW row's id and `event_ts` is the inherited (original) timestamp - which is exactly what lets the processor correlate it to the prior message.
- `direction` derives from the direction column, not address comparison.

## Broadcast contract (observed)

- Action: `GOOGLE_MESSAGES_ARCHIVAL_UPDATE` (bare; not the package-qualified form, on this build).
- Fires only on a recent Messages build (the Apr-2026 build; the Feb-2025 one never fired it).
- Started our service even from a TestDPC-managed (not full-EMM) device once the
  `messages_archival` managed config was set and the build supported it.
- `EXTRA_ARCHIVAL_URI`: Parcelable `Uri`, optional, unreliable (Finding 2).
- Wake-up semantics, multiple per message, none for read receipts (Finding 1).

## Edge-case findings (capture reliability)

Captured live after the core matrix; all favorable.

- **Capture survives locked screen AND forced deep Doze.** With the moto screen
  off (Dozing) and again under forced `deviceidle` deep idle on battery, inbound
  RCS + SMS still (a) delivered to the provider and (b) **auto-fired our
  foreground service** - dumps were written from idle with **no
  `ForegroundServiceStartNotAllowedException`**. The archival broadcast from
  Google Messages (default SMS app, `WRITE_SMS`-holder) carries enough exemption
  to start our `shortService` from idle. The silent-miss-while-asleep failure
  mode did not occur. (Caveat: forced `deviceidle` may differ slightly from
  multi-hour natural Doze + restrictive App Standby buckets; re-confirm on Pixel.)
- **All attachment types use the same `content://mms/part` + `_data` mechanism**
  (`ct_cls=135` throughout): voice note `audio/mp4`, video `video/mp4`, image
  `image/png|jpeg`, contact `text/x-vcard` - each a part with `ct`=MIME and a
  `_data` file path (read via `openInputStream(content://mms/part/<id>)`).
  **Location share is the exception:** the row's `ct_t =
  application/vnd.gsma.rcspushlocation+xml`, but the content is a **`text/plain`
  part holding a Google Maps URL** (no blob, `_data` null). Normalizer rule:
  parts with non-null `_data` => binary attachments; `text/plain` parts => body
  text (including location URLs).
- **Status updates do not look like edits.** Marking a message read flips
  `read` 0->1 (and delivery updates touch status columns) but the **`body_hash`
  is byte-identical**, no new row is created, and several broadcasts fire as
  noise. Because the idempotency/edit logic keys on `body_hash` + `provider_id`,
  status churn is naturally a no-op (same `provider_id`, same `body_hash`) and is
  never mis-classified as an edit.
- **A long (>160-char) SMS is a single reassembled `content://sms` row** with the
  full body - no provider-level segmentation; no reassembly needed by the client.

## Still pending (not blockers)

- **Per-OEM variance:** this capture is Motorola moto g. The plan's primary target
  is Pixel (and secondary Samsung); re-confirm the `ct_cls=135` marker, the
  seconds-vs-ms date split, and broadcast firing there.
- **Genuine EMM enrollment:** broadcast firing was validated under TestDPC +
  managed config on a current Messages build; confirm under a real managed
  Google Play / EMM enrollment for production.
- **Minimum Messages version** that implements the broadcast (somewhere between
  the Feb-2025 and Apr-2026 builds) - document a floor for admins.
