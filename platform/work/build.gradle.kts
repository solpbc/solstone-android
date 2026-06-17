plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.solstone.platform.work"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    api("androidx.work:work-runtime:2.9.1")
    implementation(project(":platform:persistence-room"))
    implementation(project(":platform:pl-transport-conscrypt"))
    implementation(project(":platform:identity-file"))
    implementation(project(":core:observer"))
    implementation(project(":core:pl"))
    implementation(project(":core:identity"))
    implementation(project(":core:model"))
    implementation(project(":core:queue"))
    implementation(project(":core:sources"))

    testImplementation(kotlin("test"))
}
