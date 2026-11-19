package com.notiflog

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Системная служба: Android сам вызывает onNotificationPosted
 * для КАЖДОГО нового уведомления любого приложения.
 */
class NotifListener : NotificationListenerService() {

    private val labelCache = HashMap<String, String>()

    override fun onListenerConnected() {
        // При подключении сохраняем уведомления, которые уже висят в шторке
        try {
            activeNotifications?.forEach { save(it) }
        } catch (_: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            save(sbn)
        } catch (_: Exception) {
        }
    }

    private fun save(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return

        // Пропускаем «сводки» групп и постоянные уведомления (музыка, загрузки, VPN...)
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val ex = n.extras ?: return
        fun cs(key: String) = ex.getCharSequence(key)?.toString()?.trim().orEmpty()

        val title = cs(Notification.EXTRA_TITLE).ifEmpty { cs(Notification.EXTRA_TITLE_BIG) }
        var text = cs(Notification.EXTRA_BIG_TEXT).ifEmpty { cs(Notification.EXTRA_TEXT) }
        if (text.isEmpty()) {
            val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            if (!lines.isNullOrEmpty()) text = lines.joinToString("\n") { it.toString() }
        }
        if (title.isEmpty() && text.isEmpty()) return

        val app = appLabel(sbn.packageName)
        if (Filters.shouldSkip(this, sbn.packageName, app, title, text)) return

        val db = LogDb.get(this)
        val added = db.insert(
            time = sbn.postTime,
            pkg = sbn.packageName,
            app = app,
            title = title,
            text = text,
            key = sbn.key
        )
        if (added) onNew?.invoke()
    }

    private fun appLabel(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    companion object {
        /** Окно приложения подписывается сюда, чтобы обновлять список вживую */
        @Volatile
        var onNew: (() -> Unit)? = null
    }
}
