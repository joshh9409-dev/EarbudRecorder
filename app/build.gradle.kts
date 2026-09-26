plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.10.1")
}

android {
    namespace = "com.echolink.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.echolink.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 120
        versionName = "1.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
