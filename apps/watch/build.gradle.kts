plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.solstone.observer.watch"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.solstone.observer.watch"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    flavorDimensions += "mode"
    productFlavors {
        create("mock") {
            dimension = "mode"
        }
        create("real") {
            dimension = "mode"
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:sources"))
    implementation(project(":core:segment"))
    implementation(project(":core:spool"))
    implementation(project(":core:queue"))
    implementation(project(":core:diagnostics"))
}
