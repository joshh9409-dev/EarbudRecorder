plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.echolink.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.echolink.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 110
        versionName = "1.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
