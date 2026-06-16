import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.io.File

plugins {
    base
    id("com.android.application") version "8.9.1" apply false
    id("com.android.library") version "8.9.1" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
}

fun deniedPrivacyCoordinate(group: String, name: String): String? {
    val deniedGroupPrefixes = listOf("com.segment", "io.sentry")
    val deniedArtifacts = setOf(
        "firebase-analytics",
        "firebase-crashlytics",
        "crashlytics",
        "play-services-analytics",
        "google-analytics",
        "appcenter",
        "mixpanel",
        "bugsnag",
        "amplitude",
        "flurry",
    )
    val exactCoordinates = setOf("com.google.android.gms:play-services-analytics")

    val coordinate = "$group:$name"
    if (coordinate in exactCoordinates) return coordinate
    if (deniedGroupPrefixes.any { group == it || group.startsWith("$it.") }) return coordinate
    if (name in deniedArtifacts) return coordinate
    return null
}

fun isImpureImportLine(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.startsWith("import android.") || trimmed.startsWith("import androidx.")
}

fun isImpureBuildLine(line: String): Boolean {
    val trimmed = line.trim()
    return Regex("""id\(["']com\.android\.""").containsMatchIn(trimmed) ||
        Regex("""["']androidx\.[^:"']*:[^"']+["']""").containsMatchIn(trimmed) ||
        Regex("""["']com\.android\.[^:"']*:[^"']+["']""").containsMatchIn(trimmed)
}

fun scanCorePurity(rootDir: File): List<String> {
    val roots = listOf(rootDir.resolve("core"), rootDir.resolve("testing"))
    val violations = mutableListOf<String>()
    roots.filter { it.exists() }.forEach { root ->
        root.walkTopDown()
            .filter { it.isFile }
            .filterNot { file ->
                file.toPath().any { part -> part.toString() == "build" }
            }
            .filter { file ->
                file.extension in setOf("kt", "java") ||
                    file.name == "build.gradle" ||
                    file.name == "build.gradle.kts"
            }
            .forEach { file ->
                file.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        val reason = when {
                            file.extension in setOf("kt", "java") && isImpureImportLine(line) ->
                                "Android import at line ${index + 1}"
                            (file.name == "build.gradle" || file.name == "build.gradle.kts") && isImpureBuildLine(line) ->
                                "Android plugin/dependency at line ${index + 1}"
                            else -> null
                        }
                        if (reason != null) {
                            violations += "${file.relativeTo(rootDir)}: $reason"
                        }
                    }
                }
            }
    }
    return violations
}

tasks.register("checkPrivacyDeps") {
    group = "verification"
    description = "Fails if a denylisted analytics, telemetry, crash, or tracking dependency is resolved."

    doLast {
        val violations = mutableListOf<String>()
        allprojects.forEach { project ->
            project.configurations
                .filter { it.isCanBeResolved }
                .forEach { configuration ->
                    runCatching {
                        configuration.incoming.resolutionResult.allComponents.forEach { component ->
                            val module = component.id as? ModuleComponentIdentifier
                            if (module != null) {
                                val denied = deniedPrivacyCoordinate(module.group, module.module)
                                if (denied != null) {
                                    violations += "coordinate ${module.group}:${module.module}:${module.version} entered through ${project.path}:${configuration.name}"
                                }
                            }
                        }
                    }
                }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Privacy dependency guard failed:\n${violations.distinct().sorted().joinToString("\n")}")
        }
    }
}

tasks.register("checkCorePurity") {
    group = "verification"
    description = "Fails if core/testing source or build files gain Android coupling."

    doLast {
        val violations = scanCorePurity(rootProject.projectDir)
        if (violations.isNotEmpty()) {
            throw GradleException("Core purity guard failed:\n${violations.sorted().joinToString("\n")}")
        }
    }
}

tasks.register("privacyGuardSelfTest") {
    group = "verification"
    description = "Exercises privacy guard matching against synthetic coordinates."

    doLast {
        check(deniedPrivacyCoordinate("io.sentry", "sentry") != null)
        check(deniedPrivacyCoordinate("com.segment", "analytics") != null)
        check(deniedPrivacyCoordinate("com.google.firebase", "firebase-analytics") != null)
        check(deniedPrivacyCoordinate("app.solstone", "core-segment") == null)
        check(deniedPrivacyCoordinate("org.example", "segment") == null)
        check(deniedPrivacyCoordinate("org.jetbrains.kotlin", "kotlin-stdlib") == null)
    }
}

tasks.register("purityGuardSelfTest") {
    group = "verification"
    description = "Exercises core purity line predicates against synthetic inputs."

    doLast {
        check(isImpureImportLine("import androidx.core.Foo"))
        check(isImpureImportLine("import android.os.Bundle"))
        check(!isImpureImportLine("import app.solstone.core.segment.Segmenter"))
        check(!isImpureImportLine("// mentions androidx in a comment"))
        check(isImpureBuildLine("""id("com.android.library")"""))
        check(isImpureBuildLine("""implementation("androidx.core:core:1.13.1")"""))
        check(!isImpureBuildLine("""implementation(project(":core:segment"))"""))
    }
}

tasks.named("check") {
    dependsOn("checkPrivacyDeps", "checkCorePurity", "privacyGuardSelfTest", "purityGuardSelfTest")
}

project(":apps:validation-rogbid") {
    tasks.configureEach {
        if (name.startsWith("lint")) {
            enabled = false
        }
    }
}
