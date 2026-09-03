# Comma Compliance Messages Archiver (android-capture)

An Android compliance-archival client for **fully-managed (device-owner)**
Android Enterprise devices. On each message event the default messaging app
starts a permission-gated foreground service in this app; the service reads the
new message rows from the device's Telephony provider and background workers
encrypt them - message bodies and attachment binaries alike - and ship them to a
compliance ingest server.

This app is **compatible with Google Messages(TM)**. Google Messages is a
trademark of Google LLC. This project is not affiliated with, sponsored by, or
endorsed by Google. The word "Google" is used here only nominatively to describe
interoperability; it is not part of this app's name, package, or branding.

- Application ID / package: `com.commacompliance.archiver`
- Launcher label: "Comma Archiver" (full product name: "Comma Compliance Messages Archiver")

## Use & Policy Statement

### Why This Exists

This project provides an open-source tool for archiving business SMS, MMS, and
RCS messages on **fully-managed corporate Android devices**, for organizations
that are obligated - by regulation, legal hold, or internal policy - to retain
their employees' work communications. It exists so that compliance archival can
happen **transparently and verifiably**, with the code open to inspection,
rather than through an opaque black box.

### User Consent & Control

Archiving is **transparent, never covert**:

- While archival is active the device shows a **persistent, non-dismissible
  foreground-service notification** stating that messages are being archived for
  compliance and naming the organization.
- A **plain-language rationale screen is shown first** - before any SMS access
  is requested - explaining exactly what is captured (text, participants,
  timestamps, attachments), that the organization administers the archive, that
  messages are encrypted on-device before sending, and that there are no ads and
  no data sale.
- The device only archives after it is connected to **its own organization's**
  compliance backend, either through administrator-delivered managed
  configuration or through the employee's own authenticated organizational
  sign-in. A device cannot be bound to a membership that is not the
  authenticating user's, and self-serve sign-in works only if the organization's
  administrator has enabled the capability.
- Data is sent **only** to the organization-controlled ingest endpoint; nothing
  is transmitted anywhere else.

### Transparency & Auditability

This project is fully open-source under the Apache License 2.0, so anyone can
read the code, review what it captures, and verify the security claims here.
Independent audits and feedback are welcome - especially around capture scope,
encryption, and access control. The on-device behavior is observable: the
persistent notification, the rationale screen, and the absence of any hidden
capture path are all in the open.

### Disclaimers

- This tool is for **transparent, authorized compliance archival on
  corporate-managed devices** - **not** for surveillance, covert monitoring, or
  capturing the communications of people who have not been informed.
- It does not intercept, weaken, or bypass any messaging app's transport
  encryption. It reads only what the device's default messaging app has already
  decrypted and stored locally (see "What it does NOT capture").
- Organizations are responsible for deploying it lawfully - including any notice,
  consent, and jurisdictional obligations that apply to them. Users assume full
  responsibility for how they configure and deploy this tool.

### Our Commitment

We believe organizations should be able to meet legitimate compliance
obligations without resorting to hidden surveillance. Our goal is to provide a
transparent, auditable, ethical tool: archival that is disclosed to the people
it affects, encrypted in transit and at rest on-device, and open for anyone to
inspect.

## What it captures

On a fully-managed device where Google Messages is the default SMS/RCS handler
and the administrator has pointed it at this app (see
[docs/emm-managed-config.md](docs/emm-managed-config.md)), the client captures:

- **RCS** - 1:1 and group chats, attachments, edits, reactions/tapbacks, and
  RCS-to-SMS fallback.
- **SMS** - including long multipart messages (reassembled to a single row).
- **MMS** - including group MMS and media attachments.
- **Attachment binaries** - the actual image/video/voice/vCard bytes referenced
  by a message, not just metadata. Any attachment that has retrievable bytes is
  archived regardless of size (large files stream in resumable chunks); it is
  never skipped for being large. The only entries not uploaded are structurally
  unuploadable parts that carry no content reference at all.

It captures these by reading the rows that the default messaging app persists to
the standard Telephony content providers (`content://sms`, `content://mms`) -
i.e. **post-decryption**, after the messaging app has already delivered and
stored each message. The archival broadcast itself is treated as a **wake-up
signal only**; it does not carry the message payload. On each broadcast the
client scans the providers for the delta versus a locally cached snapshot, so
every message is captured even though multiple broadcasts (send, delivery,
read-receipt) can fire per logical message. Messages are captured even when the
device is locked or in deep Doze.

To stay complete under real-world conditions the client also:

- **Queues durably offline.** Capture is local and runs on every broadcast
  regardless of connectivity; events and pending-attachment records persist in a
  Room database and are uploaded out-of-band by WorkManager with backoff.
  Nothing un-uploaded is ever silently dropped; a long-offline backlog drains on
  reconnect, and a reboot resumes draining.
- **Reconciles all diffs.** A periodic full reconciliation scan diffs the entire
  provider against the stored snapshot, so adds/edits/deletes that a missed
  broadcast or a Doze window skipped are still captured (not just surviving
  messages). The scan shares one serialized snapshot read/diff/write with
  per-broadcast capture, so an unchanged provider emits zero events.
- **Heartbeats.** An idle, connected device periodically reports it is alive
  (and refreshes its backfill policy) so it stays visible server-side even with
  nothing to upload.

## What it does NOT capture

- **No third-party messengers.** WhatsApp, Signal, Telegram, and other apps are
  out of scope - they do not flow through the Telephony provider and are not part
  of this contract. (Those platforms have their own dedicated clients.)
- **No work-profile / BYOD / COPE devices.** Fully-managed (device-owner)
  devices are the only supported deployment; the messaging-archival contract is
  not available on work profiles or personally-owned/COPE configurations. (The
  Google Messages archival broadcast that drives capture only fires under managed
  configuration; the app does not separately hard-gate on device-owner status, so
  it relies on this managed-only distribution.)
- **No own-EMM.** This app does not provision or manage devices itself. An EMM
  (Intune, Workspace ONE, or Google's managed configuration) distributes and
  configures it. Building an Android Management API EMM is out of scope.
- **No pre-decryption interception.** It reads what the messaging app has already
  decrypted and stored; it is not a wiretap and does not touch the transport.

## Onboarding

The administrator enables "Android RCS+SMS capture" as a team capability on the
compliance backend. There are then two ways a device binds itself to the
organization. Both end with the device holding a long-lived bearer
`ArcArchiveToken` and the server's rotatable destination public key.

### Self-serve in-app sign-in (primary)

An employee installs the app and taps **Connect to your organization**. The app:

1. Discovers the OAuth client from the backend
   (`GET /api/v1/android_archiver/config`) - the client id is never hardcoded.
2. Launches **Authorization-Code + PKCE** sign-in through a **Chrome Custom
   Tab** (no embedded WebView) against the organization's existing login, per
   RFC 8252. The redirect comes back on a private-use scheme
   (`com.commacompliance.archiver:/oauth2redirect`).
3. Runs the device-registration handshake against the user's **own membership**:
   it sends its Curve25519 box public key and an Ed25519 signing public key,
   receives a one-time challenge, signs the challenge, and completes
   registration. The OAuth token is used **only for this setup**; the device
   runs long-term on the `ArcArchiveToken` it receives.

The device is bound to the **authenticating user's own membership** and only if
the team capability is enabled. A user cannot register a device against a
membership that is not theirs.

### Managed (EMM one-time-token) path (retained)

For zero-interaction provisioning, the EMM can deliver a one-time
`enrollment_token` (plus `backend_url`) via managed configuration. On first run
the app exchanges the token + its device public-key fingerprint +
`enrollment_specific_id` for the same `ArcArchiveToken`/destination-key bundle.
A consumed token cannot be replayed by another device. The app reacts proactively
when managed configuration arrives, so the device enrolls without waiting for the
first message.

If managed config supplies an `enrollment_token`, the app uses the EMM path;
otherwise it presents the self-serve sign-in. `backend_url` from managed config
is always honored.

### Redirect handling and device attestation

Both behaviors below are **server-advertised and degrade gracefully** - an
older or unconfigured backend keeps working unchanged, and the app never
hard-blocks sign-in if they are unavailable.

- **Verified App Link OAuth redirect.** When the backend advertises an HTTPS App
  Link redirect URI (and its host matches the app's compiled App Link host), the
  OAuth redirect returns over a verified `https://` Android App Link instead of
  the private-use scheme. If on-device App Link verification has not completed
  (or the host does not match), the app falls back to the
  `com.commacompliance.archiver:/oauth2redirect` custom scheme; the backend's
  redirect page forwards to that scheme so the flow always completes. The
  token-exchange redirect URI always matches the one used in the auth request.
- **Play Integrity attestation.** When the backend advertises a Cloud project
  number, the app requests a Play Integrity token and includes it with
  registration/enroll so the server can attest the device and app. If Play
  services are absent or a token cannot be obtained, the app proceeds without one
  (the server decides whether to accept, monitor, or reject per its configured
  policy); attestation never blocks sign-in client-side.

## Privacy and security model

This is a compliance product that handles people's private messages; it is built
to be transparent and to keep content confidential end to end. No per-tenant or
per-customer keys are baked into the distributed APK.

- **Transparency notification.** While archival is active the device displays a
  **persistent foreground-service notification** stating that messages are being
  archived for compliance, and for which organization. It cannot be dismissed
  while the service is working. Archiving is never hidden from the user.
- **Permissions rationale first.** A plain-language screen explains what is
  captured (text, participants, timestamps, attachments), that the organization
  administers the archive, that messages are encrypted on-device before sending,
  and that there are no ads and no data sale - shown **before** SMS access is
  requested.
- **Per-device keypair.** On first run the app generates its own Curve25519 box
  keypair and a separate Ed25519 signing keypair locally. Private keys live in
  `EncryptedSharedPreferences` (AES-GCM master key in the Android Keystore) and
  never leave the device. Box keys are not used as signing keys.
- **OAuth/PKCE org sign-in.** Self-serve uses Authorization-Code + PKCE through a
  Custom Tab against the org's existing login - no embedded WebView, no password
  handling in-app.
- **Encrypted upload.** Each batch is sealed in a `nacl-box-v1` envelope
  (`{version, kid, sourcePublicKey, nonce, ciphertext}`) to the server's
  rotatable destination public key and POSTed with the bearer `ArcArchiveToken`.
- **Encrypted attachment content.** Attachment bytes are uploaded in the same
  `nacl-box` envelope with **domain separation** (a framed `type:"attachment"`
  object carrying the plaintext SHA-256 `content_hash`); large files stream in
  resumable, individually-enveloped chunks. The server recomputes the hash after
  decrypt and rejects a mismatch. An attachment with retrievable bytes is never
  skipped for being large.
- **Rotatable destination key.** The server can rotate its destination key; the
  `kid` that encrypted each payload is recorded so it can be decrypted later. A
  revoked token forces the client to re-authenticate / re-read managed config.
- **Encrypted at rest on-device.** The local upload queue (the Room database
  where captured message rows and pending-attachment records wait to be shipped)
  is encrypted at rest with SQLCipher. The database passphrase is generated
  on-device and stored in `EncryptedSharedPreferences` (Keystore-wrapped); an
  existing plaintext queue is migrated in place on upgrade. To bound how long
  uploaded cleartext lingers, a row is pruned once it has been uploaded, is older
  than the retention window, **and** its underlying message is no longer present
  in the device's Telephony provider; rows for still-present messages are
  retained so later edits keep correlating correctly.
- **No PII in logs.** Logging records control flow and exception class names
  only - never message bodies, tokens, or attachment contents.

The heavy Telephony read happens inside the short-lived foreground service;
network upload (events and attachments) runs out-of-band via WorkManager with
network constraints and exponential backoff, so it is safe to retry and never
blocks the capture window.

## Backfill policy

A single `backfill_days` integer (delivered via the registration/enroll response
and/or the `backfill_days` managed-config key) controls how much existing
history is emitted on first run:

- `0` = forward-only (default): the first run silently establishes the baseline
  snapshot and emits nothing.
- `N` = capture messages from the last `N` days on first run (`9999` ≈ full
  history).

`backfill_days` controls **first-run emission only, not snapshot coverage**: the
baseline snapshot always represents the entire current provider, so the later
reconciliation scan never mistakes pre-existing rows for new ones.

## Build

Prerequisites:

- **JDK 17 installed system-wide** (one-time, requires sudo):
  `sudo apt-get install -y openjdk-17-jdk`. `javac -version` must report `17.x`.
- **Android SDK** via the userspace, sudo-free installer:
  `./scripts/setup-toolchain.sh`
  This downloads the Android command-line tools into `~/Android/sdk`, installs
  `platform-tools`, `platforms;android-35`, and `build-tools;35.0.0`, and accepts
  the SDK licenses. Export the `ANDROID_HOME` / `ANDROID_SDK_ROOT` / `PATH` lines
  it prints (or add them to your shell profile) before building. Gradle itself is
  not installed; the Gradle wrapper (`./gradlew`) downloads the pinned version on
  first use.

Build the debug APK:

```bash
./gradlew :app:assembleDebug
```

Run the unit tests (JVM + Robolectric, including the NaCl-box envelope
round-trip vectors that prove wire compatibility with the server's decryptor):

```bash
./gradlew :app:testDebugUnitTest
```

The build is reproducible from a clean checkout: no network state, no committed
SDK, and no committed signing material is required for `assembleDebug`. The
`ci-android-client.yml` GitHub Actions workflow builds and tests the same way on
a clean runner.

### Signed release bundle

A signed Android App Bundle (`.aab`) is produced by the
`release-android-client.yml` GitHub Actions workflow on a `v*` tag push (or
manual dispatch). Signing material is provided **only** through GitHub Actions
secrets - never committed (`.gitignore` excludes `*.keystore`, `*.jks`, `*.aab`).
The workflow expects these four secrets:

| Secret | Meaning |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | base64 of the release/upload keystore (`.jks`) |
| `ANDROID_KEYSTORE_PASSWORD` | store password for that keystore |
| `ANDROID_KEY_ALIAS` | alias of the signing key inside the keystore |
| `ANDROID_KEY_PASSWORD` | password for that key alias |

The workflow decodes the keystore to a runner-local temp file, passes the path
and passwords to Gradle **through the environment** (never as `-P` command-line
args, so secrets never appear in process arguments or `--stacktrace` output),
removes the temp file afterward, and uploads the resulting `app-release.aab` as a
build artifact. The `release` `signingConfig` in `app/build.gradle.kts` reads
exclusively from those environment variables / project properties; when they are
absent - as in a normal local `assembleDebug` - no signing material is required
and only an unsigned/debug build is produced. No keystore, password, or alias is
hardcoded anywhere in the tree.

## Distribution

Distributed first through **private / managed Google Play** under the
restricted-permission "enterprise archive" exception (corporate-managed devices,
corporate login required, archival/compliance as the core function). See
[docs/play-permissions-declaration.md](docs/play-permissions-declaration.md) for
the Play Console restricted-permission declaration narrative. A public Play
listing is a deferred follow-up.

## Open-source readiness

This client is open-able without embarrassment: no committed secrets or keys,
dependency licenses reviewed for compatibility, a reproducible build, and a
clean codebase with no developer-grade scaffolding in the shipped flow. See
[docs/open-source-readiness.md](docs/open-source-readiness.md) for the checklist
and the dependency-license inventory in
[docs/third-party-notices.md](docs/third-party-notices.md).

## Repository note

This is a standalone repository. It has no coupling to any other system beyond
the documented device-to-server ingest contract; the backend it talks to is
configured at runtime (via managed config or in-app sign-in) and is never
hardcoded. The build needs only a JDK and the Android SDK - see **Build** above.

## License

Licensed under the **Apache License, Version 2.0**. See [LICENSE](LICENSE) for
the full text and [NOTICE](NOTICE) for attribution. Third-party dependency
licenses are inventoried in
[docs/third-party-notices.md](docs/third-party-notices.md); note that the Play
Integrity client library ships under Google's Play Integrity API Terms of
Service (proprietary, distributable in the app binary), not under Apache-2.0.

## Contributing

Contributions are welcome - please read the
[Contributing Guidelines](CONTRIBUTING.md). In short: fork, work on a feature
branch, **sign your commits** (the CI signature/DCO check enforces this), and
open a pull request. At least two reviewer approvals are required; mention
**@JeremiahChurch** and **@sasha** for roadmap-affecting changes.

## Security & Bug Bounty

We take security seriously. If you discover a security vulnerability, please:

1. **Do not** open a public issue.
2. **Email** us at security@commacompliance.com.
3. **Include** detailed steps to reproduce - but **never** real message
   contents, addresses, or tokens.
4. **Wait** for our response before public disclosure.

**Bug Bounty Program**: report vulnerabilities responsibly and earn rewards. The
minimum bounty is **$25** for valid submissions. See [SECURITY.md](SECURITY.md)
for the full policy and supported-versions note.

## Support

- **Enterprise Support**: contact@commacompliance.com
