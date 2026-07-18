package com.fitdash

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.widget.RemoteViews
import java.text.NumberFormat

class StepsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
        val prefs = context.getSharedPreferences("fitdash_widget", Context.MODE_PRIVATE)
        val steps = prefs.getInt("steps", 0)
        val goal  = prefs.getInt("goal", 10000)

        val openIntent = Intent(context, MainActivity::class.java)
        val tapIntent  = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val views = RemoteViews(context.packageName, R.layout.widget_steps)
        views.setOnClickPendingIntent(R.id.widget_root, tapIntent)
        views.setImageViewBitmap(R.id.widget_arc, drawArc(context, steps, goal))
        appWidgetManager.updateAppWidget(widgetId, views)
    }

    private fun drawArc(context: Context, steps: Int, goal: Int): Bitmap {
        val density     = context.resources.displayMetrics.density
        val size        = (220 * density).toInt()
        val bmp         = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas      = Canvas(bmp)
        val cx          = size / 2f; val cy = size / 2f
        val strokeWidth = 18f * density
        val radius      = cx - strokeWidth / 2f - 4f * density
        val oval        = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val startAngle  = 150f; val sweepTotal = 240f
        val pct         = if (goal > 0) (steps.toFloat() / goal).coerceIn(0f, 1f) else 0f

        canvas.drawArc(oval, startAngle, sweepTotal, false, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
            this.strokeWidth = strokeWidth; color = Color.parseColor("#2a2a2a")
        })
        if (pct > 0f) {
            canvas.drawArc(oval, startAngle, sweepTotal * pct, false, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
                this.strokeWidth = strokeWidth; color = Color.parseColor("#b0b0b0")
            })
        }
        canvas.drawText(
            NumberFormat.getNumberInstance().format(steps.toLong()), cx, cy + 10f * density,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 38f * density
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        )
        canvas.drawText(
            "/ ${NumberFormat.getNumberInstance().format(goal.toLong())}", cx, cy + 32f * density,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#888888"); textSize = 16f * density
                textAlign = Paint.Align.CENTER
            }
        )
        return bmp
    }
}
