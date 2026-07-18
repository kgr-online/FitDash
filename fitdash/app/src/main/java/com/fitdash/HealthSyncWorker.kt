package com.fitdash

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.*
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

class HealthSyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())
        return try {
            val hcManager = HealthConnectManager(context)
            if (!hcManager.isAvailable() || !hcManager.hasPermissions()) return Result.success()

            // ── UltraSignal sync (runs first so HC read below sees fresh data) ─
            val syncer = UltraSignalSyncer(context)
            if (syncer.hasApiToken()) {
                val ok = syncer.sync()
                android.util.Log.d("FitDash:Worker", "UltraSignal sync: ${if (ok) "ok" else "failed/skipped"}")
            }

            val payload = withContext(Dispatchers.IO) { hcManager.fetchDashboard() }
            val steps   = payload.today.steps.toInt()
            val goal    = payload.today.stepsGoal.toInt()

            // ── Widget cache ──────────────────────────────────────────────────
            context.getSharedPreferences("fitdash_widget", Context.MODE_PRIVATE)
                .edit().putInt("steps", steps).putInt("goal", goal).apply()

            val widgetManager = AppWidgetManager.getInstance(context)
            val widgetIds     = widgetManager.getAppWidgetIds(
                ComponentName(context, StepsWidgetProvider::class.java)
            )
            if (widgetIds.isNotEmpty()) {
                StepsWidgetProvider().onUpdate(context, widgetManager, widgetIds)
            }

            // ── Watch push ────────────────────────────────────────────────────
            try {
                val dataClient = Wearable.getDataClient(context)
                val req = PutDataMapRequest.create("/fitdash/steps").apply {
                    dataMap.putInt("steps", steps)
                    dataMap.putInt("goal", goal)
                    dataMap.putLong("timestamp", System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Tasks.await(dataClient.putDataItem(req))
            } catch (e: Exception) {
                android.util.Log.w("FitDash:Worker", "Watch push failed: ${e.message}")
            }

            android.util.Log.d("FitDash:Worker", "Sync done — steps=$steps")
            Result.success()
        } catch (e: Exception) {
            val isQuotaError = e.message?.contains("quota exceeded", ignoreCase = true) == true ||
                               e.cause?.message?.contains("quota exceeded", ignoreCase = true) == true
            if (isQuotaError) {
                android.util.Log.w("FitDash:Worker", "HC quota exceeded — skipping cycle, will retry next schedule")
                Result.success()  // don't retry, just wait for next 30-min window
            } else {
                android.util.Log.e("FitDash:Worker", "Sync failed", e)
                Result.retry()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val tapIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("FitDash")
            .setContentText("Syncing health data...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    companion object {
        private const val CHANNEL_ID      = "fitdash_sync"
        private const val NOTIFICATION_ID = 1
        private const val WORK_NAME       = "fitdash_health_sync"

        fun schedule(context: Context) {
            createNotificationChannel(context)
            val intervalMinutes = context
                .getSharedPreferences("fitdash_settings", Context.MODE_PRIVATE)
                .getInt("sync_interval_minutes", 30)
                .toLong()
                .coerceAtLeast(15) // minimum 15 minutes
            val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<HealthSyncWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }

        private fun createNotificationChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FitDash Sync",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background health data sync"
                setShowBadge(false)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
