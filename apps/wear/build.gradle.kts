plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.solstone.observer.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.solstone.observer.phone"
        minSdk = 30
        targetSdk = 36
        versionCode = 51
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val courierStrings = rootProject.file("core/watch-courier/src/main/kotlin/app/solstone/core/watch/courier/WatchCourierStrings.kt").readText()
        val launcherName = Regex("const val SOLSTONE = \"([^\"]+)\"").find(courierStrings)?.groupValues?.get(1)
            ?: error("Missing named watch launcher string")
        resValue("string", "app_name", launcherName)
    }

    signingConfigs {
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
        debug {
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}

dependencies {
    implementation("androidx.wear:wear:1.4.0")
    implementation("androidx.wear:wear-ongoing:1.1.0")
    implementation("androidx.concurrent:concurrent-futures:1.3.0")
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.3.0")

    implementation(project(":core:watch-courier"))
    implementation(project(":core:segment"))
    implementation(project(":platform:audio"))
    implementation(project(":platform:wearable"))
    implementation("androidx.work:work-runtime:2.9.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity:1.12.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
