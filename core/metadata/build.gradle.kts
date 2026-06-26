plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:sources"))
    api(project(":core:segment"))
    api(project(":core:spool"))
    testImplementation(project(":testing"))
    testImplementation(kotlin("test"))
}
