// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.solstone.observer.formfactor.glasses"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
    implementation(project(":harness"))
    implementation(project(":core:model"))
    implementation(project(":core:sources"))
    implementation(project(":core:pl"))
    implementation(project(":platform:fgs"))
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.zxing:core:3.5.3")
}
