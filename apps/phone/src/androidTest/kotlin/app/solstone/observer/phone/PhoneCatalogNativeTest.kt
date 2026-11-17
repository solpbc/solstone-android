// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.phone

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.i18n.PhoneText
import app.solstone.core.i18n.PhoneTextReader
import app.solstone.core.i18n.phoneText
import app.solstone.core.push.PushRenderCall
import app.solstone.core.push.parsePushCatalog
import app.solstone.observer.formfactor.shared.AndroidPhoneText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PhoneCatalogNativeTest {
    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun unsupportedLocaleUsesSourcePluralRulesAndAnEnumDefault() {
        val savedReader = PhoneText.reader
        val savedLocale = PhoneText.locale
        try {
            val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                setLocales(LocaleList(Locale.FRANCE, Locale.US))
            })
            AndroidPhoneText(context).install()
            // French selects one for zero; fallback English must still use its other category.
            assertEquals("0 photos", phoneText("android.intake.photos", "count" to 0))
            assertEquals("1 photo", phoneText("android.intake.photos", "count" to 1))
            assertEquals("2 photos", phoneText("android.intake.photos", "count" to 2))
            assertEquals("through a relay", phoneText("android.phone.source.route.fixed", "route" to null))
            assertEquals("through a relay", phoneText("android.phone.source.route.fixed"))
        } finally {
            PhoneText.reader = savedReader
            PhoneText.locale = savedLocale
        }
    }

    @Test fun earlyPlaceholdersAreReboundAfterContextAttachment() {
        val savedReader = PhoneText.reader
        val savedLocale = PhoneText.locale
        try {
            PhoneText.reader = PhoneTextReader { _, _, _, _ -> "early placeholder" }
            val early = PhoneApplication()
            assertEquals("early placeholder", early.intakeModel().stateWord)
            PhoneText.reader = savedReader
            val attach = PhoneApplication::class.java.getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
            attach.isAccessible = true
            attach.invoke(early, app)
            assertEquals(phoneText("android.phone.status.unavailable"), early.intakeModel().stateWord)
        } finally {
            PhoneText.reader = savedReader
            PhoneText.locale = savedLocale
        }
    }

    @Test fun completeLicensesSurviveAndroidPackaging() {
        val savedReader = PhoneText.reader
        val savedLocale = PhoneText.locale
        try {
            val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.US) })
            AndroidPhoneText(context).install()
            // Golden hashes of the original bundled license bytes, before catalog migration.
            for ((id, hash) in mapOf(
                "android.legal.license.agpl.exact" to "8486a10c4393cee1c25392769ddd3b2d6c242d6ec7928e1414efff7dfb2f07ef",
                "android.legal.license.ofl" to "1d361a8f8e8ce6e68457dcd93fb56e162e6baa3bbb7e7573a290d44399f6b57e",
                "android.legal.license.lucide" to "653ad2fe1f4492cd4d0f0b1d3961d669b5723f50368e1db0a11ef5cabbe54c05",
            )) {
                val bytes = phoneText(id).toByteArray(Charsets.UTF_8)
                val actual = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                assertEquals(id, hash, actual)
            }
        } finally {
            PhoneText.reader = savedReader
            PhoneText.locale = savedLocale
        }
    }

    @Test fun pushFallbackUsesSourceQuantityRulesAndHandlesMissingSelector() {
        val catalog = parsePushCatalog(app.assets.open("ir.json").bufferedReader().use { it.readText() })
        val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.FRANCE) })
        val renderer = PushPresentationRenderer(context, catalog)
        assertEquals("\u20680\u2069 items are ready.", renderer.render(PushRenderCall("push.proof.count", true, 0, null, "ungrouped")))
        val presentation = mapOf("body" to mapOf("v" to java.math.BigDecimal.ONE, "catalog_epoch" to java.math.BigDecimal.ONE,
            "code" to "push.proof.state", "fallback_en" to "fallback", "args" to emptyMap<String, Any>()))
        assertEquals("fallback", renderer.renderPresentation("title", "body", presentation).second)
    }
}
