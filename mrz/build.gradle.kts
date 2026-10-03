// Lecture optique de la MRZ (D32) : Kotlin pur, aucun import android.*, aucun code natif.
// Reçoit le plan de luminance d'une image de la caméra et ne rend que le format, le numéro
// de document et les deux dates. Testé sur JVM.
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

dependencies {
    testImplementation(libs.junit)
}

ktlint {
    version.set(libs.versions.ktlint)
}
