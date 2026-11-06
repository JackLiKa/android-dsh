package com.example.adsh.device

import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Provides active notifications to the device bridge. The user grants access
 * in the system notification-access settings; nothing is read before then.
 */
class AdshNotificationListenerService : NotificationListenerService() {
    companion object {
        @Volatile private var active: AdshNotificationListenerService? = null

        fun instance(): AdshNotificationListenerService? = active

        fun isEnabled(context: Context): Boolean {
            if (active != null) return true
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            return enabled.split(':').any { it.startsWith(context.packageName) }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        active = this
    }

    override fun onListenerDisconnected() {
        if (active === this) active = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }
}
