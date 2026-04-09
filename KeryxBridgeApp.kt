package app.keryx.bridge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.Configuration
import androidx.work.WorkManager

class KeryxBridgeApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        // Foreground service channel — low importance = no sound/heads-up
        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "Keryx Bridge Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Keryx Bridge running in the background"
            setShowBadge(false)
        }

        // Status/error alerts channel
        val alertChannel = NotificationChannel(
            CHANNEL_ALERTS,
            "Keryx Bridge Alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Permission or connectivity alerts from Keryx Bridge"
        }

        manager.createNotificationChannels(listOf(serviceChannel, alertChannel))
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    companion object {
        const val CHANNEL_SERVICE = "keryx_bridge_service"
        const val CHANNEL_ALERTS = "keryx_bridge_alerts"
    }
}
