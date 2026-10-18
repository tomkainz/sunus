plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.tomkainz.sonoslive"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.tomkainz.sonoslive"
        // AudioPlaybackCapture exists from Android 10
        minSdk = 29
        targetSdk = 37
        versionCode = 14
        versionName = "1.0-beta"
    }

    // The key is only stored locally, not in the repository. Without it the
    // release APK is unsigned; use assembleDebug to try the app instead.
    val releaseKeystore = rootProject.file("keystore/sonoslive.jks")

    signingConfigs {
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = "sonoslive"
                keyAlias = "sonoslive"
                keyPassword = "sonoslive"
            }
        }
    }

    buildTypes {
        release {
            // No R8: SubmixRecorder is started by class name via app_process
            // and must be neither renamed nor removed.
            isMinifyEnabled = false
            isShrinkResources = false
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/*.kotlin_module"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Built-in ADB client: pairs with the phone itself via "Wireless debugging"
    // and uses that to start the capture helper with shell privileges.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")
}
