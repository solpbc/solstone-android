plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:sources"))
    implementation(project(":core:segment"))
    implementation(project(":core:spool"))
    implementation(project(":core:pl"))
    testImplementation(kotlin("test"))
}
