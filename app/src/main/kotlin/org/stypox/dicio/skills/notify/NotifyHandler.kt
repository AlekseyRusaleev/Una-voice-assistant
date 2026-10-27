package org.stypox.dicio.skills.notify

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

open class NotifyHandler : NotificationListenerService() {
    companion object Companion {
        private const val TAG: String = "NotifyHandler"
        var Instance: NotifyHandler? = null

        /** True when the user has granted notification access to the app. */
        fun available(): Boolean = Instance != null
    }

    override fun onCreate() {
        super.onCreate()
        Instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        Instance = null
    }

    fun getActiveNotificationsList(): List<Notification> {
        // getActiveNotifications() can only be run in NotificationListenerService() class
        return active().mapNotNull { notification ->
            val title = notification.notification.extras.getString("android.title")
            val message = notification.notification.extras.getString("android.text")
            if (title.isNullOrBlank() && message.isNullOrBlank()) {
                return@mapNotNull null // skip empty notifications (like from android_system)
            }
            Notification(appName(notification.packageName), title ?: "", message ?: "")
        }
    }

    /** Raw active notifications, newest first. */
    fun active(): List<StatusBarNotification> = try {
        (getActiveNotifications() ?: emptyArray())
            .sortedByDescending { it.postTime }
    } catch (t: Throwable) {
        Log.w(TAG, "cannot read notifications", t)
        emptyList()
    }

    /** Human-readable lines like "Telegram: Мама — не забудь хлеб". */
    fun summary(limit: Int = 8): List<String> = active()
        .mapNotNull { sbn ->
            val extras = sbn.notification.extras
            val title = extras.getCharSequence("android.title")?.toString()?.trim().orEmpty()
            val text = extras.getCharSequence("android.text")?.toString()?.trim().orEmpty()
            val big = extras.getCharSequence("android.bigText")?.toString()?.trim().orEmpty()
            val body = text.ifBlank { big }
            if (title.isBlank() && body.isBlank()) {
                null
            } else {
                val app = appName(sbn.packageName)
                if (body.isBlank()) "$app: $title" else "$app: $title — $body"
            }
        }
        .distinct()
        .take(limit)

    /**
     * Replies into the chat notification that matches [contact], using the "direct reply" action
     * the messaging app itself provides. Returns false when no such notification (or no reply
     * action) is found.
     */
    fun reply(contact: String, message: String): Boolean {
        val service = Instance ?: return false
        val query = contact.lowercase().trim()
        val candidates = active()
        for (sbn in candidates) {
            val notification = sbn.notification ?: continue
            val extras = notification.extras
            val title = extras.getCharSequence("android.title")?.toString()?.lowercase().orEmpty()
            val text = extras.getCharSequence("android.text")?.toString()?.lowercase().orEmpty()
            val big = extras.getCharSequence("android.bigText")?.toString()?.lowercase().orEmpty()

            val matches = query.isEmpty() ||
                title.contains(query) || text.contains(query) || big.contains(query)
            if (!matches) {
                continue
            }
            val action = notification.actions
                ?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
                ?: continue
            val inputs = action.remoteInputs ?: continue

            val intent = Intent()
            val results = Bundle()
            for (input in inputs) {
                results.putCharSequence(input.resultKey, message)
            }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            try {
                action.actionIntent.send(service, 0, intent)
                Log.i(TAG, "reply sent to \"$title\"")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "reply failed for \"$title\"", t)
            }
        }
        return false
    }

    private fun appName(packageName: String): String = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (t: Throwable) {
        packageName
    }
}

/**
 * Either [message] or [title] will be non-empty
 */
data class Notification(
    val appName: String,
    val title: String,
    val message: String,
)
