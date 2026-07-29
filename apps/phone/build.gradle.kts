import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val gateReceiptMainDir = layout.buildDirectory.dir("generated/solstoneGateReceipt/main/assets")
val gateReceiptTestDir = layout.buildDirectory.dir("generated/solstoneGateReceipt/androidTest/assets")
val gateSourceCommit = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map(String::trim)
val generateSolstoneGateBuildReceipt by tasks.registering {
    inputs.property("sourceCommit", gateSourceCommit)
    outputs.dirs(gateReceiptMainDir, gateReceiptTestDir)
    doLast {
        val receipt = """
            {"schema_version":1,"source_commit":"${gateSourceCommit.get()}","variant":"realDebug","driver_contract_version":1}
        """.trimIndent() + "\n"
        listOf(gateReceiptMainDir.get().asFile, gateReceiptTestDir.get().asFile).forEach { directory ->
            directory.mkdirs()
            directory.resolve("solstone-android-gate-build-receipt.json").writeText(receipt)
        }
    }
}

android {
    namespace = "app.solstone.observer.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.solstone.observer.phone"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.2.2"
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

    sourceSets {
        getByName("main").assets.srcDir(gateReceiptMainDir)
        getByName("androidTest").assets.srcDir(gateReceiptTestDir)
    }
}

tasks.matching {
    it.name == "mergeRealDebugAssets" || it.name == "mergeRealDebugAndroidTestAssets"
}.configureEach {
    dependsOn(generateSolstoneGateBuildReceipt)
}

tasks.register("verifySolstoneGateBuildReceipts") {
    group = "verification"
    description = "Verifies the exact contract-v1 source receipt embedded in both realDebug APKs."
    dependsOn("assembleRealDebug", "assembleRealDebugAndroidTest")
    inputs.property("sourceCommit", gateSourceCommit)
    doLast {
        val expected = """
            {"schema_version":1,"source_commit":"${gateSourceCommit.get()}","variant":"realDebug","driver_contract_version":1}
        """.trimIndent() + "\n"
        val apks = listOf(
            layout.buildDirectory.file("outputs/apk/real/debug/phone-real-debug.apk").get().asFile,
            layout.buildDirectory.file(
                "outputs/apk/androidTest/real/debug/phone-real-debug-androidTest.apk",
            ).get().asFile,
        )
        apks.forEach { apk ->
            ZipFile(apk).use { zip ->
                val entry = requireNotNull(
                    zip.getEntry("assets/solstone-android-gate-build-receipt.json"),
                ) { "missing gate receipt in $apk" }
                val actual = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                check(actual == expected) { "gate receipt mismatch in $apk" }
            }
        }
    }
}

dependencies {
    implementation(project(":apps:observer-scaffold"))
    implementation(project(":core:sources"))

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation(project(":harness"))
    androidTestImplementation(project(":core:diagnostics"))
    androidTestImplementation(project(":core:identity"))
    androidTestImplementation(project(":core:gate"))
    androidTestImplementation(project(":core:observer"))
    androidTestImplementation(project(":core:crypto"))
    androidTestImplementation(project(":core:pl"))
    androidTestImplementation(project(":platform:camera-still"))
    androidTestImplementation(project(":platform:fgs"))
    androidTestImplementation(project(":platform:persistence-room"))
    androidTestImplementation(project(":platform:power"))
    androidTestImplementation(project(":platform:pl-transport-conscrypt"))
    androidTestImplementation(project(":platform:identity-file"))
    androidTestImplementation(project(":platform:work"))
    androidTestImplementation(project(":testing"))
}
