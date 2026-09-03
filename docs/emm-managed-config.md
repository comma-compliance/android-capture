# EMM admin runbook - managed configuration

This is the administrator guide for deploying the Comma Compliance Messages
Archiver (`com.commacompliance.archiver`) to **fully-managed (device-owner)**
Android Enterprise devices. It is compatible with Google Messages(TM); Google
Messages is a trademark of Google LLC.

## How a device connects: two paths

A device must (1) be told which app Google Messages should wake, and (2) connect
to your organization's compliance backend. There are two ways to do step 2:

- **Managed (zero-interaction) enrollment** - your EMM delivers a `backend_url`
  and a one-time `enrollment_token` to this app via managed configuration; the
  app enrolls on first run with no user interaction. Use this for zero-touch
  fleets. This runbook covers it.
- **User-authenticated connection (self-serve)** - you only need to deliver the
  Google Messages `messages_archival` key (step 1) and the `backend_url`; the
  employee then opens the app and signs in to your organization in-app (OAuth
  Authorization-Code + PKCE in a Chrome Custom Tab) to bind the device to their
  own membership. **No `enrollment_token` is required for this path.** Self-serve
  requires that an administrator has enabled the "Android RCS+SMS capture"
  capability for the team on the compliance backend; until then, sign-in is
  refused with a plain-language message.

Both paths require Google Messages to be wired to this app (step 1 below) and the
device to be fully-managed. The two paths are interchangeable per device; if an
`enrollment_token` is present in managed config the app uses the managed path,
otherwise it presents in-app sign-in.

## Overview - the managed-config settings

For the **managed enrollment** path, archival uses managed-config settings on two
different apps:

1. **On Google Messages** (`com.google.android.apps.messaging`): set the key
   `messages_archival` to this app's package name,
   `com.commacompliance.archiver`. This tells Google Messages which app to wake
   on each message event. **Required for both connection paths.**
2. **On this app** (`com.commacompliance.archiver`): set `backend_url`,
   optionally `enrollment_token`, and optionally `backfill_days` (the app's own
   app restrictions). This tells the archiver where to send data, optionally
   gives it a one-time bootstrap credential, and optionally sets how much history
   to capture on first run.

For managed enrollment, set both `backend_url` and `enrollment_token`. For the
self-serve path, set `backend_url` (and the Google Messages key) but omit
`enrollment_token`. None of these are user-editable; all are delivered by your
EMM.

### Config 1 - `messages_archival` on Google Messages

| Key | Type | Value |
|---|---|---|
| `messages_archival` | string | `com.commacompliance.archiver` |

> **Important:** `messages_archival` is an **undocumented** managed-config key on
> Google Messages. Schema-driven managed-configuration UIs (which only show keys
> the app publishes in its restrictions schema) may **not** list it. You must
> set it through your EMM's **raw key/value bundle** editor for Google Messages.
> See the per-EMM notes below.

### Config 2 - `backend_url`, `enrollment_token`, `backfill_days` on this app

These are published in this app's app-restrictions schema, so they appear
directly in any managed-configuration UI.

| Key | Type | Value |
|---|---|---|
| `backend_url` | string | Base URL of your archival ingest server (e.g. `https://archive.example.com`). Required for both paths. |
| `enrollment_token` | string | A one-time enrollment token (see below). **Managed path only** - omit it for self-serve. |
| `backfill_days` | integer | How many days of existing messages to capture on first run. `0` = forward-only (default). `N` = the last `N` days (`9999` ≈ full history). Optional. |

> **`backfill_days`** controls only what is **emitted** on first run, not what is
> snapshotted. The app always records a full baseline snapshot of the provider so
> later reconciliation never re-emits pre-existing messages as new. If the key is
> absent, the device uses the value returned by enrollment / device registration
> from the backend.

## Generating an `enrollment_token`

The `enrollment_token` is a **one-time bootstrap credential minted by the
server**. It is not a long-lived API key:

- The server issues it scoped to the customer's account/membership.
- On the device's first run the app POSTs the token together with its freshly
  generated device public key, the SHA-256 fingerprint of that key, and the
  device's `enrollment_specific_id`
  (`DevicePolicyManager.getEnrollmentSpecificId()`).
- The server **consumes the token and binds it** to that public-key fingerprint
  plus the `enrollment_specific_id`. A consumed token cannot be reused; if a
  different device presents the same token, or the same token is presented with
  a different fingerprint, enrollment is rejected.
- In exchange the server returns a device-scoped bearer `archive_token`, the
  rotatable destination public key, and its key id. All subsequent encrypted
  uploads use these.

Practically: mint one `enrollment_token` per device (or per enrollment batch
per your provisioning policy), set it as the managed config on that device, and
treat it as single-use. Re-provisioning a device means minting a fresh token.

## Device prerequisites

For the broadcast to fire and capture to work, the device must be:

1. **Fully-managed (device-owner)** - enrolled via your EMM's fully-managed
   (not work-profile, not COPE) flow, or via TestDPC as device owner for
   testing. Work profile / BYOD / COPE are not supported.
2. **Google Messages set as the default SMS/RCS app**, and RCS provisioned. On
   OEMs where another app (e.g. Samsung Messages) is default, switch the default
   handler to Google Messages.
3. **Both managed configs applied** (the two above).
4. **Archiver protected from user removal/disable.** Use the device policy
   `setUserControlDisabledPackages` to include both
   `com.commacompliance.archiver` and `com.google.android.apps.messaging`, so a
   user cannot force-stop, disable, or clear the archiver or the messaging app
   and silently break capture.

### Minimum Google Messages build

The archival broadcast only fires on a sufficiently recent Google Messages
build. This is verified by observation, not by published documentation:

- A **Feb 2025** Google Messages build (`messages.android_20250225_00_RC03`) did
  **not** fire the archival broadcast at all.
- An **Apr 2026** build (`messages.android_20260428_00_RC02`, delivered via Play
  update) **does** fire it - and is also the build on which RCS provisioned.

**Action for admins:** ensure devices run a **current** Google Messages build
(allow/push the Play update for `com.google.android.apps.messaging`) before
relying on archival. An out-of-date Messages app will silently capture nothing.

## Per-EMM configuration

The mechanics are the same everywhere - you are pushing an Android managed
configuration (`RestrictionsManager`) key/value bundle to each app. The only
wrinkle is that `messages_archival` is undocumented, so it must go through a raw
bundle editor rather than a schema-driven form.

### Microsoft Intune

- Create an **App configuration policy** for **Managed devices**, targeting
  Google Messages. Use the **"Enter JSON data"** (configuration designer's raw
  bundle) option to add `messages_archival = com.commacompliance.archiver`,
  since the key won't appear in the form-based picker.
- Create a second App configuration policy targeting this app, setting
  `backend_url` and `enrollment_token` (these appear in the form-based picker).
- Add both packages to **Uninstall / user-control restrictions** (device
  configuration) so users cannot disable them.

### VMware Workspace ONE UEM

- Under the app's **Assignment -> Application Configuration**, supply the
  managed-config bundle. For Google Messages, add the **custom key**
  `messages_archival` with value `com.commacompliance.archiver` via the raw
  key/value editor.
- Configure this app's `backend_url` + `enrollment_token` the same way.
- Use the device profile's app-control settings to prevent users from disabling
  either package.

### Google managed configuration (Android Management API / managed Google Play)

- In your provisioning policy, set `applications[].managedConfiguration` for
  Google Messages to include `messages_archival = com.commacompliance.archiver`
  (raw key/value - the key is not in a published schema).
- Set this app's `managedConfiguration` to `backend_url` + `enrollment_token`.
- Set the policy's `setUserControlDisabledPackages` equivalent
  (`applications[].defaultPermissionPolicy` / install type `FORCE_INSTALLED`
  plus user-control restrictions) for both packages.

## Verifying a deployment

1. Confirm the device is device-owner and Google Messages is the default
   handler with RCS provisioned and a current Messages build.
2. Confirm the managed configs are applied:
   - **Managed path:** the archiver shows the persistent archival notification
     once it enrolls automatically (both `backend_url` and `enrollment_token`
     set).
   - **Self-serve path:** the employee opens the app, taps **Connect to your
     organization**, signs in, and sees the connected Status screen; the
     persistent archival notification then appears (only `backend_url` set,
     `messages_archival` wired on Google Messages, and the team capability
     enabled on the backend).
3. Send a test SMS and a test RCS message; confirm they arrive at the ingest
   server. If nothing arrives, the most common causes are a stale Google
   Messages build (broadcast never fires) or a missing/typo'd `messages_archival`
   key. For self-serve, also confirm the team's "Android RCS+SMS capture"
   capability is enabled on the backend - the app reports a plain-language
   "not enabled for your organization yet" message if it is not.
