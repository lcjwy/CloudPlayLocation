package com.chan.location.service.mock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat

/** 两个前台服务共用的通知：渠道创建 + 构造（点击回应用主页） */
internal object ServiceNotifications {
    fun createChannel(
        context: Context,
        channelId: String,
        nameRes: Int,
        importance: Int,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, context.getString(nameRes), importance),
        )
    }

    fun build(
        context: Context,
        channelId: String,
        titleRes: Int,
        contentText: String,
    ): Notification {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent =
            launch?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
            }
        return NotificationCompat
            .Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_locate)
            .setContentTitle(context.getString(titleRes))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }
}
