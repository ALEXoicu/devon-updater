package com.devon.updater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifications {
    const val UPDATES = "updates"
    const val PROGRESS = "progress"
    const val ID_UPDATE = 1
    const val ID_PROGRESS = 2

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(UPDATES, "Updates available", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(PROGRESS, "Install progress", NotificationManager.IMPORTANCE_LOW))
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun update(ctx: Context, title: String, text: String) {
        val n = Notification.Builder(ctx, UPDATES)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(openApp(ctx)).setAutoCancel(true).build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_UPDATE, n)
    }

    fun progress(ctx: Context, text: String, fraction: Float?): Notification =
        Notification.Builder(ctx, PROGRESS)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle("devon-updater").setContentText(text)
            .setContentIntent(openApp(ctx)).setOngoing(true)
            .apply { if (fraction == null) setProgress(0, 0, true) else setProgress(1000, (fraction * 1000).toInt(), false) }
            .build()
}
