package com.fitdash.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest

class StepsComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 6500f, min = 0f, max = 10000f,
                contentDescription = PlainComplicationText.Builder("Steps").build()
            ).setText(PlainComplicationText.Builder("6,500").build()).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("6.5K").build(),
                contentDescription = PlainComplicationText.Builder("Steps").build()
            ).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder("6,500 steps").build(),
                contentDescription = PlainComplicationText.Builder("Steps").build()
            ).build()
            else -> null
        }
    }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        val prefs = getSharedPreferences("fitdash_wear", MODE_PRIVATE)
        val steps = prefs.getInt("steps", 0)
        val goal  = prefs.getInt("goal", 10000)

        val tapIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage("com.fitdash") ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val data = when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = steps.toFloat(), min = 0f, max = goal.toFloat(),
                contentDescription = PlainComplicationText.Builder("Steps").build()
            ).setText(PlainComplicationText.Builder(formatSteps(steps)).build())
                .setTapAction(tapIntent).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(formatSteps(steps)).build(),
                contentDescription = PlainComplicationText.Builder("Steps").build()
            ).setTapAction(tapIntent).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder("$steps steps").build(),
                contentDescription = PlainComplicationText.Builder("Steps").build()
            ).setTapAction(tapIntent).build()
            else -> return
        }
        listener.onComplicationData(data)
    }

    private fun formatSteps(steps: Int): String = steps.toString()
}
