plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.sentry)
}

android {
    namespace = "com.commacompliance.archiver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.commacompliance.archiver"
        // minSdk 34: the foreground-service shortService type and its
        // Service.onTimeout() callback were introduced in Android 14 (API 34),
        // and the archival service depends on both.
        minSdk = 34
        // targetSdk 36 (Android 16) is required by Google Play: from 2026-08-31 no
        // new app OR app update may be submitted below it. Staying at 35 would not
        // pull the already-published build, but it would block shipping ANY update.
        //
        // Targeting 36 opts this app into Android 16's behaviour changes; the one
        // that actually bites here is edge-to-edge enforcement, which no longer has
        // an opt-out flag - see the window-inset handling in the onboarding/status
        // activities, without which those screens draw under the system bars.
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // AppAuth's RedirectUriReceiverActivity captures the OAuth redirect on this
        // private-use scheme (RFC 8252). Must match AuthCoordinator.REDIRECT_URI and
        // the redirect URI seeded on the server's Doorkeeper client.
        manifestPlaceholders["appAuthRedirectScheme"] = "com.commacompliance.archiver"

        // Single source of truth for the verified App Link host. The host the app
        // accepts as an autoVerify HTTPS OAuth redirect target. Overridable per fork
        // via -PappLinkHost=... ; the default is not load-bearing because the server
        // ADVERTISES the redirect URI it wants (app_link_redirect_uri) and the client
        // only honours it when its host matches this compiled value.
        //
        // Derived to BOTH a manifest placeholder (so the intent-filter's host can be
        // stamped at build time) AND a BuildConfig field (so the runtime redirect-
        // selection code can compare the advertised host against the compiled one - a
        // manifest placeholder alone is not readable at runtime).
        val appLinkHost = (findProperty("appLinkHost") as String?) ?: "app.commacompliance.com"
        manifestPlaceholders["appLinkHost"] = appLinkHost
        buildConfigField("String", "APP_LINK_HOST", "\"$appLinkHost\"")

        // Sentry DSN, embedded at build time (a mobile app has no server runtime to
        // inject it). Accepts -PsentryDsn=... or the SENTRY_DSN env, empty by default.
        // The DSN is a publishable client value, not a secret. It is stamped into the
        // manifest meta-data the Sentry SDK auto-init reads; an EMPTY DSN makes auto-init
        // a graceful no-op, so local/dev builds and any build produced before the
        // android-client Sentry project exists ship clean. The reported release is the
        // SDK default applicationId@versionName+versionCode. The env is read through the
        // provider API (lazy, configuration-cache friendly) - same as the auth token.
        val sentryDsn = (findProperty("sentryDsn") as String?)
            ?: providers.environmentVariable("SENTRY_DSN").orNull
            ?: ""
        manifestPlaceholders["sentryDsn"] = sentryDsn
    }

    buildFeatures {
        // The redirect-selection logic reads BuildConfig.APP_LINK_HOST at runtime.
        buildConfig = true
    }

    // Release signing reads its material from Gradle project properties (-P...)
    // or the matching environment variables - never from anything checked in.
    // CI supplies them from GitHub secrets (see release-android-client.yml); the
    // keystore is a runner-local temp file. When the properties are absent (e.g.
    // a local `assembleDebug`), the signingConfig is left unconfigured and only
    // debug builds are produced. No keystore, password, or alias is hardcoded.
    val releaseKeystorePath =
        (findProperty("releaseKeystorePath") as String?) ?: System.getenv("ANDROID_KEYSTORE_PATH")
    val releaseKeystorePassword =
        (findProperty("releaseKeystorePassword") as String?) ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val releaseKeyAlias =
        (findProperty("releaseKeyAlias") as String?) ?: System.getenv("ANDROID_KEY_ALIAS")
    val releaseKeyPassword =
        (findProperty("releaseKeyPassword") as String?) ?: System.getenv("ANDROID_KEY_PASSWORD")
    val hasReleaseSigning = !releaseKeystorePath.isNullOrBlank() &&
        !releaseKeystorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Android Lint. Run as a CI gate: pre-existing findings are recorded in the
    // committed baseline so they don't fail the build, but any NEW finding does.
    // checkDependencies extends analysis into library modules; html/xml reports
    // let CI surface and archive results.
    lint {
        checkDependencies = true
        abortOnError = true
        baseline = file("lint-baseline.xml")
        htmlReport = true
        xmlReport = true
        // Upstream "a newer version is available" advisories drift with every release
        // and are not actionable in a lint pass - dependency bumps are a deliberate,
        // separately-tested change. SimilarGradleDependency fires on the intentional
        // jna (aar, on-device) vs jna-test (desktop libsodium round-trip) split.
        disable += setOf(
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "SimilarGradleDependency",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            // JNA ships duplicate license metadata across its jar/aar variants;
            // drop the duplicates so the APK packages cleanly.
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/licenses/**",
            )
        }
    }

    sourceSets {
        // The frozen golden fixtures live under contract/ at the module's parent;
        // expose them to unit tests so the classifier/normalizer are verified
        // against the exact observed payloads rather than copies that could drift.
        getByName("test").resources.srcDir("${rootDir}/contract")
    }
}

// Sentry Android Gradle plugin. org/project + auth token are only consumed by the
// upload tasks, which run when there is something to upload. With
// isMinifyEnabled = false there is no R8/ProGuard mapping, includeSourceContext is
// off by default, and native-symbol upload is off, so NO task contacts the Sentry
// API at build time - the build stays green even before the android-client Sentry
// project (or the token) exists; mapping upload activates automatically if minify
// is later enabled. The auth token is read from the environment only (the org
// GitHub secret SENTRY_AUTH_TOKEN), never committed and never a sentry.properties.
sentry {
    org.set("camphound")
    projectName.set("android-client")
    // Provider-backed env read: lazy + configuration-cache friendly. Unset when the
    // env var is absent, which simply leaves the (already no-op) upload unauthenticated.
    authToken.set(providers.environmentVariable("SENTRY_AUTH_TOKEN"))
    // Keep the build hermetic: do not phone Sentry's plugin-telemetry endpoint.
    telemetry.set(false)
    // Crash/error reporting only for v1 - skip build-time bytecode tracing
    // instrumentation. Performance tracing can be turned on later.
    tracingInstrumentation {
        enabled.set(false)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Crash + error reporting. Initialized via manifest auto-init from the embedded
    // DSN meta-data (empty DSN = disabled). Pinned to the version the Sentry Gradle
    // plugin bundles so the plugin's auto-installation does not pull a different one.
    implementation(libs.sentry.android)

    // In-app organization sign-in: Authorization-Code + PKCE through a Chrome
    // Custom Tab (no embedded WebView), per RFC 8252.
    implementation(libs.appauth)

    // Device/app attestation at registration + enroll via the Play Integrity
    // standard request API. Under Google's Play Integrity API ToS (proprietary),
    // not Apache-2.0 - see docs/third-party-notices.md. The client degrades to a
    // null token when Play services are unavailable, so it never hard-blocks sign-in.
    implementation(libs.play.integrity)

    // Declared for the upload pipeline added in a later change.
    implementation(libs.androidx.work.runtime.ktx)

    // Durable on-device event cache.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // The on-device queue is encrypted at rest with SQLCipher. Its
    // SupportOpenHelperFactory plugs into Room's openHelperFactory; the AAR ships a
    // libsqlcipher .so per ABI (~2-3 MB each). The AAB delivers a single ABI per
    // device, so the on-device cost is one .so, not the full set. androidx.sqlite is
    // pinned explicitly because SupportOpenHelperFactory binds to its SupportSQLite*
    // surface rather than inheriting it transitively.
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    // At-rest encryption for the device secret key (EncryptedSharedPreferences,
    // backed by an AES-GCM master key held in the Android Keystore).
    implementation(libs.androidx.security.crypto)

    // NaCl box for the upload envelope. The -android artifact ships native
    // libsodium for device ABIs; JNA is its FFI bridge. On Android the @aar JNA
    // variant carries the native JNI dispatch, so exclude lazysodium's transitive
    // plain-jar JNA to avoid a duplicate-class clash with the @aar variant.
    implementation(libs.lazysodium.android) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation(libs.jna) { artifact { type = "aar" } }

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.mockito.core)
    // Desktop libsodium so the envelope round-trip vector test exercises the real
    // NaCl box on the host JVM (the -android natives cannot load off-device).
    testImplementation(libs.lazysodium.java)
    testImplementation(libs.jna.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
