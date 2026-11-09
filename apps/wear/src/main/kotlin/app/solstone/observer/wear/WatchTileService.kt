// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear

import app.solstone.core.watch.courier.WatchCourierStrings
import androidx.wear.tiles.TileService
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.concurrent.futures.CallbackToFutureAdapter

/** Stateless opening surface. No session command or cached microphone claim. */
class WatchTileService : TileService() {
    private fun <T> immediate(value:T):com.google.common.util.concurrent.ListenableFuture<T> = CallbackToFutureAdapter.getFuture { completer -> completer.set(value); "stateless tile" }
    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) =
        immediate(ResourceBuilders.Resources.Builder().setVersion("1").build())

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): com.google.common.util.concurrent.ListenableFuture<TileBuilders.Tile> {
        val open=ActionBuilders.LaunchAction.Builder().setAndroidActivity(
            ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(WearMainActivity::class.java.name).build()
        ).build()
        fun text(value:String,size:Float)=LayoutElementBuilders.Text.Builder().setText(value).setFontStyle(
            LayoutElementBuilders.FontStyle.Builder().setSize(DimensionBuilders.sp(size)).setColor(ColorBuilders.argb(0xffffffff.toInt())).build()
        ).build()
        val content=LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand()).setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder()
                .setBackground(ModifiersBuilders.Background.Builder().setColor(ColorBuilders.argb(0xff000000.toInt())).build())
                .setPadding(ModifiersBuilders.Padding.Builder().setTop(DimensionBuilders.dp(48f)).build())
                .setClickable(ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(open).build())
                .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription("${WatchCourierStrings.SOLSTONE}. start on your watch. open").build()).build())
            .addContent(text(WatchCourierStrings.SOLSTONE,20f))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(16f)).build())
            .addContent(text("start on your watch",14f))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(16f)).build())
            .addContent(text("open",16f)).build()
        val layout=LayoutElementBuilders.Layout.Builder().setRoot(content).build()
        return immediate(TileBuilders.Tile.Builder().setResourcesVersion("1").setTileTimeline(
            TimelineBuilders.Timeline.Builder().addTimelineEntry(TimelineBuilders.TimelineEntry.Builder().setLayout(layout).build()).build()
        ).build())
    }
}
