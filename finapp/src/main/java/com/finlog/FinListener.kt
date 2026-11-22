package com.finlog

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Читает КАЖДОЕ уведомление, но сохраняет только финансовые.
 * Все остальные сразу забываются — никуда не записываются.
 */
class FinListener : NotificationListenerService() {

    private val labelCache = HashMap<String, String>()

    override fun onListenerConnected() {
        KeepAliveService.start(this)
        try {
            activeNotifications?.forEach { handle(it) }
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        KeepAliveService.rebindListener(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (_: Exception) {
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val ex = n.extras ?: return
        fun cs(k: String) = ex.getCharSequence(k)?.toString()?.trim().orEmpty()
        val title = cs(Notification.EXTRA_TITLE).ifEmpty { cs(Notification.EXTRA_TITLE_BIG) }
        var text = cs(Notification.EXTRA_BIG_TEXT).ifEmpty { cs(Notification.EXTRA_TEXT) }
        if (text.isEmpty()) {
            val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            if (!lines.isNullOrEmpty()) text = lines.joinToString("\n") { it.toString() }
        }
        if (title.isEmpty() && text.isEmpty()) return

        val db = TxDb.get(this)
        val app = appLabel(sbn.packageName)
        val parsed = Classifier.classify(sbn.packageName, app, title, text, db.trust(sbn.packageName))
            ?: return // не финансовое

        if (db.insert(sbn.postTime, sbn.packageName, app, title, text, parsed, sbn.key)) {
            onNew?.invoke()
        }
    }

    private fun appLabel(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    companion object {
        @Volatile var onNew: (() -> Unit)? = null
    }
}
