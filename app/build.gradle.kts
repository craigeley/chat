import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is read from a gitignored keystore.properties at the repo root
// (storeFile/storePassword/keyAlias/keyPassword). When it's absent — a fresh clone,
// CI without the secret — the release build falls back to debug signing so it still
// produces a sideloadable APK; only a real distribution build needs the keystore.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.craigeley.chat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.craigeley.chat"
        minSdk = 34   // Light Phone III runs Android 14 — the only target device.
        targetSdk = 35
        versionCode = 12
        versionName = "0.7.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // A stable release key when keystore.properties is present (required for
            // Obtainium/Play-style seamless updates), else debug-signed to sideload.
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // REST is plain HttpURLConnection + org.json (bundled in the platform), like
    // hive/pod. The live event feed is Socket.IO — a plain JVM client (pulls
    // okhttp + engine.io), NOT a Google dependency. org.json is excluded because
    // the platform already provides it (avoids a duplicate-class build error).
    implementation("io.socket:socket.io-client:2.1.0") {
        exclude(group = "org.json", module = "json")
    }
    // No Google Play Services / Firebase anywhere — that's the whole point.
}
