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

fun foregroundServiceTypeTokens(manifestText: String): Set<String> =
    Regex("""foregroundServiceType\s*=\s*["']([^"']+)["']""")
        .findAll(manifestText)
        .flatMap { match -> match.groupValues[1].split('|').asSequence() }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

fun intentFilterTokenGroups(manifestText: String): List<Set<String>> =
    Regex("""<intent-filter[^>]*>(.*?)</intent-filter>""", RegexOption.DOT_MATCHES_ALL)
        .findAll(manifestText)
        .map { match ->
            Regex("""android:name\s*=\s*["']([^"']+)["']""")
                .findAll(match.groupValues[1])
                .map { it.groupValues[1] }
                .toSet()
        }
        .toList()

data class IntentDataTokens(
    val scheme: String?,
    val host: String?,
    val path: String?,
)

data class IntentFilterTokens(
    val autoVerify: Boolean,
    val names: Set<String>,
    val data: List<IntentDataTokens>,
)

fun intentFilterTokens(manifestText: String): List<IntentFilterTokens> =
    Regex("""<intent-filter([^>]*)>(.*?)</intent-filter>""", RegexOption.DOT_MATCHES_ALL)
        .findAll(manifestText)
        .map { filter ->
            val body = filter.groupValues[2]
            fun attribute(text: String, name: String): String? =
                Regex("""android:$name\s*=\s*["']([^"']+)["']""")
                    .find(text)
                    ?.groupValues
                    ?.get(1)
            IntentFilterTokens(
                autoVerify = attribute(filter.groupValues[1], "autoVerify") == "true",
                names = Regex("""android:name\s*=\s*["']([^"']+)["']""")
                    .findAll(body)
                    .map { it.groupValues[1] }
                    .toSet(),
                data = Regex("""<data\b[^>]*>""")
                    .findAll(body)
                    .map { data ->
                        IntentDataTokens(
                            scheme = attribute(data.value, "scheme"),
                            host = attribute(data.value, "host"),
                            path = attribute(data.value, "path"),
                        )
                    }
                    .toList(),
            )
        }
        .toList()

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

tasks.register("manifestGuardSelfTest") {
    group = "verification"
    description = "Exercises manifest foreground service type token parsing."

    doLast {
        val tokens = foregroundServiceTypeTokens("""<service android:foregroundServiceType="microphone|location|camera" />""")
        check("microphone" in tokens)
        check("location" in tokens)
        check("camera" in tokens)
        check("dataSync" !in tokens)

        val noLocationTokens = foregroundServiceTypeTokens("""<service android:foregroundServiceType="microphone|camera" />""")
        check("microphone" in noLocationTokens)
        check("camera" in noLocationTokens)
        check("location" !in noLocationTokens)
    }
}

tasks.register("launcherHomeGuardSelfTest") {
    group = "verification"
    description = "Exercises intent-filter token grouping for the launcher/HOME manifest guard."
    doLast {
        val main = "android.intent.action.MAIN"
        val launcher = "android.intent.category.LAUNCHER"
        val home = "android.intent.category.HOME"
        val default = "android.intent.category.DEFAULT"
        fun List<Set<String>>.hasFilter(vararg names: String) = any { g -> names.all { it in g } }

        val both = intentFilterTokenGroups(
            """<intent-filter><action android:name="$main" /><category android:name="$launcher" /></intent-filter>""" +
                """<intent-filter><action android:name="$main" /><category android:name="$home" /><category android:name="$default" /></intent-filter>"""
        )
        check(both.hasFilter(main, launcher))
        check(both.hasFilter(main, home, default))

        val launcherOnly = intentFilterTokenGroups(
            """<intent-filter><action android:name="$main" /><category android:name="$launcher" /></intent-filter>"""
        )
        check(launcherOnly.hasFilter(main, launcher))
        check(!launcherOnly.hasFilter(main, home, default))
        check(launcherOnly.none { home in it })

        // HOME and DEFAULT on SEPARATE filters must NOT satisfy co-occurrence.
        val split = intentFilterTokenGroups(
            """<intent-filter><action android:name="$main" /><category android:name="$home" /></intent-filter>""" +
                """<intent-filter><action android:name="$main" /><category android:name="$default" /></intent-filter>"""
        )
        check(!split.hasFilter(main, home, default))
    }
}

tasks.register("appLinksGuardSelfTest") {
    group = "verification"
    description = "Exercises verified App Links intent-filter parsing."
    doLast {
        val view = "android.intent.action.VIEW"
        val default = "android.intent.category.DEFAULT"
        val browsable = "android.intent.category.BROWSABLE"
        fun List<IntentFilterTokens>.hasVerifiedPairLink() = any { filter ->
            filter.autoVerify &&
                setOf(view, default, browsable).all { it in filter.names } &&
                filter.data.any { it == IntentDataTokens("https", "go.solstone.app", "/p") }
        }

        val valid = intentFilterTokens(
            """<intent-filter android:autoVerify="true"><action android:name="$view" />""" +
                """<category android:name="$default" /><category android:name="$browsable" />""" +
                """<data android:scheme="https" android:host="go.solstone.app" android:path="/p" /></intent-filter>"""
        )
        check(valid.hasVerifiedPairLink())

        val notVerified = intentFilterTokens(
            """<intent-filter><action android:name="$view" /><category android:name="$default" />""" +
                """<category android:name="$browsable" /><data android:scheme="https" android:host="go.solstone.app" android:path="/p" /></intent-filter>"""
        )
        check(!notVerified.hasVerifiedPairLink())

        val explicitlyFalse = intentFilterTokens(
            """<intent-filter android:autoVerify="false"><action android:name="$view" /><category android:name="$default" />""" +
                """<category android:name="$browsable" /><data android:scheme="https" android:host="go.solstone.app" android:path="/p" /></intent-filter>"""
        )
        check(!explicitlyFalse.hasVerifiedPairLink())

        val wrongScheme = intentFilterTokens(
            """<intent-filter android:autoVerify="true"><action android:name="$view" /><category android:name="$default" />""" +
                """<category android:name="$browsable" /><data android:scheme="http" android:host="go.solstone.app" android:path="/p" /></intent-filter>"""
        )
        check(!wrongScheme.hasVerifiedPairLink())

        val wrongHost = intentFilterTokens(
            """<intent-filter android:autoVerify="true"><action android:name="$view" /><category android:name="$default" />""" +
                """<category android:name="$browsable" /><data android:scheme="https" android:host="example.invalid" android:path="/p" /></intent-filter>"""
        )
        check(!wrongHost.hasVerifiedPairLink())

        val wrongPath = intentFilterTokens(
            """<intent-filter android:autoVerify="true"><action android:name="$view" /><category android:name="$default" />""" +
                """<category android:name="$browsable" /><data android:scheme="https" android:host="go.solstone.app" android:path="/other" /></intent-filter>"""
        )
        check(!wrongPath.hasVerifiedPairLink())

        val split = intentFilterTokens(
            """<intent-filter android:autoVerify="true"><action android:name="$view" /><category android:name="$default" /><category android:name="$browsable" /></intent-filter>""" +
                """<intent-filter><data android:scheme="https" android:host="go.solstone.app" android:path="/p" /></intent-filter>"""
        )
        check(!split.hasVerifiedPairLink())
    }
}

tasks.named("check") {
    dependsOn(
        "checkPrivacyDeps",
        "checkCorePurity",
        "privacyGuardSelfTest",
        "purityGuardSelfTest",
        "manifestGuardSelfTest",
        "launcherHomeGuardSelfTest",
        "appLinksGuardSelfTest",
    )
}

fun Project.registerMicrophoneManifestCheck(requireLocation: Boolean = true) {
    tasks.register("checkRealDebugMicrophoneManifest") {
        group = "verification"
        description = "Checks the realDebug merged manifest for microphone foreground service declarations."
        dependsOn("processRealDebugManifest")

        doLast {
            val manifest = layout.buildDirectory
                .file("intermediates/merged_manifests/realDebug/processRealDebugManifest/AndroidManifest.xml")
                .get()
                .asFile
            if (!manifest.exists()) {
                throw GradleException("Merged manifest not found: ${manifest.relativeTo(rootProject.projectDir)}")
            }
            val text = manifest.readText()
            val failures = mutableListOf<String>()
            if (!text.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE")) {
                failures += "missing FOREGROUND_SERVICE_MICROPHONE permission"
            }
            if (!text.contains("android.permission.FOREGROUND_SERVICE_CAMERA")) {
                failures += "missing FOREGROUND_SERVICE_CAMERA permission"
            }
            val foregroundServiceTypes = foregroundServiceTypeTokens(text)
            if ("microphone" !in foregroundServiceTypes) {
                failures += "foregroundServiceType must include microphone"
            }
            if ("camera" !in foregroundServiceTypes) {
                failures += "foregroundServiceType must include camera"
            }
            if (requireLocation) {
                if (!text.contains("android.permission.FOREGROUND_SERVICE_LOCATION")) {
                    failures += "missing FOREGROUND_SERVICE_LOCATION permission"
                }
                if ("location" !in foregroundServiceTypes) {
                    failures += "foregroundServiceType must include location"
                }
            } else {
                if (text.contains("android.permission.FOREGROUND_SERVICE_LOCATION")) {
                    failures += "must not declare FOREGROUND_SERVICE_LOCATION permission"
                }
                if ("location" in foregroundServiceTypes) {
                    failures += "foregroundServiceType must not include location"
                }
            }
            if (text.contains("dataSync")) {
                failures += "must not declare dataSync"
            }
            if (failures.isNotEmpty()) {
                throw GradleException("${project.path} realDebug microphone manifest check failed:\n${failures.joinToString("\n")}")
            }
        }
    }
}

fun Project.registerLauncherHomeManifestCheck(requireHome: Boolean) {
    tasks.register("checkRealDebugLauncherManifest") {
        group = "verification"
        description = "Checks the realDebug merged manifest for the launcher and (glasses only) HOME intent-filters."
        dependsOn("processRealDebugManifest")
        doLast {
            val manifest = layout.buildDirectory
                .file("intermediates/merged_manifests/realDebug/processRealDebugManifest/AndroidManifest.xml")
                .get()
                .asFile
            if (!manifest.exists()) {
                throw GradleException("Merged manifest not found: ${manifest.relativeTo(rootProject.projectDir)}")
            }
            val text = manifest.readText()
            val main = "android.intent.action.MAIN"
            val launcher = "android.intent.category.LAUNCHER"
            val home = "android.intent.category.HOME"
            val default = "android.intent.category.DEFAULT"
            val groups = intentFilterTokenGroups(text)
            fun hasFilter(vararg names: String) = groups.any { g -> names.all { it in g } }
            val failures = mutableListOf<String>()
            if (!hasFilter(main, launcher)) {
                failures += "missing MAIN + LAUNCHER intent-filter"
            }
            if (requireHome) {
                if (!hasFilter(main, home, default)) {
                    failures += "missing MAIN + HOME + DEFAULT intent-filter"
                }
            } else {
                if (groups.any { home in it }) {
                    failures += "must not declare CATEGORY_HOME"
                }
            }
            if (failures.isNotEmpty()) {
                throw GradleException("${project.path} realDebug launcher manifest check failed:\n${failures.joinToString("\n")}")
            }
        }
    }
}

fun Project.registerAppLinksManifestCheck() {
    tasks.register("checkRealDebugAppLinksManifest") {
        group = "verification"
        description = "Checks the realDebug merged manifest for the verified pair App Link."
        dependsOn("processRealDebugManifest")
        doLast {
            val manifest = layout.buildDirectory
                .file("intermediates/merged_manifests/realDebug/processRealDebugManifest/AndroidManifest.xml")
                .get()
                .asFile
            if (!manifest.exists()) {
                throw GradleException("Merged manifest not found: ${manifest.relativeTo(rootProject.projectDir)}")
            }
            val view = "android.intent.action.VIEW"
            val default = "android.intent.category.DEFAULT"
            val browsable = "android.intent.category.BROWSABLE"
            val present = intentFilterTokens(manifest.readText()).any { filter ->
                filter.autoVerify &&
                    setOf(view, default, browsable).all { it in filter.names } &&
                    filter.data.any { it == IntentDataTokens("https", "go.solstone.app", "/p") }
            }
            if (!present) {
                throw GradleException("${project.path} realDebug App Links manifest check failed:\nmissing verified https://go.solstone.app/p VIEW intent-filter")
            }
        }
    }
}

project(":apps:watch") {
    registerMicrophoneManifestCheck()
    registerLauncherHomeManifestCheck(requireHome = false)
}

project(":apps:phone") {
    registerMicrophoneManifestCheck()
    registerLauncherHomeManifestCheck(requireHome = false)
    registerAppLinksManifestCheck()
}

project(":apps:glasses") {
    registerMicrophoneManifestCheck(requireLocation = false)
    registerLauncherHomeManifestCheck(requireHome = true)
}

project(":apps:validation-rogbid") {
    tasks.configureEach {
        if (name.startsWith("lint")) {
            enabled = false
        }
    }
}
