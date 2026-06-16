plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:sources"))
    implementation(project(":core:model"))
    implementation(project(":core:segment"))
    implementation(project(":core:spool"))
    testImplementation(kotlin("test"))
}
