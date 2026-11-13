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
        versionCode = 29
        versionName = "2.7.0"

        // Phones that matter today are 64-bit ARM; dropping the 32-bit and x86 native libraries
        // (ML Kit and the video renderer carry several each) roughly halves the APK.
        ndk {
            abiFilters.add("arm64-v8a")
            // The emulator job in CI runs on x86_64; releases stay arm64-only.
            if (project.hasProperty("emulatorAbi")) abiFilters.add("x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        // Release builds are signed with a private key supplied by CI secrets when present
        // (KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD). Without them they fall back
        // to the committed debug key, so existing installs keep updating in place.
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_FILE")
            if (!keystorePath.isNullOrEmpty() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                storeFile = file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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

    lint {
        // CI builds the APKs; lint warnings (mostly API-level hints already guarded in code)
        // shouldn't block a release.
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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
    implementation("androidx.core:core-splashscreen:1.0.1")
    // Installs the bundled baseline profiles (Compose ships its own) so the UI starts faster.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
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

    // Portrait mode: bundled on-device person segmentation (no Play services, no network).
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")

    // Portrait mode's depth model (MiDaS small, downloaded once on first use) runs on the TensorFlow Lite runtime.
    implementation("org.tensorflow:tensorflow-lite:2.14.0")


    // Filters on recorded video are rendered after recording by the Media3 transformer.
    implementation("androidx.media3:media3-transformer:1.3.1")
    implementation("androidx.media3:media3-effect:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")

    testImplementation("junit:junit:4.13.2")

    // Tests that run on an emulator (see .github/workflows/android-emulator.yml).
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
