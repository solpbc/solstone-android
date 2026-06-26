plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:sources"))
    api(project(":core:metadata"))
    implementation(project(":core:model"))
    implementation(project(":core:segment"))
    implementation(project(":core:spool"))
    implementation(project(":platform:camera-still"))
    testImplementation(kotlin("test"))
}
