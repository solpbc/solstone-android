// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.solstone.platform.garmin"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation(kotlin("test"))
    api("com.garmin.connectiq:ciq-companion-app-sdk:2.4.0") {
        version { strictly("2.4.0") }
    }
}
