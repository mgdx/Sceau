plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "io.github.mgdx.sceau"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.mgdx.sceau"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    // Décodeur JPEG 2000 natif : OpenJPEG compilé depuis les sources du sous-module
    // app/src/main/cpp/openjpeg (décision D3). Drapeaux de compilation dans CMakeLists.txt.
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    // Un APK par architecture, plus un APK universel (SPEC §2, décision D2).
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    // Pas de bloc de métadonnées de dépendances (chiffré pour Google Play) dans le bloc de
    // signature de l'APK ou dans l'AAB : illisible par F-Droid, qui le refuse (D25).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            // bcprov, bcpkix et bcutil embarquent chacun le même texte de licence (MIT).
            pickFirsts += "META-INF/LICENSE.md"
            // Manifestes OSGi multi-version de BouncyCastle, sans usage sur Android.
            excludes += "META-INF/versions/*/OSGI-INF/MANIFEST.MF"
        }
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        // Contrôles « une version plus récente existe » : ils interrogent le réseau et font
        // échouer la CI à chaque publication en amont, sans lien avec le code. Les montées de
        // version sont faites délibérément, pas imposées par lint.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors = true
    }
}

configurations.configureEach {
    // Voir core/build.gradle.kts : BouncyCastle uniquement en jdk18on.
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    exclude(group = "org.bouncycastle", module = "bcpkix-jdk15on")
    exclude(group = "org.bouncycastle", module = "bcutil-jdk15on")
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.scuba.sc.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Mode démo (CNIe simulée) : APK de debug uniquement, jamais en release.
    debugImplementation(project(":testchip"))
}

ktlint {
    version.set(libs.versions.ktlint)
}
