plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nikoezveracamspiral"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nikoezveracamspiral"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.1.0"
    }

    // Without this, Gradle falls back to its own default debug config, which auto-generates
    // ~/.android/debug.keystore the first time it's needed -- fine on a single dev machine, but
    // every GitHub Actions run starts on a fresh runner with no such keystore, so each CI build
    // got signed with a brand-new, different key. Android refuses to install an update signed
    // with a different key than the one already on the device, so every OTA update silently
    // failed to apply, forcing an uninstall first. Pointing every build (CI and local) at the
    // same committed keystore/alias/passwords keeps the signature stable across releases.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // The camera engine is built directly on android.hardware.camera2 rather than CameraX: Pro
    // mode's manual ISO/shutter/focus/white-balance controls and true RAW_SENSOR (DNG) capture
    // both need CaptureRequest-level access that CameraX's stable public API doesn't expose.
    // These two libraries are the only non-Compose additions that raw Camera2 usage calls for:
    // coroutines to bridge Camera2's callback APIs into suspend functions, and exifinterface to
    // write correct orientation/metadata into saved JPEGs.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    testImplementation("junit:junit:4.13.2")
}
