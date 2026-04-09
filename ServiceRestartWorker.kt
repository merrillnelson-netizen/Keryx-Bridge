package app.keryx.bridge.network

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.keryx.bridge.SmsObserverService
import app.keryx.bridge.util.Prefs

/**
 * One-shot WorkManager worker that re-launches [SmsObserverService] after it
 * gets killed by a task-swipe. Scheduled from [SmsObserverService.onTaskRemoved].
 */
class ServiceRestartWorker(context: Context, params: WorkerParameters) :
    Worker(context, params) {

    override fun doWork(): Result {
        val prefs = Prefs.get(applicationContext)
        if (!prefs.enabled || !prefs.isConfigured()) return Result.success()

        Log.d("ServiceRestartWorker", "Restarting SmsObserverService after task removal")
        val intent = Intent(applicationContext, SmsObserverService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(intent)
        } else {
            applicationContext.startService(intent)
        }
        return Result.success()
    }
}
