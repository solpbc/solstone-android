plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:i18n"))
    api(project(":core:identity"))
    implementation(project(":core:crypto"))
    api(project(":core:diagnostics"))
    testImplementation(kotlin("test"))
}
