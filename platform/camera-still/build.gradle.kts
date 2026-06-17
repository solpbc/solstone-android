plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:model"))
    api(project(":core:sources"))
    implementation(project(":core:segment"))
    api(project(":core:spool"))
    testImplementation(kotlin("test"))
}
