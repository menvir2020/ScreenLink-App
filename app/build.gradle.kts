plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.screenlink"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.screenlink"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // TVs y celulares: 64 y 32 bits (muchas TV usan armeabi-v7a).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    implementation("io.github.webrtc-sdk:android:125.6422.07")
}
