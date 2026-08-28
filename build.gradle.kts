import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.io.File

plugins {
    base
    id("com.android.application") version "8.9.1" apply false
    id("com.android.library") version "8.9.1" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
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

fun deniedNavigationCoordinate(group: String, name: String): String? {
    if (group == "androidx.compose.material3.adaptive" && name == "adaptive-navigation") {
        return null
    }
    if (group == "androidx.navigation" || group.startsWith("androidx.navigation.")) {
        return "$group:$name"
    }
    if (group == "androidx.navigation3" || group.startsWith("androidx.navigation3.")) {
        return "$group:$name"
    }
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

private val splGateRequiredMarkers = listOf(
    "GateInvocation.decide",
    "GateInvocationDecision.Skip",
    "syncStores",
    "RealPlStatusProbe",
    "SegmentReconciler",
    "ObserverActivity",
    "onScannedPairLink",
    "syncNow()",
    "listEvidence()",
    "audio.m4a",
    "QueueState.UPLOADED",
    "SplIntegrationGateDriverTest",
    "pair-authority.json",
    "action-result.json",
    "action-progress.json",
)

private val splGateBannedMarkers = listOf(
    Regex("""\b(Mock|Fake|Loopback|Stub)[A-Za-z0-9_]*\b""") to "test-double type",
    Regex("""RelayPairLink\s*\.\s*toString|\.toString\(\).*RelayPairLink""") to "RelayPairLink.toString",
    Regex("""\bLog\s*\.""") to "driver logging",
    Regex("""printStackTrace|getStackTrace|stackTraceToString""") to "stack trace rendering",
    Regex("""["'][^"']*\${'$'}\{?\s*pairLink|pairLink\s*\+|\+\s*pairLink""", RegexOption.IGNORE_CASE) to "pair-link interpolation",
    Regex("""RealSplGateDriverTest|g1-pair-status-register|g2-large-response-facts|g3-interrupted-stream-recovery|g4-g5-degraded-restored-status""") to "obsolete gate contract",
    Regex("""files/spl-gate|pair-link\.txt|spl-gate/result\.json""") to "obsolete gate path",
    Regex("""NetworkDenialController|svc\s+(wifi|data)""") to "driver-owned network mutation",
    Regex("""GateConnectivity""") to "device-wide gate connectivity",
)

private val splGateSideEffectMarkers = listOf(
    "syncStores(",
    "RealRelayPairProbe(",
    "RealPlStatusProbe(",
    "openRelaySyncClient(",
    "openRelayClient(",
    "executeShellCommand(",
    "GateResultWriter(",
    "pair-authority.json",
)

fun splGateBannedSinkViolations(source: String): List<String> {
    val violations = mutableListOf<String>()
    splGateBannedMarkers.forEach { (pattern, description) ->
        if (pattern.containsMatchIn(source)) violations += "banned $description"
    }
    return violations.distinct()
}

fun splGateDriverViolations(source: String): List<String> {
    if (source.isBlank()) return emptyList()
    val violations = splGateBannedSinkViolations(source).toMutableList()
    splGateRequiredMarkers.forEach { marker ->
        if (!source.contains(marker)) violations += "missing production marker $marker"
    }
    if (!source.contains("openRelaySyncClient") && !source.contains("openRelayClient")) {
        violations += "missing production relay-client opener"
    }
    val decision = source.indexOf("GateInvocation.decide")
    val skip = source.indexOf("GateInvocationDecision.Skip")
    val firstSideEffect = splGateSideEffectMarkers
        .map(source::indexOf)
        .filter { it >= 0 }
        .minOrNull()
    if (decision < 0 || skip < decision || (firstSideEffect != null && skip > firstSideEffect)) {
        violations += "inert action guard does not dominate side effects"
    }
    val authorityDelete = source.indexOf("file.delete()")
    val authorityParse = source.indexOf("parseJson(bytes.toString")
    if (authorityDelete < 0 || authorityParse < authorityDelete) {
        violations += "pair authority is not deleted before parsing"
    }
    return violations.distinct()
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

fun List<IntentFilterTokens>.hasVerifiedPairLink(): Boolean {
    val view = "android.intent.action.VIEW"
    val default = "android.intent.category.DEFAULT"
    val browsable = "android.intent.category.BROWSABLE"
    return any { filter ->
        filter.autoVerify &&
            setOf(view, default, browsable).all { it in filter.names } &&
            filter.data.any { it == IntentDataTokens("https", "go.solstone.app", "/p") }
    }
}

data class ActivityIntentFilterGroup(
    val activityName: String,
    val tokenGroups: List<Set<String>>,
    val exported: Boolean? = null,
    val intentFilters: List<IntentFilterTokens> = emptyList(),
)

fun resolveManifestActivityName(raw: String, packageName: String?): String =
    when {
        raw.startsWith(".") -> (packageName ?: "") + raw
        raw.contains('.') -> raw
        else -> if (packageName.isNullOrEmpty()) raw else "$packageName.$raw"
    }

fun activityIntentFilterGroups(manifestText: String): List<ActivityIntentFilterGroup> {
    val packageName = Regex("""<manifest\b[^>]*\bpackage\s*=\s*["']([^"']+)["']""")
        .find(manifestText)
        ?.groupValues
        ?.get(1)
    val results = mutableListOf<ActivityIntentFilterGroup>()
    // activity-alias is listed first so `<activity\b` cannot swallow it. An alias
    // that carries MAIN+LAUNCHER is a launcher owner: it places an icon.
    val startPattern = Regex("""<(activity-alias|activity)\b""")
    var searchFrom = 0
    while (true) {
        val start = startPattern.find(manifestText, searchFrom) ?: break
        val tagName = start.groupValues[1]
        val gt = manifestText.indexOf('>', start.range.last + 1)
        if (gt < 0) break
        val openTag = manifestText.substring(start.range.first, gt + 1)
        val rawName = Regex("""android:name\s*=\s*["']([^"']+)["']""")
            .find(openTag)
            ?.groupValues
            ?.get(1)
        if (rawName == null) {
            searchFrom = gt + 1
            continue
        }
        val resolved = resolveManifestActivityName(rawName, packageName)
        val exported = Regex("""android:exported\s*=\s*["'](true|false)["']""")
            .find(openTag)
            ?.groupValues
            ?.get(1)
            ?.toBoolean()
        val selfClosing = openTag.trimEnd().endsWith("/>")
        if (selfClosing) {
            results += ActivityIntentFilterGroup(resolved, emptyList(), exported, emptyList())
            searchFrom = gt + 1
            continue
        }
        val closeTag = "</$tagName>"
        val close = manifestText.indexOf(closeTag, gt + 1)
        if (close < 0) {
            results += ActivityIntentFilterGroup(resolved, emptyList(), exported, emptyList())
            searchFrom = gt + 1
            continue
        }
        val body = manifestText.substring(gt + 1, close)
        val filters = intentFilterTokens(body)
        results += ActivityIntentFilterGroup(resolved, filters.map { it.names }, exported, filters)
        searchFrom = close + closeTag.length
    }
    return results
}

fun mainLauncherOwners(manifestText: String): List<String> {
    val main = "android.intent.action.MAIN"
    val launcher = "android.intent.category.LAUNCHER"
    return activityIntentFilterGroups(manifestText)
        .filter { activity -> activity.tokenGroups.any { main in it && launcher in it } }
        .map { it.activityName }
}

fun stripKotlinComments(source: String): String {
    val noBlock = Regex("""/\*.*?\*/""", setOf(RegexOption.DOT_MATCHES_ALL)).replace(source, " ")
    return noBlock.lineSequence().joinToString("\n") { line ->
        val idx = line.indexOf("//")
        if (idx >= 0) line.take(idx) else line
    }
}

fun forbiddenThemeApiViolations(source: String): List<String> {
    val stripped = stripKotlinComments(source)
    val violations = mutableListOf<String>()
    if (stripped.contains("androidx.compose.material.")) {
        violations += "material2 import"
    }
    listOf(
        "dynamicLightColorScheme",
        "dynamicDarkColorScheme",
        "dynamicTonalPalette",
        "MaterialExpressiveTheme",
        "MotionScheme",
    ).forEach { marker ->
        if (stripped.contains(marker)) violations += marker
    }
    if (Regex("""(?m)^\s*(?:import|package)\s+[\w.]*\.state\.""").containsMatchIn(stripped)) {
        violations += "state package"
    }
    if (stripped.contains(".nav.")) violations += "nav package"
    return violations.distinct()
}

fun colorLiteralViolations(source: String, fileName: String): List<String> {
    if (fileName.endsWith("SolstoneColors.kt")) return emptyList()
    val stripped = stripKotlinComments(source)
    val violations = mutableListOf<String>()
    if (Regex("""Color\s*\(\s*0x""").containsMatchIn(stripped)) {
        violations += "packed Color(0x"
    }
    listOf("Color.White", "Color.Black", "Color.Transparent").forEach { named ->
        if (stripped.contains(named)) violations += named
    }
    if (Regex("""Color\s*\(\s*(?!0x)[\d.]""").containsMatchIn(stripped)) {
        violations += "component Color ctor"
    }
    return violations
}

fun phoneShellInsetDoctrineViolations(source: String): List<String> {
    val stripped = stripKotlinComments(source)
    val violations = mutableListOf<String>()
    fun count(token: String) = Regex.fromLiteral(token).findAll(stripped).count()
    if (count("Modifier.windowInsetsPadding(WindowInsets.safeDrawing)") != 0) {
        violations += "pre-1D host safeDrawing pad"
    }
    if (count("contentWindowInsets = WindowInsets(0, 0, 0, 0)") != 0) {
        violations += "zero contentWindowInsets"
    }
    if (count("Modifier.padding(paddingValues)") != 0) {
        violations += "host paddingValues"
    }
    if (stripped.contains("statusBarsPadding()")) {
        violations += "statusBarsPadding()"
    }
    if (count("content(paddingValues)") != 1) {
        violations += "content(paddingValues) count=${count("content(paddingValues)")}"
    }
    if (count("WindowInsets.safeGestures") < 1) {
        violations += "WindowInsets.safeGestures count=${count("WindowInsets.safeGestures")}"
    }
    if (count("WindowInsets.safeDrawing") < 1) {
        violations += "WindowInsets.safeDrawing count=${count("WindowInsets.safeDrawing")}"
    }
    if (!stripped.contains("Modifier.fillMaxSize()")) {
        violations += "missing fillMaxSize"
    }
    return violations
}

data class PhoneBackHandlerDoctrineReport(
    val callSiteCount: Int,
    val violations: List<String>,
)

fun skipKotlinStringLiteral(source: String, index: Int): Int {
    if (index >= source.length) return index
    return when (source[index]) {
        '"' -> {
            if (source.startsWith("\"\"\"", index)) {
                val end = source.indexOf("\"\"\"", index + 3)
                if (end < 0) source.length else end + 3
            } else {
                var i = index + 1
                while (i < source.length) {
                    when (source[i]) {
                        '\\' -> i += 2
                        '"' -> return i + 1
                        else -> i++
                    }
                }
                source.length
            }
        }
        '\'' -> {
            var i = index + 1
            while (i < source.length) {
                when (source[i]) {
                    '\\' -> i += 2
                    '\'' -> return i + 1
                    else -> i++
                }
            }
            source.length
        }
        else -> index
    }
}

fun matchingDelimiter(source: String, openIndex: Int, open: Char, close: Char): Int {
    var depth = 0
    var i = openIndex
    while (i < source.length) {
        val skipped = skipKotlinStringLiteral(source, i)
        if (skipped != i) {
            i = skipped
            continue
        }
        when (source[i]) {
            open -> depth++
            close -> {
                depth--
                if (depth == 0) return i
            }
        }
        i++
    }
    return -1
}

fun phoneBackLadderBodyRange(stripped: String): IntRange? {
    val match = Regex("""fun\s+PhoneBackLadder\b""").find(stripped) ?: return null
    var i = match.range.last + 1
    while (i < stripped.length && stripped[i].isWhitespace()) i++
    if (i >= stripped.length || stripped[i] != '(') return null
    val closeParams = matchingDelimiter(stripped, i, '(', ')')
    if (closeParams < 0) return null
    i = closeParams + 1
    while (i < stripped.length && stripped[i].isWhitespace()) i++
    if (i < stripped.length && stripped[i] == ':') {
        while (i < stripped.length) {
            val skipped = skipKotlinStringLiteral(stripped, i)
            if (skipped != i) {
                i = skipped
                continue
            }
            if (stripped[i] == '{') break
            i++
        }
    }
    while (i < stripped.length && stripped[i].isWhitespace()) i++
    if (i >= stripped.length || stripped[i] != '{') return null
    val bodyOpen = i
    val bodyClose = matchingDelimiter(stripped, bodyOpen, '{', '}')
    if (bodyClose < 0) return null
    return bodyOpen..bodyClose
}

fun braceDepthAt(source: String, bodyOpen: Int, index: Int): Int {
    var depth = 0
    var i = bodyOpen
    while (i < index) {
        val skipped = skipKotlinStringLiteral(source, i)
        if (skipped != i) {
            i = skipped
            continue
        }
        when (source[i]) {
            '{' -> depth++
            '}' -> depth--
        }
        i++
    }
    return depth
}

fun argumentListHasEnabled(stripped: String, parenIndex: Int): Boolean {
    val close = matchingDelimiter(stripped, parenIndex, '(', ')')
    if (close < 0) return false
    val args = stripped.substring(parenIndex + 1, close)
    return Regex("""enabled\s*=""").containsMatchIn(args)
}

fun phoneBackHandlerDoctrineReport(
    mainSources: Map<String, String>,
    moduleSources: Map<String, String>,
): PhoneBackHandlerDoctrineReport {
    val violations = mutableListOf<String>()
    var callSiteCount = 0
    val callSiteRegex = Regex("""\b(?:Predictive)?BackHandler\s*\(""")
    mainSources.forEach { (path, text) ->
        val stripped = stripKotlinComments(text)
        val sites = callSiteRegex.findAll(stripped).toList()
        callSiteCount += sites.size
        val isLadderFile = path.endsWith("PhoneBackLadder.kt")
        val body = if (isLadderFile) phoneBackLadderBodyRange(stripped) else null
        if (isLadderFile && body != null) {
            val bodyText = stripped.substring(body)
            if (Regex("""\b(if|when|for|while)\b""").containsMatchIn(bodyText)) {
                violations += "PhoneBackLadder body contains control keyword"
            }
        }
        sites.forEach { site ->
            val index = site.range.first
            val parenIndex = site.range.last
            if (!argumentListHasEnabled(stripped, parenIndex)) {
                violations += "missing enabled = at $path:$index"
            }
            if (!isLadderFile) {
                violations += "handler outside PhoneBackLadder.kt: $path"
            } else if (body == null) {
                violations += "PhoneBackLadder body not found: $path"
            } else if (index !in body) {
                violations += "handler outside PhoneBackLadder body: $path"
            } else {
                val depth = braceDepthAt(stripped, body.first, index)
                if (depth != 1) {
                    violations += "nested handler at $path:$index depth=$depth"
                }
            }
        }
    }
    if (callSiteCount < 3) {
        violations += "callSites=$callSiteCount < 3"
    }
    moduleSources.forEach { (path, text) ->
        val stripped = stripKotlinComments(text)
        if (stripped.contains("onBackPressed") || stripped.contains("KEYCODE_BACK")) {
            violations += "legacy back API in $path"
        }
    }
    return PhoneBackHandlerDoctrineReport(callSiteCount, violations)
}

fun walkPhoneKotlin(root: File): Map<String, String> {
    if (!root.exists()) return emptyMap()
    return root.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filterNot { file -> file.toPath().any { part -> part.toString() == "build" } }
        .associate { it.relativeTo(rootProject.projectDir).path to it.readText() }
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

tasks.register("checkSplGateDriver") {
    group = "verification"
    description = "Checks the SPL gate driver for inertness, production composition, and secret-safe sinks."

    doLast {
        val sourceDir = rootProject.file("apps/phone/src/androidTestReal")
        val allSources = if (sourceDir.exists()) {
            sourceDir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()
        } else {
            emptyList()
        }
        if (allSources.isEmpty()) return@doLast
        val violations = mutableListOf<String>()
        allSources.forEach { source ->
            splGateBannedSinkViolations(source.readText()).forEach { violation ->
                violations += "${source.relativeTo(rootProject.projectDir)}: $violation"
            }
        }
        val gateSources = allSources.filter { it.readText().contains("GateInvocation") }
        if (gateSources.isNotEmpty()) {
            val gateGraph = (gateSources + allSources.filterNot(gateSources::contains))
                .joinToString("\n") { it.readText() }
            splGateDriverViolations(gateGraph)
                .filterNot { it.startsWith("banned ") }
                .forEach { violations += it }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("SPL gate driver guard failed:\n${violations.sorted().joinToString("\n")}")
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

tasks.register("splGateDriverGuardSelfTest") {
    group = "verification"
    description = "Exercises SPL gate driver source predicates against synthetic safe and unsafe inputs."

    doLast {
        val safe = """
            val decision = GateInvocation.decide(extras)
            if (decision is GateInvocationDecision.Skip) return
            val stores = syncStores(context)
            val status = RealPlStatusProbe(stores.endpointStore, stores.credentialStore, stores.identityStore)
            val client = openRelaySyncClient(origin, instanceId, token, credential)
            val reconciler = SegmentReconciler(client)
            val activity = ObserverActivity()
            activity.onScannedPairLink(pairLink)
            activity.syncNow()
            activity.listEvidence()
            val recorded = "audio.m4a"
            val uploaded = QueueState.UPLOADED
            class SplIntegrationGateDriverTest
            val authority = "pair-authority.json"
            val result = "action-result.json"
            val progress = "action-progress.json"
            file.delete()
            parseJson(bytes.toString())
        """.trimIndent()
        check(splGateDriverViolations("").isEmpty())
        check(splGateDriverViolations(safe).isEmpty())
        val cleanNonGate = "class ExistingRealFlavorRuntimeTest"
        check(splGateBannedSinkViolations(cleanNonGate).isEmpty())
        check(splGateBannedSinkViolations("$cleanNonGate\nLog.i(\"tag\", \"message\")").isNotEmpty())
        check(splGateBannedSinkViolations("$cleanNonGate\n\"pair=\$pairLink\"").isNotEmpty())
        listOf(
            "MockTransport",
            "FakeClient",
            "LoopbackSession",
            "StubRelay",
            "RelayPairLink.toString",
            "Log.i",
            "stackTraceToString",
            "\"pair=\$pairLink\"",
            "RealSplGateDriverTest",
            "g1-pair-status-register",
            "files/spl-gate/result.json",
            "NetworkDenialController",
            "svc wifi disable",
            "GateConnectivity",
        ).forEach { banned ->
            check(splGateDriverViolations("$safe\n$banned").isNotEmpty()) { "guard missed $banned" }
        }
        splGateRequiredMarkers.forEach { marker ->
            check(splGateDriverViolations(safe.replace(marker, "removed")).isNotEmpty()) {
                "guard missed required marker $marker"
            }
        }
        check(splGateDriverViolations(safe.replace("openRelaySyncClient", "removed")).isNotEmpty())
        val sideEffectFirst = "executeShellCommand(\"fixed\")\n$safe"
        check("inert action guard does not dominate side effects" in splGateDriverViolations(sideEffectFirst))
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

tasks.register("phoneLauncherCountGuardSelfTest") {
    group = "verification"
    description = "Exercises MAIN+LAUNCHER owner parsing for the phone launcher-count guard."
    doLast {
        val main = "android.intent.action.MAIN"
        val launcher = "android.intent.category.LAUNCHER"
        val observer = "app.solstone.observer.scaffold.ObserverActivity"
        val one = mainLauncherOwners(
            """<manifest package="app.solstone.observer.phone">""" +
                """<activity android:name="$observer">""" +
                """<intent-filter><action android:name="$main" /><category android:name="$launcher" /></intent-filter>""" +
                """</activity></manifest>""",
        )
        check(one == listOf(observer))

        val two = mainLauncherOwners(
            """<manifest package="app.solstone.observer.phone">""" +
                """<activity android:name="$observer">""" +
                """<intent-filter><action android:name="$main" /><category android:name="$launcher" /></intent-filter>""" +
                """</activity>""" +
                """<activity android:name=".probe.ProbeIndexActivity">""" +
                """<intent-filter><action android:name="$main" /><category android:name="$launcher" /></intent-filter>""" +
                """</activity></manifest>""",
        )
        check(
            two.toSet() == setOf(
                observer,
                "app.solstone.observer.phone.probe.ProbeIndexActivity",
            ),
        )

        val selfClosingThenLauncher =
            """<manifest package="app.solstone.observer.phone">""" +
                """<activity android:name=".PhoneShellActivity" android:exported="false" />""" +
                """<activity android:name="$observer">""" +
                """<intent-filter><action android:name="$main" /><category android:name="$launcher" /></intent-filter>""" +
                """</activity></manifest>"""
        val selfClosingGroups = activityIntentFilterGroups(selfClosingThenLauncher)
        check(
            selfClosingGroups.any {
                it.activityName == "app.solstone.observer.phone.PhoneShellActivity" &&
                    it.tokenGroups.isEmpty()
            },
        )
        check(mainLauncherOwners(selfClosingThenLauncher) == listOf(observer))

        val alias = "app.solstone.observer.phone.Alias"
        val aliasOwnsLauncher = mainLauncherOwners(
            """<manifest package="app.solstone.observer.phone">""" +
                """<activity-alias android:name=".Alias" android:targetActivity="$observer">""" +
                """<intent-filter><action android:name="$main" /><category android:name="$launcher" /></intent-filter>""" +
                """</activity-alias></manifest>""",
        )
        check(aliasOwnsLauncher == listOf(alias))

        val aliasSilentBesideLauncher = mainLauncherOwners(
            """<manifest package="app.solstone.observer.phone">""" +
                """<activity-alias android:name=".Alias" android:targetActivity="$observer" />""" +
                """<activity android:name="$observer">""" +
                """<intent-filter><action android:name="$main" /><category android:name="$launcher" /></intent-filter>""" +
                """</activity></manifest>""",
        )
        check(aliasSilentBesideLauncher == listOf(observer))
        check(alias !in aliasSilentBesideLauncher)

        val aliasAndActivity = mainLauncherOwners(
            """<manifest package="app.solstone.observer.phone">""" +
                """<activity-alias android:name=".Alias" android:targetActivity="$observer">""" +
                """<intent-filter><action android:name="$main" /><category android:name="$launcher" /></intent-filter>""" +
                """</activity-alias>""" +
                """<activity android:name="$observer">""" +
                """<intent-filter><action android:name="$main" /><category android:name="$launcher" /></intent-filter>""" +
                """</activity></manifest>""",
        )
        check(aliasAndActivity.toSet() == setOf(alias, observer))

        val component = mainLauncherOwners(
            """<manifest package="app.solstone.observer.phone">""" +
                """<activity android:name="androidx.activity.ComponentActivity" android:exported="true" />""" +
                """</manifest>""",
        )
        check(component.isEmpty())

        val three = mainLauncherOwners(
            """<manifest package="app.solstone.observer.phone">""" +
                """<activity android:name="$observer"><intent-filter><action android:name="$main" /><category android:name="$launcher" /></intent-filter></activity>""" +
                """<activity android:name=".probe.ProbeIndexActivity"><intent-filter><action android:name="$main" /><category android:name="$launcher" /></intent-filter></activity>""" +
                """<activity android:name=".Other"><intent-filter><action android:name="$main" /><category android:name="$launcher" /></intent-filter></activity>""" +
                """</manifest>""",
        )
        check(three.size == 3)

        val wrongOwner = mainLauncherOwners(
            """<manifest package="app.solstone.observer.phone">""" +
                """<activity android:name=".Other"><intent-filter><action android:name="$main" /><category android:name="$launcher" /></intent-filter></activity>""" +
                """</manifest>""",
        )
        check(wrongOwner == listOf("app.solstone.observer.phone.Other"))
        check(observer !in wrongOwner)

        val split = mainLauncherOwners(
            """<manifest package="app.solstone.observer.phone">""" +
                """<activity android:name="$observer">""" +
                """<intent-filter><action android:name="$main" /></intent-filter>""" +
                """<intent-filter><category android:name="$launcher" /></intent-filter>""" +
                """</activity></manifest>""",
        )
        check(split.isEmpty())

        val nested = mainLauncherOwners(
            """<manifest package="app.solstone.observer.phone">""" +
                """<activity android:name="$observer">""" +
                """<meta-data android:name="x" android:value="y" />""" +
                """<intent-filter><action android:name="$main" /><category android:name="$launcher" /></intent-filter>""" +
                """</activity></manifest>""",
        )
        check(nested == listOf(observer))

        val exportedTrue = activityIntentFilterGroups(
            """<manifest package="app.solstone.observer.phone">""" +
                """<activity android:name=".PhoneShellActivity" android:exported="true">""" +
                """<intent-filter><action android:name="$main" /><category android:name="$launcher" /></intent-filter>""" +
                """</activity></manifest>""",
        )
        check(exportedTrue.single().exported == true)
        check(exportedTrue.single().intentFilters.single().names == setOf(main, launcher))

        val exportedFalse = activityIntentFilterGroups(
            """<manifest package="app.solstone.observer.phone">""" +
                """<activity android:name=".PhoneShellActivity" android:exported="false" />""" +
                """</manifest>""",
        )
        check(exportedFalse.single().exported == false)

        val exportedAbsent = activityIntentFilterGroups(
            """<manifest package="app.solstone.observer.phone">""" +
                """<activity android:name=".PhoneShellActivity" />""" +
                """</manifest>""",
        )
        check(exportedAbsent.single().exported == null)
    }
}

tasks.register("forbiddenThemeApiGuardSelfTest") {
    group = "verification"
    description = "Exercises forbidden theme-API predicates, including the M2 trailing-dot trap."
    doLast {
        check(forbiddenThemeApiViolations("import androidx.compose.material.Text").isNotEmpty())
        check(forbiddenThemeApiViolations("import androidx.compose.material3.Text").isEmpty())
        check(
            forbiddenThemeApiViolations(
                "import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo",
            ).isEmpty(),
        )
        check(forbiddenThemeApiViolations("dynamicLightColorScheme()").isNotEmpty())
        check(forbiddenThemeApiViolations("lightColorScheme()").isEmpty())
        check(forbiddenThemeApiViolations("MaterialExpressiveTheme").isNotEmpty())
        check(forbiddenThemeApiViolations("MotionScheme").isNotEmpty())
        check(
            forbiddenThemeApiViolations(
                "import app.solstone.observer.formfactor.phone.state.Foo",
            ).isNotEmpty(),
        )
        check(
            forbiddenThemeApiViolations(
                "import app.solstone.observer.formfactor.phone.nav.Bar",
            ).isNotEmpty(),
        )
        check(
            forbiddenThemeApiViolations(
                "import app.solstone.observer.formfactor.phone.PhoneShell",
            ).isEmpty(),
        )
        check(
            forbiddenThemeApiViolations(
                "package app.solstone.observer.formfactor.phone.state.models",
            ).isNotEmpty(),
        )
        check(
            forbiddenThemeApiViolations(
                "val label = status.state.name",
            ).isEmpty(),
        )
    }
}

tasks.register("colorLiteralGuardSelfTest") {
    group = "verification"
    description = "Exercises colour-construction location predicates."
    doLast {
        check(
            colorLiteralViolations("val x = Color(0xFFE8913A)", "PhoneShell.kt").isNotEmpty(),
        )
        check(
            colorLiteralViolations("val x = Color(0xFFE8913A)", "SolstoneColors.kt").isEmpty(),
        )
        check(
            colorLiteralViolations("Color.White", "SolstoneColorSchemes.kt").isNotEmpty(),
        )
        check(
            colorLiteralViolations("Color.Black", "SolstoneColorSchemes.kt").isNotEmpty(),
        )
        check(
            colorLiteralViolations("Color.Transparent", "SolstoneColorSchemes.kt").isNotEmpty(),
        )
        check(
            colorLiteralViolations("Color(1f, 1f, 1f)", "SolstoneColorSchemes.kt").isNotEmpty(),
        )
        check(
            colorLiteralViolations("Color(255, 255, 255)", "SolstoneColorSchemes.kt").isNotEmpty(),
        )
        check(
            colorLiteralViolations("SolstoneColors.surfaceWhite", "SolstoneColorSchemes.kt").isEmpty(),
        )
        check(
            colorLiteralViolations("MINIMUM_TOUCH_TARGET_DP = 48", "PhoneMetrics.kt").isEmpty(),
        )
    }
}

tasks.register("phoneShellInsetDoctrineGuardSelfTest") {
    group = "verification"
    description = "Exercises PhoneShell inset-doctrine snippet matching."
    doLast {
        val good = """
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets.safeDrawing
                    .union(WindowInsets.safeGestures)
                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            ) { paddingValues ->
                Box(Modifier.fillMaxSize()) { content(paddingValues) }
            }
        """.trimIndent()
        check(phoneShellInsetDoctrineViolations(good).isEmpty())
        val pre1d = """
            Scaffold(
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) { paddingValues ->
                Box(Modifier.padding(paddingValues)) { }
            }
        """.trimIndent()
        check(phoneShellInsetDoctrineViolations(pre1d).isNotEmpty())
        check(phoneShellInsetDoctrineViolations(good + "\nstatusBarsPadding()").isNotEmpty())
        check(phoneShellInsetDoctrineViolations("").isNotEmpty())
        check(phoneShellInsetDoctrineViolations(good + "\n// statusBarsPadding()").isEmpty())
    }
}

tasks.register("checkForbiddenThemeApis") {
    group = "verification"
    description = "Fails if forbidden theme APIs, colour construction, or state/nav packages appear."
    doLast {
        val violations = mutableListOf<String>()
        val roots = listOf(
            rootProject.file("formfactor/phone/src"),
            rootProject.file("apps/phone/src/main"),
        )
        roots.filter { it.exists() }.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { file -> file.toPath().any { part -> part.toString() == "build" } }
                .forEach { file ->
                    val relative = file.relativeTo(rootProject.projectDir).path
                    val text = file.readText()
                    forbiddenThemeApiViolations(text).forEach { violation ->
                        violations += "$relative: $violation"
                    }
                    if (relative.startsWith("formfactor/phone/src/")) {
                        colorLiteralViolations(text, file.name).forEach { violation ->
                            violations += "$relative: $violation"
                        }
                    }
                    val pathParts = file.toPath().map { it.toString() }
                    if ("state" in pathParts || "nav" in pathParts) {
                        violations += "$relative: state/ or nav/ path"
                    }
                    if (
                        relative.startsWith("formfactor/phone/src/main/") &&
                        file.name != "PhoneMetrics.kt" &&
                        stripKotlinComments(text).contains("MINIMUM_TOUCH_TARGET_DP")
                    ) {
                        violations += "$relative: MINIMUM_TOUCH_TARGET_DP outside PhoneMetrics"
                    }
                    if (
                        relative.startsWith("formfactor/phone/src/") &&
                        stripKotlinComments(text).contains("announceForAccessibility")
                    ) {
                        violations += "$relative: announceForAccessibility"
                    }
                }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Forbidden theme API guard failed:\n${violations.sorted().joinToString("\n")}",
            )
        }
    }
}

tasks.register("checkPhoneShellInsetDoctrine") {
    group = "verification"
    description = "Checks PhoneShell.kt for the single inset-mechanism snippets."
    doLast {
        val file = rootProject.file(
            "formfactor/phone/src/main/kotlin/app/solstone/observer/formfactor/phone/PhoneShell.kt",
        )
        val violations = phoneShellInsetDoctrineViolations(file.readText())
        if (violations.isNotEmpty()) {
            throw GradleException(
                "PhoneShell inset doctrine failed:\n${violations.joinToString("\n")}",
            )
        }
    }
}

tasks.register("checkPhoneBackHandlerDoctrine") {
    group = "verification"
    description = "Fails if phone back-handler doctrine is violated."
    doLast {
        val mainSources = walkPhoneKotlin(rootProject.file("formfactor/phone/src/main"))
        val moduleSources = walkPhoneKotlin(rootProject.file("formfactor/phone/src"))
        val report = phoneBackHandlerDoctrineReport(mainSources, moduleSources)
        logger.lifecycle("phone back-handler doctrine: callSites=${report.callSiteCount}")
        if (report.violations.isNotEmpty()) {
            throw GradleException(
                "Phone back-handler doctrine guard failed:\n${report.violations.joinToString("\n")}",
            )
        }
    }
}

tasks.register("phoneBackHandlerDoctrineGuardSelfTest") {
    group = "verification"
    description = "Exercises phone back-handler doctrine predicates against synthetic inputs."
    doLast {
        val ladderPath =
            "formfactor/phone/src/main/kotlin/app/solstone/observer/formfactor/phone/PhoneBackLadder.kt"
        val goodLadder = """
            @Composable
            fun PhoneBackLadder(
                paneStates: PaneStates,
                detailStack: PhoneRouteStack,
                widthClass: WidthClass,
                onClosePane: (PhonePane) -> Unit,
                onPopDetail: () -> Unit,
            ) {
                val outcome = resolveBack(paneStates, detailStack, widthClass)
                PredictiveBackHandler(enabled = outcome.closesPane(PhonePane.SHELF)) { progress ->
                    progress.collect()
                    onClosePane(PhonePane.SHELF)
                }
                PredictiveBackHandler(enabled = outcome.closesPane(PhonePane.JOURNAL)) { progress ->
                    progress.collect()
                    onClosePane(PhonePane.JOURNAL)
                }
                PredictiveBackHandler(enabled = outcome.closesPane(PhonePane.STATUS)) { progress ->
                    progress.collect()
                    onClosePane(PhonePane.STATUS)
                }
                BackHandler(enabled = outcome.popsDetail) {
                    onPopDetail()
                }
            }
        """.trimIndent()
        val good = phoneBackHandlerDoctrineReport(mapOf(ladderPath to goodLadder), emptyMap())
        check(good.violations.isEmpty())
        check(good.callSiteCount == 4)

        val productionMirror = """
            @Composable
            fun PhoneBackLadder(
                paneStates: PaneStates,
                detailStack: PhoneRouteStack,
                widthClass: WidthClass,
                onClosePane: (PhonePane) -> Unit,
                onPopDetail: () -> Unit,
            ) {
                val outcome = resolveBack(paneStates, detailStack, widthClass)
                PredictiveBackHandler(enabled = outcome.closesPane(PhonePane.JOURNAL)) { progress ->
                    progress.collect()
                    onClosePane(PhonePane.JOURNAL)
                }
                PredictiveBackHandler(enabled = outcome.closesPane(PhonePane.STATUS)) { progress ->
                    progress.collect()
                    onClosePane(PhonePane.STATUS)
                }
                BackHandler(enabled = outcome.popsDetail) {
                    onPopDetail()
                }
            }
        """.trimIndent()
        val productionReport = phoneBackHandlerDoctrineReport(
            mapOf(ladderPath to productionMirror),
            emptyMap(),
        )
        check(productionReport.violations.isEmpty())
        check(productionReport.callSiteCount == 3)

        val twoRung = productionMirror.replace(
            """    PredictiveBackHandler(enabled = outcome.closesPane(PhonePane.STATUS)) { progress ->
        progress.collect()
        onClosePane(PhonePane.STATUS)
    }
""",
            "",
        )
        val twoRungReport = phoneBackHandlerDoctrineReport(mapOf(ladderPath to twoRung), emptyMap())
        check(twoRungReport.callSiteCount == 2)
        check(twoRungReport.violations.any { it.startsWith("callSites=2 < 3") })

        val missingEnabled = goodLadder.replace(
            "BackHandler(enabled = outcome.popsDetail)",
            "BackHandler()",
        )
        check(
            phoneBackHandlerDoctrineReport(mapOf(ladderPath to missingEnabled), emptyMap())
                .violations.any { it.startsWith("missing enabled =") },
        )

        val ifWrapped = """
            fun PhoneBackLadder() {
                PredictiveBackHandler(enabled = true) {}
                PredictiveBackHandler(enabled = true) {}
                PredictiveBackHandler(enabled = true) {}
                if (x) {
                    BackHandler(enabled = true) {}
                }
            }
        """.trimIndent()
        val ifReport = phoneBackHandlerDoctrineReport(mapOf(ladderPath to ifWrapped), emptyMap())
        check(ifReport.violations.any { it.contains("control keyword") })
        check(ifReport.violations.any { it.contains("nested handler") })

        val secondFile = phoneBackHandlerDoctrineReport(
            mapOf(
                ladderPath to goodLadder,
                "Other.kt" to "BackHandler(enabled = true) {}",
            ),
            emptyMap(),
        )
        check(secondFile.violations.any { it.startsWith("handler outside PhoneBackLadder.kt:") })

        val empty = phoneBackHandlerDoctrineReport(emptyMap(), emptyMap())
        check(empty.callSiteCount == 0)
        check(empty.violations.any { it.startsWith("callSites=0 < 3") })

        val commented = goodLadder + "\n// BackHandler(enabled = true) {}\n"
        val commentedReport = phoneBackHandlerDoctrineReport(mapOf(ladderPath to commented), emptyMap())
        check(commentedReport.violations.isEmpty())
        check(commentedReport.callSiteCount == 4)

        val fixturePath = "formfactor/phone/src/test/resources/phone-back-handler-doctrine-violation.txt"
        val fixture = rootProject.file(fixturePath)
        check(fixture.exists()) { "missing phone-back-handler-doctrine fixture: $fixturePath" }
        val fixtureText = fixture.readText()
        val wrapped = """
            fun PhoneBackLadder() {
                PredictiveBackHandler(enabled = true) {}
                PredictiveBackHandler(enabled = true) {}
                PredictiveBackHandler(enabled = true) {}
                BackHandler(enabled = true) {}
            $fixtureText
            }
        """.trimIndent()
        val conditionalReport = phoneBackHandlerDoctrineReport(
            mapOf(ladderPath to wrapped),
            emptyMap(),
        )
        check(conditionalReport.callSiteCount >= 4)
        check(conditionalReport.violations.none { it.startsWith("handler outside PhoneBackLadder.kt:") })
        check(
            conditionalReport.violations.any { it.contains("control keyword") } &&
                conditionalReport.violations.any { it.contains("nested handler") },
        )
        val locationReport = phoneBackHandlerDoctrineReport(
            mapOf("PaneContent.kt" to fixtureText),
            emptyMap(),
        )
        check(locationReport.violations.any { it.startsWith("handler outside PhoneBackLadder.kt:") })

        val legacy = phoneBackHandlerDoctrineReport(
            mapOf(ladderPath to goodLadder),
            mapOf("Foo.kt" to "override fun onBackPressed() {}"),
        )
        check(legacy.violations.any { it.startsWith("legacy back API in ") })

        val commentedLegacy = phoneBackHandlerDoctrineReport(
            mapOf(ladderPath to goodLadder),
            mapOf("Foo.kt" to "// onBackPressed\n// KEYCODE_BACK"),
        )
        check(commentedLegacy.violations.none { it.startsWith("legacy back API in ") })

        val runNested = goodLadder.replace(
            "    BackHandler(enabled = outcome.popsDetail) {\n        onPopDetail()\n    }\n}",
            "    BackHandler(enabled = outcome.popsDetail) {\n        onPopDetail()\n    }\n    run { BackHandler(enabled = true) {} }\n}",
        )
        val runReport = phoneBackHandlerDoctrineReport(mapOf(ladderPath to runNested), emptyMap())
        check(runReport.callSiteCount == 5)
        check(runReport.violations.any { it.contains("nested handler") && it.contains("depth=2") })

        val sibling = goodLadder + "\nfun Other() { BackHandler(enabled = true) {} }\n"
        val siblingReport = phoneBackHandlerDoctrineReport(mapOf(ladderPath to sibling), emptyMap())
        check(siblingReport.violations.any { it.startsWith("handler outside PhoneBackLadder body:") })
    }
}

tasks.register("checkNoNavigationLibrary") {
    group = "verification"
    description = "Fails if a Navigation Compose or Navigation3 dependency is resolved."

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
                                val denied = deniedNavigationCoordinate(module.group, module.module)
                                if (denied != null) {
                                    violations += "coordinate ${module.group}:${module.module}:${module.version} entered through ${project.path}:${configuration.name}"
                                }
                            }
                        }
                    }
                }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Navigation library guard failed:\n${violations.distinct().sorted().joinToString("\n")}")
        }
    }
}

tasks.register("navigationLibraryGuardSelfTest") {
    group = "verification"
    description = "Exercises navigation-library coordinate matching against synthetic coordinates."
    doLast {
        check(deniedNavigationCoordinate("androidx.navigation", "navigation-compose") != null)
        check(deniedNavigationCoordinate("androidx.navigation3", "navigation3-ui") != null)
        check(deniedNavigationCoordinate("androidx.navigation.compose", "navigation-compose") != null)
        check(deniedNavigationCoordinate("androidx.navigationevent", "navigationevent") == null)
        check(deniedNavigationCoordinate("androidx.navigationevent", "navigationevent-android") == null)
        check(deniedNavigationCoordinate("androidx.navigationevent", "navigationevent-compose") == null)
        check(deniedNavigationCoordinate("androidx.navigationevent", "navigationevent-compose-android") == null)
        check(
            deniedNavigationCoordinate("androidx.compose.material3.adaptive", "adaptive-navigation") == null,
        )
        check(deniedNavigationCoordinate("androidx.compose.material3.adaptive", "adaptive") == null)
    }
}

tasks.named("check") {
    dependsOn(
        "checkPrivacyDeps",
        "checkCorePurity",
        "checkSplGateDriver",
        "privacyGuardSelfTest",
        "purityGuardSelfTest",
        "splGateDriverGuardSelfTest",
        "manifestGuardSelfTest",
        "launcherHomeGuardSelfTest",
        "appLinksGuardSelfTest",
        "phoneLauncherCountGuardSelfTest",
        "forbiddenThemeApiGuardSelfTest",
        "colorLiteralGuardSelfTest",
        "phoneShellInsetDoctrineGuardSelfTest",
        "checkForbiddenThemeApis",
        "checkPhoneShellInsetDoctrine",
        "checkPhoneBackHandlerDoctrine",
        "phoneBackHandlerDoctrineGuardSelfTest",
        "checkNoNavigationLibrary",
        "navigationLibraryGuardSelfTest",
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

fun Project.registerPhoneLauncherCountManifestCheck() {
    tasks.register("checkPhoneLauncherCountManifest") {
        group = "verification"
        description = "Checks realDebug and realRelease merged manifests for MAIN+LAUNCHER owners."
        dependsOn("processRealDebugManifest", "processRealReleaseManifest")
        doLast {
            val debugManifest = layout.buildDirectory
                .file("intermediates/merged_manifests/realDebug/processRealDebugManifest/AndroidManifest.xml")
                .get()
                .asFile
            val releaseManifest = layout.buildDirectory
                .file(
                    "intermediates/merged_manifests/realRelease/processRealReleaseManifest/AndroidManifest.xml",
                )
                .get()
                .asFile
            if (!debugManifest.exists()) {
                throw GradleException(
                    "Merged manifest not found: ${debugManifest.relativeTo(rootProject.projectDir)}",
                )
            }
            if (!releaseManifest.exists()) {
                throw GradleException(
                    "Merged manifest not found: ${releaseManifest.relativeTo(rootProject.projectDir)}",
                )
            }
            val shell = "app.solstone.observer.phone.PhoneShellActivity"
            val probe = "app.solstone.observer.phone.probe.ProbeIndexActivity"
            val failures = mutableListOf<String>()
            val releaseOwners = mainLauncherOwners(releaseManifest.readText())
            if (releaseOwners != listOf(shell)) {
                failures += "realRelease MAIN+LAUNCHER owners=$releaseOwners expected=[$shell]"
            }
            val debugOwners = mainLauncherOwners(debugManifest.readText()).toSet()
            val expectedDebug = setOf(shell, probe)
            if (debugOwners != expectedDebug) {
                failures += "realDebug MAIN+LAUNCHER owners=$debugOwners expected=$expectedDebug"
            }
            if (failures.isNotEmpty()) {
                throw GradleException(
                    "${project.path} launcher-count manifest check failed:\n${failures.joinToString("\n")}",
                )
            }
        }
    }
}

fun Project.registerReleaseAppLinksManifestCheck() {
    tasks.register("checkRealReleaseAppLinksManifest") {
        group = "verification"
        description = "Checks the realRelease merged manifest for the verified pair App Link and its exported owner."
        dependsOn("processRealReleaseManifest")
        doLast {
            val manifest = layout.buildDirectory
                .file("intermediates/merged_manifests/realRelease/processRealReleaseManifest/AndroidManifest.xml")
                .get()
                .asFile
            if (!manifest.exists()) {
                throw GradleException("Merged manifest not found: ${manifest.relativeTo(rootProject.projectDir)}")
            }
            val owner = "app.solstone.observer.scaffold.ObserverActivity"
            val ownerGroup = activityIntentFilterGroups(manifest.readText())
                .firstOrNull { it.activityName == owner }
            val failures = mutableListOf<String>()
            if (ownerGroup == null) {
                failures += "missing activity $owner"
            } else {
                if (ownerGroup.exported != true) {
                    failures += "$owner exported=${ownerGroup.exported} expected=true"
                }
                if (!ownerGroup.intentFilters.hasVerifiedPairLink()) {
                    failures += "$owner missing verified https://go.solstone.app/p VIEW intent-filter"
                }
            }
            if (failures.isNotEmpty()) {
                throw GradleException(
                    "${project.path} realRelease App Links manifest check failed:\n${failures.joinToString("\n")}",
                )
            }
        }
    }
}

fun Project.registerPhoneShellExportManifestCheck() {
    tasks.register("checkPhoneShellExportManifest") {
        group = "verification"
        description = "Checks realDebug and realRelease merged manifests for PhoneShellActivity's exported flag."
        dependsOn("processRealDebugManifest", "processRealReleaseManifest")
        doLast {
            val shell = "app.solstone.observer.phone.PhoneShellActivity"
            val debugManifest = layout.buildDirectory
                .file("intermediates/merged_manifests/realDebug/processRealDebugManifest/AndroidManifest.xml")
                .get()
                .asFile
            val releaseManifest = layout.buildDirectory
                .file("intermediates/merged_manifests/realRelease/processRealReleaseManifest/AndroidManifest.xml")
                .get()
                .asFile
            val variants = listOf("realDebug" to debugManifest, "realRelease" to releaseManifest)
            variants.forEach { (_, manifest) ->
                if (!manifest.exists()) {
                    throw GradleException(
                        "Merged manifest not found: ${manifest.relativeTo(rootProject.projectDir)}",
                    )
                }
            }
            val failures = mutableListOf<String>()
            variants.forEach { (variant, manifest) ->
                val group = activityIntentFilterGroups(manifest.readText())
                    .firstOrNull { it.activityName == shell }
                if (group == null) {
                    failures += "$variant missing activity $shell"
                } else if (group.exported != true) {
                    failures += "$variant $shell exported=${group.exported} expected=true"
                }
            }
            if (failures.isNotEmpty()) {
                throw GradleException(
                    "${project.path} phone shell export manifest check failed:\n${failures.joinToString("\n")}",
                )
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
            val present = intentFilterTokens(manifest.readText()).hasVerifiedPairLink()
            if (!present) {
                throw GradleException("${project.path} realDebug App Links manifest check failed:\nmissing verified https://go.solstone.app/p VIEW intent-filter")
            }
        }
    }
}

fun Project.registerOnBackInvokedCallbackManifestCheck() {
    tasks.register("checkRealDebugOnBackInvokedCallbackManifest") {
        group = "verification"
        description = "Checks the realDebug merged manifest for enableOnBackInvokedCallback."
        dependsOn("processRealDebugManifest")
        doLast {
            val manifest = layout.buildDirectory
                .file("intermediates/merged_manifests/realDebug/processRealDebugManifest/AndroidManifest.xml")
                .get()
                .asFile
            if (!manifest.exists()) {
                throw GradleException("Merged manifest not found: ${manifest.relativeTo(rootProject.projectDir)}")
            }
            val application = Regex("""<application\b[^>]*>""", setOf(RegexOption.DOT_MATCHES_ALL))
                .find(manifest.readText())
                ?.value
                .orEmpty()
            if (!application.contains("""android:enableOnBackInvokedCallback="true"""")) {
                throw GradleException(
                    "${project.path} realDebug OnBackInvokedCallback manifest check failed:\nmissing android:enableOnBackInvokedCallback=\"true\" on <application>",
                )
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
    registerReleaseAppLinksManifestCheck()
    registerPhoneLauncherCountManifestCheck()
    registerPhoneShellExportManifestCheck()
    registerOnBackInvokedCallbackManifestCheck()
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
