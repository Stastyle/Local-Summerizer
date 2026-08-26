import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Baked into BuildConfig so the app can tell whether the release on GitHub is
// newer than the build running on the phone.
val gitSha: String = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "HEAD")
    }.standardOutput.asText.get().trim()
}.getOrDefault("")

val releaseKeystore: java.io.File? = System.getenv("ANDROID_KEYSTORE_PATH")
    ?.takeIf { it.isNotBlank() }
    ?.let(::File)
    ?.takeIf { it.exists() }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.stastyle.localsummarizer"
    compileSdk = 35
    ndkVersion = "28.0.13004108"

    defaultConfig {
        applicationId = "com.stastyle.localsummarizer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField(
            "String",
            "UPDATE_BASE_URL",
            "\"https://github.com/Stastyle/Local-Summerizer/releases/download/apk-latest\"",
        )
        buildConfigField(
            "String",
            "RELEASE_PAGE_URL",
            "\"https://github.com/Stastyle/Local-Summerizer/releases/tag/apk-latest\"",
        )

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // c++_shared is required once more than one .so is produced,
                // and Release keeps -O3 in the debug variant too.
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
                if (System.getenv("CCACHE_DIR") != null || System.getenv("CI") != null) {
                    arguments += listOf(
                        "-DCMAKE_C_COMPILER_LAUNCHER=ccache",
                        "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache",
                    )
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The release key is reconstructed by CI from a repository secret and is
    // deliberately absent from the repository: a committed key lets anyone
    // sign an APK that Android accepts as an update of this app, inheriting
    // its private data. Without the key the release APK is left unsigned
    // rather than signed with something forgeable.
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "localsummarizer"
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // The debug APK is signed with a throwaway debug key while the
            // release APK uses the committed one; without a distinct id the
            // second install fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // ggml picks its CPU backend at runtime by scanning the app's
            // native library directory for libggml-cpu-*.so. Modern packaging
            // leaves those files inside the APK and never populates that
            // directory, so nothing registers and whisper aborts on the null
            // CPU device. Extracting them on install is what the scan expects.
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
