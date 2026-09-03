# Open-source readiness checklist

This client is built so the repository could be opened to the public without
embarrassment - even though, for now, it ships closed-source inside the
compliance monorepo. This checklist records the current status and any remaining
gaps before a standalone open-source release.

Status legend: **OK** = done / no action needed; **DEFERRED** = intentionally
out of scope for now with a known follow-up.

## No committed secrets or keys

| Item | Status | Notes |
|---|---|---|
| No signing keystore in the tree | **OK** | `.gitignore` excludes `*.keystore`, `*.jks`; none are tracked. Signing material comes only from GitHub Actions secrets at release time. |
| No built artifacts committed | **OK** | `.gitignore` excludes `*.apk`, `*.aab`, `/build`, `**/build/`. |
| No API keys / tokens / passwords in source | **OK** | Grep of `app/src/main` finds no hardcoded secrets, backend URLs, or OAuth client ids. The OAuth `client_id` is fetched from the backend's discovery endpoint at runtime; `backend_url` and the one-time `enrollment_token` arrive via managed config or in-app sign-in. |
| `local.properties` not tracked | **OK** | Git-ignored; the SDK path is environment-specific. |
| No per-tenant / per-customer keys baked into the APK | **OK** | Each device generates its own Curve25519 + Ed25519 keypairs on first run; private keys live in `EncryptedSharedPreferences` (Keystore-wrapped) and never leave the device. |
| No real message data in the repo | **OK** | Contract fixtures under `contract/fixtures/` are synthetic. No raw device dumps are tracked. |

## Dependency licenses

| Item | Status | Notes |
|---|---|---|
| All runtime deps reviewed | **OK** | See [third-party-notices.md](third-party-notices.md). Open-source runtime set is Apache-2.0 / BSD-3-Clause (SQLCipher) / ISC / MIT plus MPL-2.0 (LazySodium); all compatible with this project's Apache-2.0 license. |
| No strong-copyleft (GPL/AGPL) deps | **OK** | None present. |
| Weak-copyleft handled | **OK** | LazySodium (MPL-2.0) is used unmodified; JNA's dual Apache-2.0/LGPL-2.1 license is taken under the Apache-2.0 option. |
| Proprietary-licensed deps identified | **OK** | The Play Integrity client library ships under Google's Play Integrity API Terms of Service (proprietary; binary distribution permitted, not open source). Recorded in the notices file; it imposes no obligation on this project's own source and is non-load-bearing (the client degrades when it is unavailable). |
| Attribution covered | **OK** | [third-party-notices.md](third-party-notices.md) inventories every dependency with its license and attribution (incl. SQLCipher BSD-3-Clause and the Play Integrity ToS). An automated attribution file (e.g. via the OSS Licenses Gradle plugin) is a possible future addition. |

## Reproducible / clean build

| Item | Status | Notes |
|---|---|---|
| Builds from a clean checkout | **OK** | `./gradlew :app:assembleDebug` needs only JDK 17 + the SDK installed by `scripts/setup-toolchain.sh`. No committed SDK, no network state, no signing material required for debug. |
| Pinned toolchain | **OK** | AGP, Kotlin, KSP, and all libraries are version-pinned in `gradle/libs.versions.toml`; the Gradle wrapper pins the Gradle version. |
| CI builds + tests on a clean runner | **OK** | `ci-android-client.yml` runs `assembleDebug` + `testDebugUnitTest` on a fresh `ubuntu-latest` runner on every push/PR touching `android-client/`. |
| Release build signing is hermetic | **OK** | `release-android-client.yml` decodes the keystore from a secret to a runner-local temp file, passes secrets via the environment (never `-P` args, so they never appear in process args or stacktraces), and deletes the temp file afterward. |

## Code quality / no scaffolding

| Item | Status | Notes |
|---|---|---|
| No developer-grade / debug-only screens in the shipped flow | **OK** | The only activities are `PermissionRationaleActivity`, `OnboardingActivity`, and `StatusActivity` - all user-facing. No dev menu, no sample-data screen. |
| No dead / stub product code | **OK** | The HTTP layer is intentionally an interface with a thin production implementation and a test fake; that is a seam, not scaffolding. |
| No PII or secrets logged | **OK** | Logging records control flow and exception class names (`e.javaClass.simpleName`) only - never message bodies, tokens, attachment contents, or recipients. Where an identifier is logged it is a local Room row id. |
| Honest, transparent runtime behavior | **OK** | A persistent foreground-service notification states archiving is active and names the organization; a permissions/privacy rationale precedes any SMS access request. |
| Device-owner restriction is deployment-enforced, not a runtime lock | **OK (documented)** | Capture is driven by the Google Messages archival broadcast, which only fires under managed configuration; the app does not separately hard-gate on `isDeviceOwnerApp`. Docs state this rather than implying a runtime guard. A runtime device-owner assertion is a possible future hardening. |

## Trademark / branding hygiene

| Item | Status | Notes |
|---|---|---|
| "Google" not in the app name or package | **OK** | Launcher label "Comma Archiver" (product name "Comma Compliance Messages Archiver"); package `com.commacompliance.archiver`. |
| Nominative trademark phrasing used | **OK** | Docs say "compatible with Google Messages(TM)" with the non-affiliation disclaimer. |
| Launcher icon is brand-neutral | **OK** | Brand-green adaptive icon (Comma Compliance brand `#33a852`, with a themed-monochrome layer); deliberately not any messaging app's palette or glyph. |

## Standalone-repo scaffolding

| Item | Status | Notes |
|---|---|---|
| Project `LICENSE` | **OK** | Apache-2.0; see [LICENSE](../LICENSE) and [NOTICE](../NOTICE). |
| Contributor guide | **OK** | [CONTRIBUTING.md](../CONTRIBUTING.md): fork -> signed-commit feature branch -> PR, two-reviewer approval, rebase-clean history. |
| Security policy | **OK** | [SECURITY.md](../SECURITY.md): private disclosure to security@commacompliance.com, coordinated disclosure, $25 minimum bounty, latest-release support. |
| GitHub templates | **OK** | `.github/ISSUE_TEMPLATE/` (bug report, feature request) and `PULL_REQUEST_TEMPLATE.md`, all reminding contributors never to paste message contents or tokens. |
| Dependency attribution | **OK** | [third-party-notices.md](third-party-notices.md) covers every dependency incl. SQLCipher (BSD-3-Clause) and the Play Integrity ToS. |

## Deferred (known, intentional)

| Item | Status | Notes |
|---|---|---|
| Automated license-attribution file in the build | **DEFERRED** | The notices file covers attribution today; an in-build attribution bundle (e.g. via the OSS Licenses Gradle plugin) is an optional future addition. |
| Public Play Store listing | **DEFERRED** | Distributed first through private/managed Google Play; a public production listing (and its full privacy review) is a later step. |
| Runtime device-owner hard gate | **DEFERRED** | Capture is driven by the managed Google Messages broadcast and managed-only distribution; a runtime `isDeviceOwnerApp` assertion is a possible future hardening (documented, not implied). |

## Verdict

This repository is ready to be opened to the public: there are no committed
secrets or keys; dependency licenses are reviewed and compatible (the one
proprietary dependency, the Play Integrity client, is permitted in binary
distribution and is non-load-bearing); the build is reproducible and
CI-verified; the code is free of scaffolding and PII logging; branding is
trademark-compliant; and the standalone scaffolding (Apache-2.0 `LICENSE`,
`NOTICE`, `CONTRIBUTING`, `SECURITY`, and GitHub templates) is in place. The
remaining items are deliberately deferred enhancements, not blockers.
