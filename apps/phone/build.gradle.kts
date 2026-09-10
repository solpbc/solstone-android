import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class GenerateSolstoneGateBuildReceipt : DefaultTask() {
    @get:Input
    abstract val sourceCommit: Property<String>

    @get:Input
    abstract val driverContractVersion: Property<Int>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val receipt = """
            {"schema_version":1,"source_commit":"${sourceCommit.get()}","variant":"realDebug","driver_contract_version":${driverContractVersion.get()}}
        """.trimIndent() + "\n"
        val directory = outputDirectory.get().asFile
        directory.mkdirs()
        directory.resolve("solstone-android-gate-build-receipt.json").writeText(receipt)
    }
}

val gateReceiptAppDir = layout.buildDirectory.dir("generated/solstoneGateReceipt/realDebug/assets")
val gateReceiptTestDir = layout.buildDirectory.dir("generated/solstoneGateReceipt/androidTest/assets")
val gateDriverContractVersion = Regex(
    """const val SPL_GATE_DRIVER_CONTRACT_VERSION = (\d+)""",
).find(
    rootProject.file(
        "core/gate/src/main/kotlin/app/solstone/core/gate/GateAction.kt",
    ).readText(),
)?.groupValues?.get(1)?.toIntOrNull()
    ?: error("could not derive SPL gate driver contract version from GateAction.kt")
val gateSourceCommit = providers.environmentVariable("GATE_SOURCE_COMMIT")
    .map(String::trim)
    .filter { it.isNotEmpty() }
    .orElse(
        providers.exec {
            commandLine("git", "rev-parse", "HEAD")
        }.standardOutput.asText.map(String::trim),
    )
    .map { sourceCommit ->
        check(sourceCommit.matches(Regex("[0-9a-fA-F]{40}"))) {
            "GATE_SOURCE_COMMIT must be a full 40-character hexadecimal commit SHA"
        }
        sourceCommit
    }
val generateSolstoneGateBuildReceipt = tasks.register<GenerateSolstoneGateBuildReceipt>(
    "generateSolstoneGateBuildReceipt",
) {
    sourceCommit.set(gateSourceCommit)
    driverContractVersion.set(gateDriverContractVersion)
    outputDirectory.set(gateReceiptAppDir)
}
val generateSolstoneGateAndroidTestBuildReceipt = tasks.register<GenerateSolstoneGateBuildReceipt>(
    "generateSolstoneGateAndroidTestBuildReceipt",
) {
    sourceCommit.set(gateSourceCommit)
    driverContractVersion.set(gateDriverContractVersion)
    outputDirectory.set(gateReceiptTestDir)
}

android {
    namespace = "app.solstone.observer.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.solstone.observer.phone"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "2.0.0"
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

    buildFeatures {
        compose = true
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

androidComponents {
    onVariants(selector().withName("realDebug")) { variant ->
        // AGP owns both generated-asset dependencies and invalidation. The app and its
        // instrumentation APK have independent merge chains, so register each explicitly.
        variant.sources.assets?.addGeneratedSourceDirectory(
            generateSolstoneGateBuildReceipt,
            GenerateSolstoneGateBuildReceipt::outputDirectory,
        )
        requireNotNull(variant.androidTest) { "realDebug Android test component is missing" }
            .sources.assets?.addGeneratedSourceDirectory(
                generateSolstoneGateAndroidTestBuildReceipt,
                GenerateSolstoneGateBuildReceipt::outputDirectory,
            )
    }
}

tasks.register("verifySolstoneGateBuildReceipts") {
    group = "verification"
    description = "Verifies the gate receipt is exact in realDebug and absent from mock/release APKs."
    dependsOn(
        "assembleRealDebug",
        "assembleRealDebugAndroidTest",
        "assembleMockDebug",
        "assembleMockDebugAndroidTest",
        "assembleRealRelease",
    )
    inputs.property("sourceCommit", gateSourceCommit)
    inputs.property("driverContractVersion", gateDriverContractVersion)
    doLast {
        val expected = """
            {"schema_version":1,"source_commit":"${gateSourceCommit.get()}","variant":"realDebug","driver_contract_version":$gateDriverContractVersion}
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
        val nonGateApks = listOf(
            layout.buildDirectory.file("outputs/apk/mock/debug/phone-mock-debug.apk").get().asFile,
            layout.buildDirectory.file(
                "outputs/apk/androidTest/mock/debug/phone-mock-debug-androidTest.apk",
            ).get().asFile,
        ) + layout.buildDirectory.dir("outputs/apk/real/release").get().asFile
            .listFiles { file -> file.extension == "apk" }
            .orEmpty()
        check(nonGateApks.size >= 3) { "mock or release APK missing from gate-receipt verification" }
        nonGateApks.forEach { apk ->
            ZipFile(apk).use { zip ->
                check(zip.getEntry("assets/solstone-android-gate-build-receipt.json") == null) {
                    "non-gate APK contains a realDebug gate receipt: $apk"
                }
            }
        }
    }
}

dependencies {
    implementation(project(":apps:observer-scaffold"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:sources"))
    implementation(project(":core:pl"))
    implementation(project(":core:identity"))
    implementation(project(":formfactor:phone"))
    implementation(project(":harness"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    debugImplementation("androidx.compose.material3:material3:1.4.0")
    debugImplementation("androidx.compose.ui:ui:1.10.6")

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.10.6")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
    androidTestImplementation("androidx.glance:glance-appwidget-testing:1.1.1")
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
    androidTestImplementation(project(":platform:pl-transport-conscrypt"))
    androidTestImplementation(project(":platform:identity-file"))
    androidTestImplementation(project(":platform:work"))
    androidTestImplementation(project(":testing"))
}
