plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.solstone.platform.pl.transport.conscrypt"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The androidTest APK must target >= SDK 24 to install on API 34+ devices
    // (the A36 / API 36 bench target). Applies to the test APK only; the AAR ignores it.
    testOptions {
        targetSdk = 35
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
    api(project(":core:pl"))
    implementation(project(":core:crypto"))
    implementation(project(":core:identity"))
    implementation(project(":core:model"))
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    // Live on-device driver (VPE-direct validation): wires this transport to the
    // observer client + a file-backed credential store. Skips itself (JUnit Assume)
    // unless an `-e pairLink ...` instrumentation arg points it at a live journal,
    // so it is inert in CI / GMD and only runs in a hand-driven device pass.
    androidTestImplementation(project(":core:observer"))
    androidTestImplementation(project(":platform:identity-file"))
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
