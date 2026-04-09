package app.keryx.bridge.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted preferences store for Keryx Bridge.
 * Stores server URL, API key, and runtime counters.
 *
 * If [EncryptedSharedPreferences] cannot be initialised (e.g. bad keystore state),
 * we throw rather than silently falling back to plaintext storage — API keys must
 * always be encrypted at rest.
 */
class Prefs private constructor(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "keryx_bridge_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var apiKey: String?
        get() = prefs.getString(KEY_API_KEY, null)
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var lastSentSmsId: Long
        get() = prefs.getLong(KEY_LAST_SMS_ID, -1L)
        set(value) = prefs.edit().putLong(KEY_LAST_SMS_ID, value).apply()

    var lastRelayedAt: Long
        get() = prefs.getLong(KEY_LAST_RELAYED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_RELAYED_AT, value).apply()

    var relayedTodayCount: Int
        get() = prefs.getInt(KEY_RELAYED_TODAY, 0)
        set(value) = prefs.edit().putInt(KEY_RELAYED_TODAY, value).apply()

    var relayedTodayDate: String?
        get() = prefs.getString(KEY_RELAYED_TODAY_DATE, null)
        set(value) = prefs.edit().putString(KEY_RELAYED_TODAY_DATE, value).apply()

    var errorCount: Int
        get() = prefs.getInt(KEY_ERROR_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_ERROR_COUNT, value).apply()

    var permanentFailureCount: Int
        get() = prefs.getInt(KEY_PERM_FAILURE_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_PERM_FAILURE_COUNT, value).apply()

    var pendingRetryCount: Int
        get() = prefs.getInt(KEY_PENDING_RETRY, 0)
        set(value) = prefs.edit().putInt(KEY_PENDING_RETRY, value).apply()

    fun recordSuccess() {
        lastRelayedAt = System.currentTimeMillis()
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        if (relayedTodayDate != today) {
            relayedTodayDate = today
            relayedTodayCount = 1
        } else {
            relayedTodayCount++
        }
    }

    fun incrementError() {
        errorCount++
    }

    fun incrementPermanentFailure() {
        permanentFailureCount++
        incrementError()
    }

    fun isConfigured(): Boolean = !serverUrl.isNullOrBlank() && !apiKey.isNullOrBlank()

    companion object {
        private const val TAG = "Prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_SMS_ID = "last_sent_sms_id"
        private const val KEY_LAST_RELAYED_AT = "last_relayed_at"
        private const val KEY_RELAYED_TODAY = "relayed_today"
        private const val KEY_RELAYED_TODAY_DATE = "relayed_today_date"
        private const val KEY_ERROR_COUNT = "error_count"
        private const val KEY_PERM_FAILURE_COUNT = "perm_failure_count"
        private const val KEY_PENDING_RETRY = "pending_retry"

        @Volatile private var instance: Prefs? = null

        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(context.applicationContext).also { instance = it }
        }
    }
}
