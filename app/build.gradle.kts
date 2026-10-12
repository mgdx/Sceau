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
        versionName = "1.0.0"
    }

    // Native JPEG 2000 decoder: OpenJPEG compiled from the sources of the submodule
    // app/src/main/cpp/openjpeg (decision D3). Compilation flags in CMakeLists.txt.
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    // One APK per architecture, plus a universal APK (SPEC §2, decision D2).
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    // No dependency metadata block (encrypted for Google Play) in the APK signing block or
    // in the AAB: unreadable by F-Droid, which rejects it (D25).
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
            // bcprov, bcpkix and bcutil each ship the same license text (MIT).
            pickFirsts += "META-INF/LICENSE.md"
            // BouncyCastle's multi-release OSGi manifests, of no use on Android.
            excludes += "META-INF/versions/*/OSGI-INF/MANIFEST.MF"
        }
    }
    testOptions {
        // Android resources (strings in 45 languages) visible to Robolectric tests (D28).
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        // "A newer version exists" checks: they query the network and make CI fail on every
        // upstream release, unrelated to the code. Version upgrades are made deliberately,
        // not imposed by lint.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors = true
    }
}

// Robolectric requires Java 21 or later to simulate Android 37 (compileSdk): the app's JVM tests
// run on JDK 25, already used by the Gradle daemon and CI; the code is still compiled for
// Java 17. --add-exports: Robolectric manipulates the JDK's internal file descriptors;
// --enable-native-access: its native rendering, without warnings (D28).
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED")
}

// F-Droid screenshots (D39): StoreScreenshots only runs if SCEAU_SCREENSHOTS=1, never through
// ./gradlew check; it then writes into fastlane/metadata/android.
val storeScreenshots = providers.environmentVariable("SCEAU_SCREENSHOTS").orNull == "1"
val fastlaneMetadata = rootProject.file("fastlane/metadata/android").absolutePath
tasks.withType<Test>().configureEach {
    if (storeScreenshots) {
        systemProperty("sceau.screenshots.dir", fastlaneMetadata)
    } else {
        filter.excludeTestsMatching("*.StoreScreenshots")
    }
}

configurations.configureEach {
    // See core/build.gradle.kts: BouncyCastle in jdk18on only.
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
    // Text overflow check in every language (D28): Robolectric and Compose UI test, in JVM
    // tests only. ui-test-manifest declares the host activity of the Compose tests:
    // debugImplementation, never in the release APK.
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Espresso 3.5, pulled in by ui-test-junit4, calls InputManager.getInstance(), absent
    // from Android 37: a recent version is forced (tests only).
    testImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Demo mode (simulated CNIe): debug APK only, never in release.
    debugImplementation(project(":testchip"))
}

ktlint {
    version.set(libs.versions.ktlint)
}
