plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.solstone.observer.formfactor.shared"
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
    implementation("com.google.zxing:core:3.5.3")

    testImplementation(kotlin("test"))
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation(project(":core:identity"))
    androidTestImplementation(project(":platform:camera-still"))
    androidTestImplementation(project(":testing"))
}
