plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.solstone.observer.scaffold"
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
    api(project(":formfactor:shared"))
    implementation(project(":harness"))
    implementation(project(":core:model"))
    implementation(project(":core:sources"))
    implementation(project(":core:segment"))
    implementation(project(":core:spool"))
    implementation(project(":core:observer"))
    implementation(project(":core:queue"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:identity"))
    implementation(project(":core:pl"))
    api(project(":platform:fgs"))
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

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
