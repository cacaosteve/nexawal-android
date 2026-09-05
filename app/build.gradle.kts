import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.nexatrode.nexawal"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.nexatrode.nexawal"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "NEXAWAL_TEST_MNEMONIC", "\"\"")
        buildConfigField("String", "NEXAWAL_TEST_RESTORE_HEIGHT", "\"\"")
    }

    val localSigningProps = Properties().apply {
        val localFile = rootProject.file("local.properties")
        if (localFile.exists()) {
            localFile.inputStream().use { load(it) }
        }
    }
    val releaseStoreFile = localSigningProps.getProperty("nexawal.store.file")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { path ->
            val asRoot = rootProject.file(path)
            when {
                asRoot.isFile -> asRoot
                file(path).isFile -> file(path)
                else -> null
            }
        }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = localSigningProps.getProperty("nexawal.store.password", "")
                keyAlias = localSigningProps.getProperty("nexawal.key.alias", "")
                keyPassword = localSigningProps.getProperty("nexawal.key.password", "")
            }
        }
    }

    buildTypes {
        // DEBUG first-run testing: set in local.properties (gitignored), e.g.
        //   nexawal.test.mnemonic=word1 word2 ... word25
        //   nexawal.test.restoreHeight=3519450
        debug {
            val localProps = Properties()
            val localFile = rootProject.file("local.properties")
            if (localFile.exists()) {
                localFile.inputStream().use { stream -> localProps.load(stream) }
            }
            fun escapeBuildConfig(value: String): String =
                value.replace("\\", "\\\\").replace("\"", "\\\"")
            val testMnemonic = localProps.getProperty("nexawal.test.mnemonic", "") ?: ""
            val testRestoreHeight = localProps.getProperty("nexawal.test.restoreHeight", "") ?: ""
            buildConfigField(
                "String",
                "NEXAWAL_TEST_MNEMONIC",
                "\"${escapeBuildConfig(testMnemonic)}\"",
            )
            buildConfigField(
                "String",
                "NEXAWAL_TEST_RESTORE_HEIGHT",
                "\"${escapeBuildConfig(testRestoreHeight)}\"",
            )
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "NEXAWAL_TEST_MNEMONIC", "\"\"")
            buildConfigField("String", "NEXAWAL_TEST_RESTORE_HEIGHT", "\"\"")
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":logic"))
    implementation(project(":walletcore"))
    implementation(project(":walletcore-api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation (Compose)
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Coroutines (WalletManager refresh polling / persistence)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // JSON parsing (transfers list)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // QR encode (Receive) + decode (Send camera). ZXing is FOSS; no ML Kit / GMS.
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.36.0")

    // HTTP client (dev probe: daemon /get_height from emulator network stack)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(libs.junit)
    testImplementation(libs.androidx.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
