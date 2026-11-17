// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.tiles.RequestBuilders
import androidx.wear.watchface.complications.data.ComplicationType
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class WatchCatalogSurfacesTest {
    private fun attach(service: ContextWrapper) {
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
        }.invoke(service, InstrumentationRegistry.getInstrumentation().targetContext)
    }
    @Test fun tileAndComplicationBuildOutsideTheActivity() {
        val tile = WatchTileService().also { attach(it) }
        val method = WatchTileService::class.java.getDeclaredMethod("onTileRequest", RequestBuilders.TileRequest::class.java).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(tile,RequestBuilders.TileRequest.Builder().build()) as com.google.common.util.concurrent.ListenableFuture<androidx.wear.tiles.TileBuilders.Tile>
        val response = result.get(10,TimeUnit.SECONDS)
        assertNotNull(response.tileTimeline)
        val complication = WatchComplicationService().also { attach(it) }.getPreviewData(ComplicationType.SHORT_TEXT)
        assertNotNull(complication)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val copy = AndroidWatchCopy(context.resources)
        for (message in listOf(WatchMessage.APP_NAME,WatchMessage.OPEN,WatchMessage.START_A_SESSION,WatchMessage.OPEN_SOLSTONE,WatchMessage.TILE_SPOKEN)) {
            assertTrue(message.name, response.toString().contains(copy.text(message)))
        }
        val short = complication as androidx.wear.watchface.complications.data.ShortTextComplicationData
        assertEquals(copy.text(WatchMessage.COMPLICATION_UNKNOWN),short.text.getTextAt(context.resources,java.time.Instant.EPOCH).toString())
        assertEquals(copy.text(WatchMessage.COMPLICATION_SPOKEN),short.contentDescription!!.getTextAt(context.resources,java.time.Instant.EPOCH).toString())
        android.util.Log.i("WatchCatalogProof", "TILE: $response")
        android.util.Log.i("WatchCatalogProof", "COMPLICATION: $complication")
    }
    @Test fun notificationAndOngoingActivityUseTheCatalog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val copy = AndroidWatchCopy(context.resources)
        val n = WearOngoingActivity.build(context,"catalog-test",2001,copy.text(WatchMessage.STARTING))
        assertEquals(copy.text(WatchMessage.APP_NAME),n.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals(copy.text(WatchMessage.STARTING),n.extras.getString(android.app.Notification.EXTRA_TEXT))
        android.util.Log.i("WatchCatalogProof", "NOTIFICATION: ${n.extras}")
    }
}
