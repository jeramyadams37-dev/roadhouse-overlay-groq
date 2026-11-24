package com.atothei.roadhouse.overlay

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Read notifications aloud — "read my notifications" reads unread
 * notification text aloud while driving. Reads only the
 * notification's own text, never opens apps. Default-off: the service
 * does nothing until the driver grants notification access in system
 * Settings (requested only when first used, graceful if denied).
 * The latest notifications are kept in memory only — nothing is
 * written to disk.
 */
class NotificationReaderService : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: NotificationReaderService? = null
            private set

        /** Notification access granted for this app? */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val expected = ComponentName(context, NotificationReaderService::class.java)
                .flattenToString()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        /** Up to 3 most recent readable notifications (app, title, text). */
        fun latestReadable(): List<Triple<String, String, String>> {
            val service = instance ?: return emptyList()
            return try {
                val active = service.activeNotifications ?: return emptyList()
                val ownPkg = service.packageName
                active
                    .filter { it.packageName != ownPkg && it.packageName != "android" }
                    .filter { !it.isOngoing }
                    .sortedByDescending { it.postTime }
                    .take(3)
                    .mapNotNull { sbn ->
                        val extras = sbn.notification.extras
                        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
                        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
                        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty()
                        val body = bigText.ifBlank { text }.trim()
                        if (body.isBlank() && title.isBlank()) return@mapNotNull null
                        val app = appLabel(service, sbn.packageName)
                        Triple(app, title, body)
                    }
            } catch (e: Exception) {
                emptyList()
            }
        }

        private fun appLabel(context: Context, pkg: String): String {
            return try {
                val ai = context.packageManager.getApplicationInfo(pkg, 0)
                context.packageManager.getApplicationLabel(ai).toString()
            } catch (e: Exception) {
                pkg
            }
        }

        fun readAloudText(context: Context): String {
            if (!isEnabled(context)) {
                return "Notification access isn't on yet. Open Settings in the RoadHouse app, " +
                    "tap \"Allow notification access\", and turn on RoadHouse Overlay Groq — " +
                    "then ask me again."
            }
            val items = latestReadable()
            if (items.isEmpty()) {
                return "No recent notifications to read."
            }
            val noun = if (items.size == 1) "notification" else "notifications"
            val sb = StringBuilder("${items.size} recent $noun. ")
            items.forEach { (app, title, body) ->
                val cleanBody = body.replace(Regex("\\s+"), " ").trim().take(200)
                val cleanTitle = title.replace(Regex("\\s+"), " ").trim().take(80)
                sb.append("From $app")
                if (cleanTitle.isNotBlank()) sb.append(": $cleanTitle")
                if (cleanBody.isNotBlank()) sb.append(". $cleanBody")
                sb.append(". ")
            }
            return sb.toString().trim()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Passive: notifications are only read when the driver asks.
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
