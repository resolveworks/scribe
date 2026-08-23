plugins {
    alias(libs.plugins.android.application)
    // AGP 9 provides built-in Kotlin support; no kotlin-android plugin needed.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "works.resolve.amanuensis"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "works.resolve.amanuensis"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    // On-device speech recognition (MicTranscriber, used by the IME)
    implementation(libs.moonshine.voice)

    // Unit tests
    testImplementation(libs.junit)
}
