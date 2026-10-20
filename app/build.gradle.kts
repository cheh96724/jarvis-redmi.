plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {
    namespace = "com.jarvis.redmi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.redmi"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
