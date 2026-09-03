# Play Console restricted-permission declaration

This document holds the Google Play Console declaration narrative for the
restricted SMS permissions used by the Comma Compliance Messages Archiver
(`com.commacompliance.archiver`). It is compatible with Google Messages(TM);
Google Messages is a trademark of Google LLC.

## Permissions declared

- **`READ_SMS`** - used to read the SMS/MMS/RCS rows the default messaging app
  persists to the Telephony provider, which is the archival core function.
- **`WRITE_SMS`** - used **only as a service permission gate**. The app's
  archival foreground service is declared with
  `android:permission="android.permission.WRITE_SMS"` so that **only the default
  SMS handler** (which holds `WRITE_SMS`) can start it. The app does not write
  SMS messages; the permission is the access-control gate that prevents any
  non-default app from triggering archival.

Both are Play "restricted SMS/Call Log" permissions and require a declaration.

## Declaration narrative (enterprise-archive exception)

> This app is an **enterprise communications-archival client**, deployed only to
> **corporate-managed (fully-managed, device-owner) Android Enterprise devices**
> through an organization's EMM. It is not a consumer app: it is installed and
> governed by an organization, and it only operates after the organization has
> enabled message archiving and the device has been bound to that organization.
>
> Its **core, advertised function** is regulatory/compliance archival of an
> employee's business SMS, MMS, and RCS messages on a corporate-owned device. To
> perform that function it must read the messages the device's default messaging
> app persists to the Telephony provider, which requires `READ_SMS`. The
> `WRITE_SMS` permission is used solely as a service-access gate so that only the
> default SMS handler can start the archival service; the app does not send or
> modify messages.
>
> **Use is gated on corporate management and an authenticated organizational
> connection.** The app only operates on a fully-managed device-owner device, and
> only after one of two organization-controlled connection paths completes:
>
> - **Managed (zero-interaction):** the organization's administrator configures
>   the app via managed configuration, delivering the archival backend URL and a
>   one-time enrollment credential; the app enrolls against the organization's
>   compliance backend.
> - **User-authenticated organizational connection:** the employee signs in to
>   the organization through the organization's own login using OAuth
>   Authorization-Code + PKCE in a Chrome Custom Tab (no embedded WebView, no
>   in-app password handling), which binds the device to that employee's own
>   organizational membership. Archiving is only available if the organization's
>   administrator has explicitly enabled the message-archiving capability for the
>   team; an individual cannot enable it unilaterally, and cannot bind a device to
>   a membership that is not their own.
>
> In both cases the device archives only after it is connected to the
> organization's compliance backend, and employees are informed: while archival
> is active the device displays a **persistent foreground-service notification**
> stating that messages are being archived for compliance and naming the
> organization. A plain-language rationale screen explains what is captured (text,
> participants, timestamps, attachments) and that the organization administers the
> archive **before** any SMS access is requested.
>
> The permissions are **central to the app's sole purpose** - without `READ_SMS`
> there is no archive - and they qualify under Google Play's enterprise-only
> exception for archival/compliance use on corporate-managed devices. There is no
> less-permissioned API that provides the same message-archival capability for a
> managed device.

When the Play Console permission-declaration form asks for the SMS/Call Log
exception category, select the **enterprise** use case (corporate-managed
devices) rather than the default-SMS-handler consumer category - this app is not
a consumer default-SMS app; it is a managed-device archival client that uses the
default handler's broadcast contract. The user-authenticated connection path does
**not** make this a consumer app: it is still restricted to fully-managed
device-owner devices and to organizations whose administrator has enabled the
capability; in-app sign-in is the employee authenticating to their employer's
compliance system, not a consumer installing a messaging app.

## Distribution posture

- **Private / managed Google Play first.** The app ships through managed Google
  Play to enrolled enterprise customers only. Managed/private distribution is the
  primary route and reinforces the enterprise-only nature of the SMS-permission
  use.
- **Public Play listing is a later step.** A public production listing (and the
  full public-facing privacy review that comes with it) is a deferred follow-up,
  pursued only after the managed/private build is proven in the field.

## Supporting artifacts to attach

- A short demo video / screen recording showing the archival flow on a managed
  device - including both the user-authenticated connection (Custom Tab org
  sign-in) and the persistent archival notification - as Play requires for
  restricted-permission reviews.
- A link to the privacy policy describing what is archived, that it occurs only
  on corporate-managed devices under administrator control, and how the data is
  encrypted in transit (per-device enrollment + NaCl-box envelope to a rotatable
  destination key, with attachment content encrypted the same way).
- This declaration narrative and the EMM admin runbook
  ([emm-managed-config.md](emm-managed-config.md)) as evidence the app is
  configured and gated by enterprise management.
