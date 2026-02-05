@file:Suppress("UnstableApiUsage")

import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

val cmakeListsPath = file("src/main/cpp/CMakeLists.txt").absolutePath

/**
 * Copy libc++_shared.so from the configured Android NDK into this module's jniLibs so runtime dlopen can resolve it.
 *
 * Why:
 * - `libmonerowalletcore.so` depends on `libc++_shared.so`
 * - Android does not provide it globally; it must be packaged into the AAR and ultimately the APK/AAB under lib/<abi>/
 *
 * How we find the NDK:
 * - Prefer ANDROID_NDK_HOME / ANDROID_NDK_ROOT env vars
 * - Otherwise read `ndk.dir` from the project's local.properties
 */
val ensureLibcxxShared by tasks.registering {
    group = "walletcore"
    description = "Copies libc++_shared.so from the Android NDK into src/main/jniLibs for supported ABIs."

    doLast {
        val localPropsFile = rootProject.file("local.properties")
        val localProps = Properties().apply {
            if (localPropsFile.exists()) {
                localPropsFile.inputStream().use { load(it) }
            }
        }

        val ndkHome = System.getenv("ANDROID_NDK_HOME")
            ?: System.getenv("ANDROID_NDK_ROOT")
            ?: localProps.getProperty("ndk.dir")

        require(!ndkHome.isNullOrBlank()) {
            "Unable to locate Android NDK. Set ANDROID_NDK_HOME/ANDROID_NDK_ROOT or configure ndk.dir in local.properties."
        }

        val ndkDir = file(ndkHome)
        require(ndkDir.exists()) { "Android NDK dir does not exist: $ndkDir" }

        // Map Android ABI -> NDK sysroot triple
        val abiToTriple = mapOf(
            "arm64-v8a" to "aarch64-linux-android",
            "x86_64" to "x86_64-linux-android",
        )

        val prebuiltRoot = ndkDir.resolve("toolchains/llvm/prebuilt")
        require(prebuiltRoot.exists()) { "NDK LLVM prebuilt dir not found under: ${prebuiltRoot.absolutePath}" }

        // Find host prebuilt folder (darwin-x86_64, darwin-arm64, linux-x86_64, etc.)
        val hostPrebuilt = prebuiltRoot.listFiles()?.firstOrNull { it.isDirectory }
        require(hostPrebuilt != null) {
            "Could not find NDK host prebuilt directory under: ${prebuiltRoot.absolutePath}"
        }

        val sysrootUsrLib = hostPrebuilt.resolve("sysroot/usr/lib")
        require(sysrootUsrLib.exists()) { "NDK sysroot usr lib directory not found: ${sysrootUsrLib.absolutePath}" }

        abiToTriple.forEach { (abi, triple) ->
            val src = sysrootUsrLib.resolve("$triple/libc++_shared.so")
            require(src.exists()) { "Missing libc++_shared.so for ABI=$abi at: ${src.absolutePath}" }

            val dstDir = file("src/main/jniLibs/$abi")
            if (!dstDir.exists()) dstDir.mkdirs()

            val dst = dstDir.resolve("libc++_shared.so")
            src.copyTo(dst, overwrite = true)
            println("Copied libc++_shared.so for $abi -> ${dst.absolutePath}")
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(ensureLibcxxShared)
}

android {
    namespace = "com.nexatrode.nexawal.walletcore"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        // Ensure we only build/package ABIs we provide in jniLibs.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }

        // Native-only module: keep consumer rules file (safe even if empty/minimal).
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file(cmakeListsPath)
        }
    }

    // This module ships native libs via src/main/jniLibs. We also build a JNI shim via CMake.
    packaging {
        jniLibs {
            pickFirsts += setOf(
                "**/libmonerowalletcore.so",
                "**/libwalletcore_jni.so",
                "**/libc++_shared.so",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Not a Compose module; it provides native libs + JNI shim only.
    buildFeatures {
        buildConfig = false
    }
}

dependencies {
}
