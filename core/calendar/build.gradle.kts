plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:sources"))
    implementation(project(":core:segment"))
    implementation(project(":core:pl"))
    testImplementation(kotlin("test"))
}
