plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "se.krillster.inventeringshjalpen"
    compileSdk = 34

    defaultConfig {
        applicationId = "se.krillster.inventeringshjalpen"
        minSdk = 24
        targetSdk = 34
        versionCode = 11
        versionName = "2.0"
    }

    signingConfigs {
        // Fast nyckel i repot så att nya byggen kan installeras ovanpå gamla
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
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Textigenkänning (etiketter) – modellen följer med i appen, fungerar offline
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
