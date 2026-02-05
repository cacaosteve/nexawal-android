// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // App module
    alias(libs.plugins.android.application) apply false

    // Walletcore module (Android library)
    alias(libs.plugins.android.library) apply false

    // Kotlin Android plugin for library module
    alias(libs.plugins.kotlin.android) apply false

    // Compose stays app-only for now
    alias(libs.plugins.kotlin.compose) apply false
}
