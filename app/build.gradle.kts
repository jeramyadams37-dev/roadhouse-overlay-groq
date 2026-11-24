plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.atothei.roadhouse.overlay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.atothei.roadhouse.overlay.groq"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.0"
    }

    // Explicit debug signing. The first CI build produced an APK with no
    // signature files in META-INF and Android refused to parse it, so the
    // debug keystore is restored in the workflow from the secret (standard debug
    // credentials) and used here. If the keystore file is absent (local
    // builds), AGP falls back to its default ~/.android/debug.keystore.
    signingConfigs {
        getByName("debug") {
            val ks = file("../debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // No applicationIdSuffix on purpose: the debug APK installs as
            // com.atothei.roadhouse.overlay.groq, alongside the Gemini build.
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // EncryptedSharedPreferences for the BYOK Groq key. No key is ever
    // compiled into the app; this only protects the key the user pastes in.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // CameraX for hands-free video + photos (no taps to start recording).
    // The system-camera intent path cannot auto-start recording, so it
    // was not used; CameraX starts the moment the activity opens.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-video:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
}
