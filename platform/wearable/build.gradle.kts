plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.solstone.platform.wearable"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation(kotlin("test"))
    api("com.google.android.gms:play-services-wearable:20.0.1") {
        version { strictly("20.0.1") }
    }
    constraints {
        api("com.google.android.gms:play-services-base:18.5.0") {
            version { strictly("18.5.0") }
        }
        api("com.google.android.gms:play-services-basement:18.9.0") {
            version { strictly("18.9.0") }
        }
    }
}
