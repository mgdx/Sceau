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
}

configurations.configureEach {
    // BouncyCastle n'est fourni que par les artefacts jdk18on : les anciennes variantes
    // jdk15on / jdk15to18 dupliqueraient les classes.
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    exclude(group = "org.bouncycastle", module = "bcpkix-jdk15on")
    exclude(group = "org.bouncycastle", module = "bcutil-jdk15on")
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
}

dependencies {
    implementation(libs.jmrtd)
    implementation(libs.scuba.smartcards)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

ktlint {
    version.set(libs.versions.ktlint)
}
