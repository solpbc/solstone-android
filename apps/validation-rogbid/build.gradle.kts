plugins {
    id("com.android.application")
}

android {
    namespace = "app.solstone.validation.rogbid"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.solstone.validation.rogbid"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.work:work-runtime:2.9.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
}
