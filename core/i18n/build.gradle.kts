plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}
kotlin { jvmToolchain(17) }
sourceSets { main { java.srcDir(rootProject.file("i18n/generated/phone/jvm")) } }
dependencies { testImplementation(kotlin("test")) }
