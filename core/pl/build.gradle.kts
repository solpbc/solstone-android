plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:identity"))
    implementation(project(":core:crypto"))
    implementation(project(":core:diagnostics"))
    testImplementation(kotlin("test"))
}
