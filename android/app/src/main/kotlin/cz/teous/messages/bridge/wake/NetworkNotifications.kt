package cz.teous.messages.bridge.wake

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import cz.teous.messages.bridge.hub

/**
 * Wakes a network when its own app on this phone posts a notification: WhatsApp gets its push, the bridge sees the
 * notification, connects for a short window and syncs (docs/OVERVIEW.md, Staying connected). A listener the system has
 * bound counts as foreground for background limits, so the sync runs here. It reads only the posting package, never
 * the notification's text. Muted chats post nothing and wait for the catch-up.
 */
class NetworkNotifications : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val hub = applicationContext.hub
        for ((network, packages) in hub.wakePackages()) {
            if (sbn.packageName in packages) hub.wake(network, WAKE_MS)
        }
    }

    companion object {
        const val WAKE_MS = 45_000L

        fun granted(context: Context): Boolean {
            val me = ComponentName(context, NetworkNotifications::class.java).flattenToString()
            return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")?.split(':')?.contains(me) == true
        }
    }
}
