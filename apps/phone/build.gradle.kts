plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.solstone.observer.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.solstone.observer.phone"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Release signing via the Play upload keystore, ingested from the
        // environment (never hard-coded). Configured only when the keystore env
        // var is present, so debug builds and keystore-less machines (the CI /
        // pure-JVM gate) are unaffected and release stays unsigned there.
        System.getenv("ANDROID_UPLOAD_KEYSTORE")?.let { storePath ->
            create("release") {
                storeFile = file(storePath)
                storePassword = System.getenv("ANDROID_UPLOAD_KEYSTORE_PASS")
                keyAlias = System.getenv("ANDROID_UPLOAD_KEY_ALIAS") ?: "upload"
                keyPassword = System.getenv("ANDROID_UPLOAD_KEY_PASS")
                    ?: System.getenv("ANDROID_UPLOAD_KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
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

    testOptions {
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
    implementation(project(":harness"))
    implementation(project(":formfactor:phone"))
    implementation(project(":core:model"))
    implementation(project(":core:sources"))
    implementation(project(":core:segment"))
    implementation(project(":core:spool"))
    implementation(project(":core:observer"))
    implementation(project(":core:queue"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:identity"))
    implementation(project(":core:pl"))
    implementation(project(":platform:fgs"))
    implementation(project(":platform:power"))
    implementation(project(":platform:persistence-room"))
    implementation(project(":platform:identity-file"))
    implementation(project(":platform:work"))
    implementation(project(":platform:camera-still"))
    add("realImplementation", project(":platform:audio"))
    add("realImplementation", project(":platform:location"))
    add("realImplementation", project(":platform:camera-legacy"))
    add("realImplementation", project(":platform:camera2"))
    add("mockImplementation", project(":testing"))

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
