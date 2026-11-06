plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.chagok.app"
    compileSdk = 35

    // Android Automotive car API (vehicle speed, gear). Ships with the Android SDK.
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.chagok.app"
        minSdk = 28 // Pleos requirement (signing key rotation)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Only Korean (+ English fallback) strings from libraries; drops ~80 other languages.
        resourceConfigurations += listOf("ko", "en")
    }

    buildTypes {
        release {
            // Size: R8 removes unused code (most of Compose/Material) and unused resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Contest/test APK: signed with the local debug key so it installs directly.
            // Use your own release key for any real distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources {
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
        }
    }

    // Google Play dependency metadata block is not needed for this APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
}

dependencies {
    implementation(libs.vehicle.sdk)
    implementation(libs.navi.helper.sdk)
    implementation(libs.zxing.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
}
