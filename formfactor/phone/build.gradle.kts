plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.solstone.observer.formfactor.phone"
    compileSdk = 36

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
                create("nexus7api35") {
                    device = "Nexus 7"
                    apiLevel = 35
                    systemImageSource = "google_apis"
                }
            }
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material3.adaptive:adaptive:1.2.0")
    implementation("androidx.compose.material3.adaptive:adaptive-layout:1.2.0")
    implementation("androidx.compose.ui:ui:1.10.6")
    implementation("androidx.compose.ui:ui-tooling-preview:1.10.6")
    debugImplementation("androidx.compose.ui:ui-tooling:1.10.6")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.10.6")
    implementation("androidx.activity:activity-compose:1.12.4")
    // Declared for a consumer outside this module's own source.
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation(project(":harness"))
    implementation(project(":core:model"))

    testImplementation(kotlin("test"))
    // Widget reducer tests construct the real HarnessController/SourceRegistry path. These stay
    // test-only to avoid leaking harness implementation seams into the phone UI runtime.
    testImplementation(project(":core:diagnostics"))
    testImplementation(project(":core:identity"))
    testImplementation(project(":core:pl"))
    testImplementation(project(":core:sources"))
    testImplementation(project(":platform:camera-still"))
    testImplementation(project(":platform:fgs"))
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.10.6")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
    androidTestImplementation(project(":harness"))
    androidTestImplementation(project(":core:model"))
}
