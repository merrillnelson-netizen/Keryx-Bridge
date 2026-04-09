package app.keryx.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import app.keryx.bridge.util.Prefs

private const val TAG = "BootReceiver"

/**
 * Starts [SmsObserverService] after the device boots.
 * Handles all common boot action strings for maximum device compatibility.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Received boot action: $action")

        val prefs = Prefs.get(context)
        if (!prefs.enabled || !prefs.isConfigured()) {
            Log.d(TAG, "Bridge disabled or not configured — skipping auto-start")
            return
        }

        val serviceIntent = Intent(context, SmsObserverService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        Log.d(TAG, "SmsObserverService started after boot")
    }
}
