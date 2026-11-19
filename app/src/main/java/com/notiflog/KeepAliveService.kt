package com.notiflog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService

/**
 * Фоновая служба «переднего плана» с постоянным значком в шторке.
 * Пока она работает, система (особенно ColorOS / MIUI) не убивает процесс,
 * и NotifListener продолжает ловить уведомления после закрытия приложения.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        // Если связь со службой уведомлений оборвалась — просим систему переподключить
        rebindListener(this)
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL, "Фоновая работа", NotificationManager.IMPORTANCE_MIN
            )
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)

        val n = b.setContentTitle("Журнал уведомлений работает")
            .setContentText("Уведомления записываются в фоне")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "keepalive"
        private const val ID = 1

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) {
                // Система может запретить запуск из фона — запустим при следующем открытии
            }
        }

        fun rebindListener(ctx: Context) {
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(ctx, NotifListener::class.java)
                )
            } catch (_: Exception) {
            }
        }
    }
}
