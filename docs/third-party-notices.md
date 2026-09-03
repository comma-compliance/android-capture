# Third-party notices

The Comma Compliance Messages Archiver depends on the libraries listed below.
All are distributable in the app binary alongside this project's Apache-2.0
`LICENSE`. Most are open-source under permissive or weak-copyleft licenses; the
one exception is the Play Integrity client library, which ships under Google's
proprietary Play Integrity API Terms of Service (binary distribution permitted,
but it is **not** open source). Versions are pinned in
`gradle/libs.versions.toml`.

This file is an inventory for license review and attribution; it is not itself a
license grant. An automated attribution bundle (e.g. via the OSS Licenses Gradle
plugin) is a possible future addition; this inventory covers attribution today.

## Runtime (shipped in the APK/AAB)

| Library | Coordinates | Version | License | Notes |
|---|---|---|---|---|
| AndroidX Core KTX | `androidx.core:core-ktx` | 1.13.1 | Apache-2.0 | |
| AndroidX AppCompat | `androidx.appcompat:appcompat` | 1.7.0 | Apache-2.0 | |
| Material Components | `com.google.android.material:material` | 1.12.0 | Apache-2.0 | |
| AndroidX Lifecycle Runtime KTX | `androidx.lifecycle:lifecycle-runtime-ktx` | 2.8.7 | Apache-2.0 | |
| Kotlin stdlib + coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-android` (+ Kotlin 2.0.21) | 1.8.1 | Apache-2.0 | |
| AndroidX WorkManager | `androidx.work:work-runtime-ktx` | 2.9.1 | Apache-2.0 | |
| AndroidX Room (runtime + ktx) | `androidx.room:room-runtime`, `androidx.room:room-ktx` | 2.6.1 | Apache-2.0 | |
| AndroidX SQLite | `androidx.sqlite:sqlite` | 2.4.0 | Apache-2.0 | Pinned so SQLCipher's `SupportOpenHelperFactory` binds to a fixed `SupportSQLite*` surface. |
| SQLCipher for Android | `net.zetetic:sqlcipher-android` | 4.16.0 | BSD-3-Clause | At-rest encryption of the on-device queue. Copyright Zetetic LLC. Bundles a native `libsqlcipher` per ABI; BSD-3-Clause attribution must accompany binary distribution. |
| AndroidX Security Crypto | `androidx.security:security-crypto` | 1.1.0-alpha06 | Apache-2.0 | Alpha; tracks the maintained line for `EncryptedSharedPreferences`. |
| AppAuth-Android | `net.openid:appauth` | 0.11.1 | Apache-2.0 | OAuth Authorization-Code + PKCE in a Custom Tab; verified App Link redirect override. |
| Play Integrity API library | `com.google.android.play:integrity` | 1.6.0 | **Play Integrity API Terms of Service (proprietary Google terms - NOT open source)** | Google's `StandardIntegrityManager` client. Distribution **in the app binary is permitted** under the Play Integrity API ToS, but the library is **not** Apache-2.0 or otherwise open source. Used only to attest device/app integrity at registration/enroll; the client degrades to a null token when Play services are absent, so it is never load-bearing for sign-in. |
| LazySodium for Android | `com.goterl:lazysodium-android` | 5.2.0 | MPL-2.0 | Weak (file-level) copyleft; used as an unmodified library, so it imposes no obligation on this app's own source. |
| libsodium (bundled native) | shipped inside `lazysodium-android` | (per artifact) | ISC | The NaCl/Curve25519 + XSalsa20-Poly1305 native implementation. |
| Java Native Access (JNA) | `net.java.dev.jna:jna` (`@aar`) | 5.17.0 | Apache-2.0 OR LGPL-2.1 | Dual-licensed; we rely on the Apache-2.0 option, so no LGPL obligation applies. FFI bridge for libsodium. |

## Build / annotation-processing only (not shipped)

| Library | Coordinates | Version | License | Notes |
|---|---|---|---|---|
| Android Gradle Plugin | `com.android.application` | 8.7.3 | Apache-2.0 | Build tooling. |
| Kotlin Gradle Plugin | `org.jetbrains.kotlin.android` | 2.0.21 | Apache-2.0 | Build tooling. |
| KSP | `com.google.devtools.ksp` | 2.0.21-1.0.28 | Apache-2.0 | Room code generation. |
| AndroidX Room compiler | `androidx.room:room-compiler` | 2.6.1 | Apache-2.0 | KSP processor; not in the APK. |

## Test only (not shipped)

| Library | Coordinates | Version | License |
|---|---|---|---|
| JUnit 4 | `junit:junit` | 4.13.2 | EPL-1.0 |
| Robolectric | `org.robolectric:robolectric` | 4.13 | MIT |
| AndroidX Test (core, ext-junit) | `androidx.test:core`, `androidx.test.ext:junit` | 1.6.1 / 1.2.1 | Apache-2.0 |
| Mockito | `org.mockito:mockito-core` | 5.12.0 | MIT |
| LazySodium for Java (host) | `com.goterl:lazysodium-java` | 5.1.4 | MPL-2.0 |
| JNA (host test) | `net.java.dev.jna:jna` | 5.12.1 | Apache-2.0 OR LGPL-2.1 |

## License-compatibility summary

- This project is licensed **Apache-2.0**. The runtime open-source set is
  **Apache-2.0 / BSD-3-Clause / ISC / MIT plus MPL-2.0** (LazySodium), all of
  which are compatible with shipping the app as a binary under an Apache-2.0
  project license.
- **SQLCipher for Android** is **BSD-3-Clause** (Zetetic LLC): permissive, with
  an attribution requirement. Its copyright/license notice must accompany the
  binary distribution, which this notices file satisfies.
- **MPL-2.0** (LazySodium) is weak, file-level copyleft: modifications to the
  MPL-licensed files themselves must be shared, but linking it from
  otherwise-licensed code does not relicense this app's own source. We use it
  unmodified.
- **JNA** is dual-licensed Apache-2.0 OR LGPL-2.1; selecting the Apache-2.0
  option (as we do) avoids any LGPL relinking obligation.
- **Play Integrity API library is the one non-open-source dependency.** It is
  governed by Google's **Play Integrity API Terms of Service** (proprietary).
  Distribution within the app binary is permitted under those terms, but the
  library is not redistributable as open source and is not under Apache-2.0. It
  imposes no copyleft on this project's own Apache-2.0 source. It is only invoked
  at registration/enroll and the client functions (degrading to a null
  attestation token) when it is unavailable.
- No GPL/AGPL or other strong-copyleft dependency is present.
- No dependency carries a "no-commercial" clause.

If a strong-copyleft (GPL/AGPL) or no-redistribution dependency is ever
introduced, it must be reviewed before it ships.
