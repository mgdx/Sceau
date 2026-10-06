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
        versionName = "0.9"
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
    testOptions {
        // Ressources Android (chaînes des 45 langues) visibles des tests Robolectric (D28).
        unitTests.isIncludeAndroidResources = true
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

// Robolectric exige Java 21 ou plus pour simuler Android 37 (compileSdk) : les tests JVM de
// l'app tournent sur le JDK 25 déjà utilisé par le démon Gradle et la CI ; le code reste compilé
// pour Java 17. --add-exports : Robolectric manipule les descripteurs de fichiers internes du
// JDK ; --enable-native-access : son rendu natif, sans avertissement (D28).
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED")
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
    // Contrôle des débordements de texte dans toutes les langues (D28) : Robolectric et
    // Compose UI test, en tests JVM uniquement. ui-test-manifest déclare l'activité hôte des
    // tests Compose : debugImplementation, jamais dans l'APK release.
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Espresso 3.5 tiré par ui-test-junit4 appelle InputManager.getInstance(), absent
    // d'Android 37 : version récente imposée (tests uniquement).
    testImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Mode démo (CNIe simulée) : APK de debug uniquement, jamais en release.
    debugImplementation(project(":testchip"))
}

ktlint {
    version.set(libs.versions.ktlint)
}
