plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.solstone.platform.work"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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
}

dependencies {
    api("androidx.work:work-runtime:2.9.1")
    api(project(":core:push"))
    implementation(project(":platform:persistence-room"))
    implementation(project(":platform:pl-transport-conscrypt"))
    implementation(project(":platform:identity-file"))
    implementation(project(":core:observer"))
    implementation(project(":core:pl"))
    implementation(project(":core:identity"))
    implementation(project(":core:model"))
    implementation(project(":core:crypto"))
    implementation(project(":core:queue"))
    implementation(project(":core:sources"))
    implementation(project(":core:spool"))

    testImplementation(kotlin("test"))
    testImplementation(project(":core:spool"))
    androidTestImplementation("androidx.work:work-testing:2.9.1")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation(kotlin("test"))
}
