plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.solstone.platform.pl.transport.conscrypt"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
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
}
