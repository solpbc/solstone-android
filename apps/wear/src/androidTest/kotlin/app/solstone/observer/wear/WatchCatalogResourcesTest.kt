// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class WatchCatalogResourcesTest {
    @Test fun nativeResourcesMatchTheEnglishReaderAndFallbackOnUnsupportedLanguages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (locale in listOf("en-US", "en-GB", "es-MX", "ar-EG")) {
            val config = Configuration(context.resources.configuration)
            config.setLocales(LocaleList.forLanguageTags(locale))
            val copy = AndroidWatchCopy(context.createConfigurationContext(config).resources)
            for (message in WatchMessage.entries) {
                val quantity = context.resources.getResourceTypeName(message.resource) == "plurals"
                val args = arguments(message)
                if (quantity) for (n in listOf(0,1,2,5)) {
                    assertEquals("$locale/$message/$n", EnglishWatchCopy.quantity(message,n,n.toString()), copy.quantity(message,n,n.toString()))
                } else {
                    assertEquals("$locale/$message", EnglishWatchCopy.text(message,*args), copy.text(message,*args))
                }
            }
        }
    }

    @Test fun optionalFailureReasonsStayHandled() {
        val copy = AndroidWatchCopy(InstrumentationRegistry.getInstrumentation().targetContext.resources)
        for (state in app.solstone.core.watch.courier.WatchSessionState.entries) {
            assertTrue(WatchSessionCopy.label(app.solstone.core.watch.courier.WatchSessionReduction(state),copy).isNotBlank())
        }
    }

    private fun arguments(message: WatchMessage): Array<Any> = when (message) {
        WatchMessage.HEADER_SPOKEN -> arrayOf("running", "2 minutes")
        WatchMessage.ROW_SPOKEN -> arrayOf("held", "2")
        WatchMessage.NEXT_STEPS -> arrayOf("session", "delivery")
        WatchMessage.HOURS_MINUTES_SHORT -> arrayOf("1", "5")
        WatchMessage.HOURS_MINUTES_SPOKEN -> arrayOf("1 hour", "5 minutes")
        WatchMessage.COUNT, WatchMessage.MINUTES_AGO, WatchMessage.SECONDS_SHORT, WatchMessage.MINUTES_SHORT -> arrayOf("2")
        else -> emptyArray()
    }
}
