plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:i18n"))
    implementation(project(":core:pl"))
    implementation(project(":core:identity"))
    testImplementation(kotlin("test"))
}
