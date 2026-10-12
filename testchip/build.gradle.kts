// Simulated ICAO 9303 chip and fake PKI: used by the `:core` tests and, later, by the demo mode
// of the debug APK. Must never be a production dependency (see README.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors = true
    }
}

lint {
    warningsAsErrors = true
    abortOnError = true
    // See app/build.gradle.kts: version freshness checks, which depend on the network.
    disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    // The fake PKI's randomness is deliberately deterministic (fixed-seed SHA1PRNG, see
    // TestCrypto.seededRandom): reproducible tests. This module only creates test keys.
    disable += "TrulyRandom"
}

configurations.configureEach {
    // Same exclusions as `:core`: BouncyCastle is only provided by the jdk18on artifacts.
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    exclude(group = "org.bouncycastle", module = "bcpkix-jdk15on")
    exclude(group = "org.bouncycastle", module = "bcutil-jdk15on")
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.jmrtd)
    implementation(libs.scuba.smartcards)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)

    testImplementation(libs.junit)
}

ktlint {
    version.set(libs.versions.ktlint)
}
