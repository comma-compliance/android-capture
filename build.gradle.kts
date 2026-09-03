// Top-level build file. Plugin versions are declared here and applied per-module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.sentry) apply false
    alias(libs.plugins.detekt)
}

// detekt: Kotlin static analysis. Run as a CI gate over the app sources. The
// codebase is clean against config/detekt/detekt.yml, so there is no baseline -
// any finding fails the build. Config is the project default + the tuned rules in
// detekt.yml.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(files("app/src/main/java", "app/src/test/java", "app/src/androidTest/java"))
    parallel = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(false)
        txt.required.set(false)
    }
}
