// Puce ICAO 9303 simulée et PKI factice : sert aux tests de `:core` et, plus tard, au mode
// démo de l'APK de debug. Ne doit jamais être une dépendance de production (voir README.md).
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
    // Voir app/build.gradle.kts : contrôles de fraîcheur des versions, dépendants du réseau.
    disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    // L'aléa de la PKI factice est volontairement déterministe (SHA1PRNG à graine fixe, voir
    // TestCrypto.seededRandom) : tests reproductibles. Ce module ne fabrique que des clés de test.
    disable += "TrulyRandom"
}

configurations.configureEach {
    // Mêmes exclusions que `:core` : BouncyCastle n'est fourni que par les artefacts jdk18on.
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
