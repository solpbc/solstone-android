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
        immediate(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(SUN, ResourceBuilders.ImageResource.Builder()
                .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(R.drawable.ic_sun_mono).build())
                .build())
            .build())

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): com.google.common.util.concurrent.ListenableFuture<TileBuilders.Tile> {
        val open=ActionBuilders.LaunchAction.Builder().setAndroidActivity(
            ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(WearMainActivity::class.java.name).build()
        ).build()
        val clickable = ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(open).build()
        fun text(value:String,size:Float,color:Int,bold:Boolean=false)=LayoutElementBuilders.Text.Builder().setText(value).setMaxLines(2).setFontStyle(
            LayoutElementBuilders.FontStyle.Builder().setSize(DimensionBuilders.sp(size)).setColor(ColorBuilders.argb(color))
                .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL).build()
        ).build()
        fun gap(height:Float)=LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(height)).build()
        val sun=LayoutElementBuilders.Image.Builder().setResourceId(SUN)
            .setWidth(DimensionBuilders.dp(28f)).setHeight(DimensionBuilders.dp(28f))
            .setColorFilter(LayoutElementBuilders.ColorFilter.Builder().setTint(ColorBuilders.argb(GOLD)).build()).build()
        // The one control, as a chip: the same solid orange the app's start control uses.
        val chip=LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.dp(112f)).setHeight(DimensionBuilders.dp(48f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder()
                .setBackground(ModifiersBuilders.Background.Builder().setColor(ColorBuilders.argb(ORANGE))
                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(24f)).build()).build())
                .setClickable(clickable)
                .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription("open ${WatchCourierStrings.SOLSTONE}").build())
                .build())
            .addContent(text("open",15f,ORANGE_INK,bold=true)).build()
        val content=LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(sun)
            .addContent(gap(6f))
            .addContent(text(WatchCourierStrings.SOLSTONE,18f,CREAM,bold=true))
            .addContent(gap(4f))
            .addContent(text("start on your watch",13f,CALM))
            .addContent(gap(12f))
            .addContent(chip).build()
        val root=LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand()).setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder()
                .setBackground(ModifiersBuilders.Background.Builder().setColor(ColorBuilders.argb(0xff000000.toInt())).build())
                .setClickable(clickable)
                .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription("${WatchCourierStrings.SOLSTONE}. start on your watch. open").build()).build())
            .addContent(content).build()
        val layout=LayoutElementBuilders.Layout.Builder().setRoot(root).build()
        return immediate(TileBuilders.Tile.Builder().setResourcesVersion(RESOURCES_VERSION).setTileTimeline(
            TimelineBuilders.Timeline.Builder().addTimelineEntry(TimelineBuilders.TimelineEntry.Builder().setLayout(layout).build()).build()
        ).build())
    }

    private companion object {
        const val RESOURCES_VERSION = "2"
        const val SUN = "sun"
        const val CREAM = 0xfff4eee4.toInt()
        const val CALM = 0xffd9d9e1.toInt()
        const val GOLD = 0xffffcc33.toInt()
        const val ORANGE = 0xffe8913a.toInt()
        const val ORANGE_INK = 0xff1e1610.toInt()
    }
}
