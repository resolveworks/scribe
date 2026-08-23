// Top-level build file. Plugin versions come from gradle/libs.versions.toml.
//
// Note on Kotlin: with AGP 9 the app module does NOT apply
// org.jetbrains.kotlin.android — AGP ships built-in Kotlin support
// (see developer.android.com/build/migrate-to-built-in-kotlin).
// The only Kotlin plugin declared here is the Compose compiler
// subplugin, versioned with the Kotlin release it ships with.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
