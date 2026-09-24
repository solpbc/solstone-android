plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:pl"))
    implementation(project(":core:identity"))
    testImplementation(kotlin("test"))
}
