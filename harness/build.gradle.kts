plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.solstone.observer.harness"
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
    implementation(project(":core:diagnostics"))
    implementation(project(":core:model"))
    implementation(project(":core:sources"))
    implementation(project(":core:pl"))
    implementation(project(":core:identity"))
    implementation(project(":core:queue"))
    implementation(project(":core:segment"))
    implementation(project(":core:spool"))
    implementation(project(":core:observer"))
    implementation(project(":platform:fgs"))
    implementation(project(":platform:persistence-room"))
    implementation(project(":platform:pl-transport-conscrypt"))
    implementation(project(":platform:work"))
    implementation(project(":platform:identity-file"))
    implementation(project(":platform:camera-still"))

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation(project(":testing"))
}
