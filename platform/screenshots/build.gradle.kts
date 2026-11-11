plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.solstone.platform.screenshots"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        targetSdk = 35
        managedDevices {
            localDevices {
                create("pixel5api35") {
                    device = "Pixel 5"
                    apiLevel = 35
                    systemImageSource = "google_apis"
                }
            }
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
    implementation(project(":core:screenshots"))
    implementation(project(":core:model"))
    implementation(project(":core:segment"))
    implementation(project(":core:spool"))
    implementation(project(":core:sources"))
    implementation(project(":core:pl"))
    implementation(project(":platform:persistence-room"))
    implementation("androidx.work:work-runtime:2.9.1")

    testImplementation(kotlin("test"))
}
