// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear

import app.solstone.core.watch.courier.WatchCourierStrings
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/** A complication never claims to be a live microphone indicator. */
class WatchComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        if(request.complicationType==ComplicationType.SHORT_TEXT)unknown() else null
    override fun getPreviewData(type:ComplicationType):ComplicationData? =
        if(type==ComplicationType.SHORT_TEXT)unknown() else null
    private fun unknown():ShortTextComplicationData = ShortTextComplicationData.Builder(
        PlainComplicationText.Builder("?").build(),
        PlainComplicationText.Builder("session status unknown. open ${WatchCourierStrings.SOLSTONE}").build()
    ).setMonochromaticImage(MonochromaticImage.Builder(Icon.createWithResource(this,R.drawable.complication_unknown)).build())
        .setTapAction(wearOpenFaceIntent(this)).build()
}
