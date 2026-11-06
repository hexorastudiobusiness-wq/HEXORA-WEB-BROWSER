import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing is injected by CI through environment variables that
// point at a temporary keystore decoded from GitHub Secrets. The keystore
// and its passwords are never part of this repository (see signing/README.md).
val releaseStorePath = System.getenv("HEXORA_RELEASE_STORE_FILE")
val hasReleaseSigning = !releaseStorePath.isNullOrBlank()

android {
    // The source tree still lives under com.example.browser; only the install
    // identity is a real one. Play rejects com.example.*, and a placeholder id
    // has no business on a 1.0.0 build.
    namespace = "com.example.browser"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hexora.browser"
        // GeckoView ships its own native libraries and requires a modern
        // baseline; 26 (Android 8.0) keeps manifest merging safe across
        // current GeckoView releases.
        minSdk = 26
        targetSdk = 37
        versionCode = 42
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // arm64-v8a only, on the owner's instruction: the target device is a
    // modern 64-bit phone, and skipping the other ABIs keeps a debug build
    // down to one artifact (~120 MB instead of three).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseStorePath!!)
                // One password serves as both store and key password; the
                // PKCS#12 keystore is generated that way (see signing/README.md).
                storePassword = System.getenv("HEXORA_RELEASE_KEYSTORE_PASSWORD")
                keyAlias = "hexora-release"
                keyPassword = System.getenv("HEXORA_RELEASE_KEYSTORE_PASSWORD")
                storeType = System.getenv("HEXORA_RELEASE_STORE_TYPE") ?: "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        // The owner needs to be able to tell which build is installed: the app
        // never exposed its own version, so "this feels old" was unanswerable.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Compress native libraries inside the APK (they are extracted
            // on install). Without this, debug APKs store the huge Gecko
            // engine .so files uncompressed.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose (versions pinned by the BOM)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Lifecycle / Navigation
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Browser engine — the ONLY place GeckoView is used is behind
    // the BrowserEngine abstraction (see browser/GeckoBrowserEngine.kt).
    implementation(libs.mozilla.geckoview)

    testImplementation(libs.junit)
}

// AGP 9 built-in Kotlin compiler options (no kotlin-android plugin anymore)
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
