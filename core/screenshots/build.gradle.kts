plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:i18n"))
    implementation(project(":core:model"))
    implementation(project(":core:sources"))
    implementation(project(":core:segment"))
    implementation(project(":core:pl"))
    testImplementation(kotlin("test"))
}
