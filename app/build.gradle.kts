plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.marketforecast.prox"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.marketforecast.prox"
        minSdk = 26
        targetSdk = 36
        versionCode = 115
        versionName = "4.8.74"
    }
    buildFeatures { compose = true }

    // Resolve duplicate native libraries that can be contributed by transitive
    // AndroidX/Compose dependencies during mergeDebugNativeLibs. Keep one
    // deterministic copy so the debug APK packaging stage cannot fail on a
    // duplicate .so entry.
    packaging {
        jniLibs {
            pickFirsts += "**/*.so"
        }
    }

    signingConfigs {
        create("mfpDebug") {
            storeFile = rootProject.file("signing/mfp-debug.keystore")
            storePassword = "android"
            keyAlias = "mfpdebug"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("mfpDebug")
        }
    }

    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("androidx.core:core-ktx:1.15.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
