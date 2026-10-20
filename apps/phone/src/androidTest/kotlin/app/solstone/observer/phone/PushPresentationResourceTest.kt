// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solstone.core.pl.parseJson
import app.solstone.core.push.parsePushCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import solstone.i18n.Select
import java.math.BigDecimal
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PushPresentationResourceTest {

    private val targetContext by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext
    }

    private val testContext by lazy {
        InstrumentationRegistry.getInstrumentation().context
    }

    private val catalog by lazy {
        val irJson = targetContext.assets.open("ir.json").bufferedReader().use { it.readText() }
        parsePushCatalog(irJson)
    }

    private val renderer by lazy {
        PushPresentationRenderer(targetContext, catalog)
    }

    @Test
    fun casesJsonGoldensMatchRenderer() {
        val casesJson = testContext.assets.open("cases.json").bufferedReader().use { it.readText() }
        val root = parseJson(casesJson) as Map<*, *>
        val cases = root["cases"] as List<*>

        for (c in cases) {
            val caseMap = c as Map<*, *>
            val messageId = caseMap["message_id"] as String
            val inputs = caseMap["inputs"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val expected = (caseMap["expected"] as? String) ?: (caseMap["logical_expected"] as? String) ?: continue

            val presentation = mapOf(
                "body" to mapOf(
                    "v" to BigDecimal.ONE,
                    "catalog_epoch" to BigDecimal.ONE,
                    "code" to messageId,
                    "args" to inputs,
                    "fallback_en" to "SENTINEL_FALLBACK",
                ),
            )
            val (_, rendered) = renderer.renderPresentation("LEGACY_T", "LEGACY_B", presentation)
            assertEquals("Message $messageId mismatch", expected, rendered)
        }
    }

    @Test
    fun cardinalQuantitiesUngroupedAndBidiIsolates() {
        for (q in listOf(1, 2, 10000)) {
            val pres = mapOf(
                "body" to mapOf(
                    "v" to BigDecimal.ONE,
                    "catalog_epoch" to BigDecimal.ONE,
                    "code" to "push.proof.count",
                    "args" to mapOf("count" to BigDecimal(q)),
                    "fallback_en" to "SENTINEL_FALLBACK",
                ),
            )
            val (_, rendered) = renderer.renderPresentation("LEGACY_T", "LEGACY_B", pres)
            val ungroupedDigits = Select.formatIntegerUngrouped(q.toLong())
            val isolateSpan = "\u2068$ungroupedDigits\u2069"
            assertTrue("Expected isolate span '$isolateSpan' in '$rendered'", rendered.contains(isolateSpan))
            if (q == 10000) {
                assertTrue("Expected ungrouped 10000 in '$rendered'", rendered.contains("10000"))
                assertFalse("Grouped 10,000 forbidden in '$rendered'", rendered.contains("10,000"))
            }
        }
    }

    @Test
    fun unknownEnumFallsBackToDeclaredDefaultResource() {
        val casesJson = testContext.assets.open("cases.json").bufferedReader().use { it.readText() }
        val root = parseJson(casesJson) as Map<*, *>
        val cases = root["cases"] as List<*>
        val waitingCase = cases.map { it as Map<*, *> }.first { it["message_id"] == "push.proof.state" }
        val expectedWaiting = (waitingCase["expected"] as? String) ?: (waitingCase["logical_expected"] as String)

        val pres = mapOf(
            "body" to mapOf(
                "v" to BigDecimal.ONE,
                "catalog_epoch" to BigDecimal.ONE,
                "code" to "push.proof.state",
                "args" to mapOf("state" to "nope"),
                "fallback_en" to "SENTINEL_FALLBACK",
            ),
        )
        val (_, rendered) = renderer.renderPresentation("LEGACY_T", "LEGACY_B", pres)
        assertEquals(expectedWaiting, rendered)
    }

    @Test
    fun invalidAndUnknownInputsReturnFallbackSentinel() {
        // Unknown id
        val unknownIdPres = mapOf(
            "body" to mapOf(
                "v" to BigDecimal.ONE,
                "catalog_epoch" to BigDecimal.ONE,
                "code" to "push.unknown.id",
                "args" to emptyMap<String, Any?>(),
                "fallback_en" to "FALLBACK_SENTINEL",
            ),
        )
        assertEquals("FALLBACK_SENTINEL", renderer.renderPresentation("T", "B", unknownIdPres).second)

        // catalog_epoch 2
        val epoch2Pres = mapOf(
            "body" to mapOf(
                "v" to BigDecimal.ONE,
                "catalog_epoch" to BigDecimal(2),
                "code" to "push.test.body",
                "args" to emptyMap<String, Any?>(),
                "fallback_en" to "FALLBACK_SENTINEL",
            ),
        )
        assertEquals("FALLBACK_SENTINEL", renderer.renderPresentation("T", "B", epoch2Pres).second)

        // count: true
        val badTypePres = mapOf(
            "body" to mapOf(
                "v" to BigDecimal.ONE,
                "catalog_epoch" to BigDecimal.ONE,
                "code" to "push.proof.count",
                "args" to mapOf("count" to true),
                "fallback_en" to "FALLBACK_SENTINEL",
            ),
        )
        assertEquals("FALLBACK_SENTINEL", renderer.renderPresentation("T", "B", badTypePres).second)
    }

    @Test
    fun unpackagedFrenchLocaleConfigurationUsesEnglishPlurals() {
        val casesJson = testContext.assets.open("cases.json").bufferedReader().use { it.readText() }
        val root = parseJson(casesJson) as Map<*, *>
        val cases = root["cases"] as List<*>
        val titleCase = cases.map { it as Map<*, *> }.first { it["message_id"] == "push.test.title" }
        val count0Case = cases.map { it as Map<*, *> }.first { it["message_id"] == "push.proof.count" }
        val expectedTitle = (titleCase["expected"] as? String) ?: (titleCase["logical_expected"] as String)
        val expectedCount0 = (count0Case["expected"] as? String) ?: (count0Case["logical_expected"] as String)

        val frConfig = Configuration(targetContext.resources.configuration)
        frConfig.setLocale(Locale.forLanguageTag("fr-FR"))
        val frContext = targetContext.createConfigurationContext(frConfig)
        val frRenderer = PushPresentationRenderer(frContext, catalog)

        val titlePres = mapOf(
            "title" to mapOf(
                "v" to BigDecimal.ONE,
                "catalog_epoch" to BigDecimal.ONE,
                "code" to "push.test.title",
                "args" to emptyMap<String, Any?>(),
                "fallback_en" to "FALLBACK_SENTINEL",
            ),
        )
        assertEquals(expectedTitle, frRenderer.renderPresentation("T", "B", titlePres).first)

        val count0Pres = mapOf(
            "body" to mapOf(
                "v" to BigDecimal.ONE,
                "catalog_epoch" to BigDecimal.ONE,
                "code" to "push.proof.count",
                "args" to mapOf("count" to BigDecimal.ZERO),
                "fallback_en" to "FALLBACK_SENTINEL",
            ),
        )
        assertEquals(expectedCount0, frRenderer.renderPresentation("T", "B", count0Pres).second)
    }
}
