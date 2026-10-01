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
    implementation(project(":core:observer"))
    implementation(project(":core:crypto"))
    implementation(project(":core:pl"))
    implementation(project(":core:identity"))
    implementation("org.bouncycastle:bcprov-jdk15to18:1.85.1")
    testImplementation(kotlin("test"))
}
