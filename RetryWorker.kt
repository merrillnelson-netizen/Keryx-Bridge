package app.keryx.bridge.network

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.keryx.bridge.data.AppDatabase
import app.keryx.bridge.util.Prefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "RetryWorker"
private const val MAX_ATTEMPTS = 6

/**
 * WorkManager worker that drains the pending_relay Room table.
 * - Runs only when a network connection is available (declared via Constraints).
 * - After [MAX_ATTEMPTS] failures a row is marked `failed = true` and its count is
 *   recorded in [Prefs.permanentFailureCount] before being purged.
 * - Uses a dedicated OkHttpClient with Keep-Alive.
 */
class RetryWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json".toMediaType()

    override suspend fun doWork(): Result {
        val prefs = Prefs.get(applicationContext)
        if (!prefs.isConfigured() || !prefs.enabled) return Result.success()

        val serverUrl = prefs.serverUrl!!.trimEnd('/')
        val apiKey = prefs.apiKey!!
        val dao = AppDatabase.get(applicationContext).pendingRelayDao()

        // Mark rows that have exhausted all retries as permanently failed,
        // tally them in Prefs, then purge them from the queue.
        val exhaustedCount = dao.markExhaustedAsFailed(MAX_ATTEMPTS)
        if (exhaustedCount > 0) {
            Log.w(TAG, "$exhaustedCount message(s) permanently failed after $MAX_ATTEMPTS attempts")
            repeat(exhaustedCount) { prefs.incrementPermanentFailure() }
            dao.purgeFailedRows()
        }

        val pending = dao.getAll()
        if (pending.isEmpty()) {
            prefs.pendingRetryCount = 0
            return Result.success()
        }

        Log.d(TAG, "RetryWorker: attempting ${pending.size} queued messages")

        var anyFailed = false
        for (item in pending) {
            val success = attemptRelay(serverUrl, apiKey, item.payloadJson)
            if (success) {
                dao.deleteById(item.id)
            } else {
                dao.incrementAttempts(item.id)
                anyFailed = true
            }
        }

        prefs.pendingRetryCount = dao.countPending()

        return if (anyFailed) Result.retry() else Result.success()
    }

    private fun attemptRelay(serverUrl: String, apiKey: String, payloadJson: String): Boolean {
        return try {
            val orig = JSONObject(payloadJson)
            val bodyJson = JSONObject().apply {
                put("type", "sms")
                put("source", "android-bridge")
                put("address", orig.optString("address"))
                put("body", orig.optString("body"))
                put("direction", orig.optString("direction"))
                put("timestamp", orig.optString("timestamp"))
                val name = orig.optString("name", "")
                if (name.isNotBlank()) put("name", name)
            }.toString()

            val request = Request.Builder()
                .url("$serverUrl/api/relay/inbound")
                .addHeader("X-API-Key", apiKey)
                .post(bodyJson.toRequestBody(jsonType))
                .build()

            val response = client.newCall(request).execute()
            response.use {
                when {
                    response.isSuccessful -> {
                        Prefs.get(applicationContext).recordSuccess()
                        true
                    }
                    response.code in 400..499 -> {
                        // 4xx = bad config — not a transient error, discard immediately
                        Log.e(TAG, "4xx error on retry ${response.code} — discarding row")
                        Prefs.get(applicationContext).incrementError()
                        true // delete the row
                    }
                    else -> false
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network error in RetryWorker: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in RetryWorker: ${e.message}")
            false
        }
    }
}
