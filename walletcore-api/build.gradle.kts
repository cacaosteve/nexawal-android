@file:Suppress("UnstableApiUsage")

plugins {
    // Kotlin-only module. The Kotlin plugin is already on the buildscript classpath (via the Android toolchain),
    // so we must apply it without specifying a version to avoid the "unknown version" conflict.
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    // Keep JVM target aligned with the Android project toolchain.
    jvmToolchain(11)
}

dependencies {
    // Serialization runtime used by walletcore-api (Transfer parsing, fee preview/send/sweep JSON).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // The native runtime (jniLibs + JNI shim) is packaged by the Android library module.
    // This Kotlin-only module provides the public API surface and can be depended on by app code.
    //
    // NOTE: A Kotlin/JVM module cannot depend on an Android library (AAR) at compile time.
    // The expectation is:
    //  - app includes :walletcore (native libs)
    //  - app (or other JVM modules) includes :walletcore-api (Kotlin API)
    //
    // If you want a single dependency for consumers, we’ll need to resolve the Kotlin plugin
    // conflict in :walletcore and move this API surface into the Android library module.
}
