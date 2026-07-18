package com.fitdash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class SyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.fitdash.REFRESH") {
            // Trigger background data sync
            HealthSyncWorker.runNow(context)

            // Post sync complete notification
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channel = NotificationChannel(
                "fitdash_sync_complete",
                "Sync Complete",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(channel)

            val tapIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, "fitdash_sync_complete")
                .setContentTitle("FitDash")
                .setContentText("Health data synced")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(tapIntent)
                .setAutoCancel(true)
                .build()

            nm.notify(2, notification)
        }
    }
}
