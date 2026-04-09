package app.keryx.bridge.network

import android.content.Context
import android.util.Log
import app.keryx.bridge.data.AppDatabase
import app.keryx.bridge.data.PendingRelay
import app.keryx.bridge.util.Prefs
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import androidx.work.*

private const val TAG = "RelayClient"
private const val MAX_ATTEMPTS = 6
private const val DEBOUNCE_MS = 2500L

/**
 * Singleton relay client.
 * - Single OkHttpClient with Keep-Alive (default in OkHttp).
 * - 2.5-second debounce buffer: messages accumulate, then flush in a single burst
 *   using the existing TCP connection.
 * - On 5xx / network error: queues to Room for WorkManager-powered retry.
 * - On 4xx: discards and increments error counter (bad key etc — no retry).
 */
class RelayClient private constructor(private val context: Context) {

    data class RelayPayload(
        val address: String,
        val body: String,
        val direction: String,
        val timestamp: String,
        val name: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val buffer = mutableListOf<RelayPayload>()
    private var flushJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json".toMediaType()

    /**
     * Enqueues a message in the debounce buffer.
     * After [DEBOUNCE_MS] ms of idle time, the buffer is flushed.
     */
    fun enqueue(payload: RelayPayload) {
        scope.launch {
            mutex.withLock {
                buffer.add(payload)
                flushJob?.cancel()
                flushJob = scope.launch {
                    delay(DEBOUNCE_MS)
                    flush()
                }
            }
        }
    }

    /** Force-flush the buffer immediately (e.g., on service stop). */
    fun flushNow() {
        scope.launch { flush() }
    }

    /**
     * Attempts to relay [payload] immediately (bypassing the debounce buffer).
     * - On 2xx: records success, returns true.
     * - On 5xx / network error: queues to Room for [RetryWorker], returns true
     *   (caller can safely advance its checkpoint — delivery is now durable).
     * - On 4xx: discards (bad config), increments error counter, returns false
     *   (caller should NOT advance checkpoint — message is permanently lost).
     *
     * Designed for the outgoing-SMS observer path where the caller needs to
     * tie checkpoint advancement to confirmed durability.
     */
    suspend fun sendOrQueue(payload: RelayPayload): Boolean {
        val prefs = Prefs.get(context)
        if (!prefs.enabled || !prefs.isConfigured()) return false
        val serverUrl = prefs.serverUrl!!.trimEnd('/')
        val apiKey = prefs.apiKey!!
        return sendOrQueueInternal(serverUrl, apiKey, payload)
    }

    private suspend fun sendOrQueueInternal(
        serverUrl: String,
        apiKey: String,
        payload: RelayPayload
    ): Boolean {
        val bodyJson = JSONObject().apply {
            put("type", "sms")
            put("source", "android-bridge")
            put("address", payload.address)
            put("body", payload.body)
            put("direction", payload.direction)
            put("timestamp", payload.timestamp)
            payload.name?.let { put("name", it) }
        }.toString()

        val request = Request.Builder()
            .url("$serverUrl/api/relay/inbound")
            .addHeader("X-API-Key", apiKey)
            .post(bodyJson.toRequestBody(jsonType))
            .build()

        return try {
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            response.use {
                when {
                    response.isSuccessful -> {
                        Log.d(TAG, "sendOrQueue OK ${response.code} — ${payload.direction} from ${payload.address}")
                        Prefs.get(context).recordSuccess()
                        true // delivered — caller can advance checkpoint
                    }
                    response.code in 400..499 -> {
                        // Bad config; do not retry, do not advance checkpoint
                        Log.e(TAG, "sendOrQueue 4xx ${response.code} — dropping, check API key")
                        Prefs.get(context).incrementError()
                        false
                    }
                    else -> {
                        // 5xx: queue for retry — message is now durable
                        Log.w(TAG, "sendOrQueue 5xx ${response.code} — queuing for retry")
                        queueForRetry(payload)
                        true // durable — caller can advance checkpoint
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "sendOrQueue network error — queuing for retry: ${e.message}")
            queueForRetry(payload)
            true // durable — caller can advance checkpoint
        } catch (e: Exception) {
            Log.e(TAG, "sendOrQueue unexpected error: ${e.message}")
            queueForRetry(payload)
            true
        }
    }

    private suspend fun flush() {
        val toSend: List<RelayPayload>
        mutex.withLock {
            if (buffer.isEmpty()) return
            toSend = buffer.toList()
            buffer.clear()
        }

        val prefs = Prefs.get(context)
        if (!prefs.enabled || !prefs.isConfigured()) {
            Log.d(TAG, "Skipping flush — bridge disabled or not configured")
            return
        }

        val serverUrl = prefs.serverUrl!!.trimEnd('/')
        val apiKey = prefs.apiKey!!

        for (payload in toSend) {
            postRelay(serverUrl, apiKey, payload)
        }
    }

    private suspend fun postRelay(serverUrl: String, apiKey: String, payload: RelayPayload) {
        val bodyJson = JSONObject().apply {
            put("type", "sms")
            put("source", "android-bridge")
            put("address", payload.address)
            put("body", payload.body)
            put("direction", payload.direction)
            put("timestamp", payload.timestamp)
            payload.name?.let { put("name", it) }
        }.toString()

        val request = Request.Builder()
            .url("$serverUrl/api/relay/inbound")
            .addHeader("X-API-Key", apiKey)
            .post(bodyJson.toRequestBody(jsonType))
            .build()

        try {
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            response.use {
                when {
                    response.isSuccessful -> {
                        Log.d(TAG, "Relay OK ${response.code} — ${payload.direction} from ${payload.address}")
                        Prefs.get(context).recordSuccess()
                    }
                    response.code in 400..499 -> {
                        Log.e(TAG, "4xx relay error ${response.code} — dropping message (check API key)")
                        Prefs.get(context).incrementError()
                    }
                    else -> {
                        Log.w(TAG, "5xx relay error ${response.code} — queuing for retry")
                        queueForRetry(payload)
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network error relaying — queuing for retry: ${e.message}")
            queueForRetry(payload)
        }
    }

    private suspend fun queueForRetry(payload: RelayPayload) {
        val payloadJson = JSONObject().apply {
            put("address", payload.address)
            put("body", payload.body)
            put("direction", payload.direction)
            put("timestamp", payload.timestamp)
            payload.name?.let { put("name", it) }
        }.toString()

        AppDatabase.get(context).pendingRelayDao().insert(
            PendingRelay(payloadJson = payloadJson)
        )

        val count = AppDatabase.get(context).pendingRelayDao().count()
        Prefs.get(context).pendingRetryCount = count

        val retryRequest = OneTimeWorkRequestBuilder<RetryWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork("keryx_relay_retry", ExistingWorkPolicy.KEEP, retryRequest)
    }

    companion object {
        @Volatile private var instance: RelayClient? = null

        fun get(context: Context): RelayClient = instance ?: synchronized(this) {
            instance ?: RelayClient(context.applicationContext).also { instance = it }
        }
    }
}
