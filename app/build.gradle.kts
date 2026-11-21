plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// GitHub Actions passes -PbuildNumber=<run number> so every build gets a higher version.
val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "dev.yomiage"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.yomiage"
        minSdk = 30
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        ndk {
            // Your phone is 64-bit ARM; this keeps the APK small.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // A fixed key so each new build installs over the previous one (keeps the voice pack and settings).
        create("stable") {
            storeFile = file("yomiage.keystore")
            storePassword = "yomiage"
            keyAlias = "yomiage"
            keyPassword = "yomiage"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
        )
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
            )
        }
        jniLibs {
            // Store native libs uncompressed so ONNX Runtime loads fast.
            useLegacyPackaging = false
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Unpacks the Kokoro voice pack (.tar.bz2) on the phone.
    implementation("org.apache.commons:commons-compress:1.26.2")
    // On-device text recognition for images and apps that don't share their text.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
