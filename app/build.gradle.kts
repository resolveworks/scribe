plugins {
    alias(libs.plugins.android.application)
    // AGP 9 provides built-in Kotlin support; no kotlin-android plugin needed.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "works.resolve.scribe"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "works.resolve.scribe"
        minSdk = 36
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"

        // Single 64-bit ARM ABI: minSdk 36 means every install target is arm64.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            // Provided by the release workflow from repository secrets.
            storeFile = System.getenv("SCRIBE_KEYSTORE")?.let(::file)
            storePassword = System.getenv("SCRIBE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("SCRIBE_KEY_ALIAS")
            keyPassword = System.getenv("SCRIBE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // AndroidX base
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.savedstate)

    // Compose (versions managed by the BOM)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // On-device speech recognition (base Transcriber, driven by DictationEngine)
    implementation(libs.moonshine.voice)

    // Unit tests
    testImplementation(libs.junit)
}
