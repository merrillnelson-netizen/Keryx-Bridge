package app.keryx.bridge

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.keryx.bridge.databinding.ActivityMainBinding
import app.keryx.bridge.network.RelayClient
import app.keryx.bridge.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { Prefs.get(this) }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshStatus() }

    private val readSmsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "READ_SMS permission is required to capture sent messages",
                Toast.LENGTH_LONG
            ).show()
        }
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadPrefsIntoUI()
        setupClickListeners()
        requestRequiredPermissions()
        refreshStatus()

        lifecycleScope.launch {
            while (isActive) {
                delay(5_000)
                refreshStatus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun loadPrefsIntoUI() {
        binding.editServerUrl.setText(prefs.serverUrl ?: "")
        binding.editApiKey.setText(prefs.apiKey ?: "")
        binding.switchEnabled.isChecked = prefs.enabled
    }

    private fun requestRequiredPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            readSmsPermissionLauncher.launch(Manifest.permission.READ_SMS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setupClickListeners() {
        binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked && !isReadSmsGranted()) {
                binding.switchEnabled.isChecked = false
                Toast.makeText(
                    this,
                    "READ_SMS permission required — tap Grant SMS Permission first",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnCheckedChangeListener
            }
            prefs.enabled = checked
            if (checked) {
                saveConfig()
                startBridgeService()
            } else {
                stopService(Intent(this, SmsObserverService::class.java))
            }
            refreshStatus()
        }

        binding.btnSave.setOnClickListener { saveConfig() }
        binding.btnTestConnection.setOnClickListener { runConnectionTest() }

        binding.btnGrantNotificationAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.btnGrantSmsPermission.setOnClickListener {
            readSmsPermissionLauncher.launch(Manifest.permission.READ_SMS)
        }

        binding.btnBatteryOptimization.setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        binding.btnGrantNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun saveConfig() {
        val serverUrl = binding.editServerUrl.text.toString().trim().trimEnd('/')
        val apiKey = binding.editApiKey.text.toString().trim()
        if (serverUrl.isBlank() || apiKey.isBlank()) {
            Toast.makeText(this, "Please fill in Server URL and API Key", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.serverUrl = serverUrl
        prefs.apiKey = apiKey
        Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun startBridgeService() {
        val intent = Intent(this, SmsObserverService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun runConnectionTest() {
        val serverUrl = binding.editServerUrl.text.toString().trim().trimEnd('/')
        val apiKey = binding.editApiKey.text.toString().trim()
        if (serverUrl.isBlank() || apiKey.isBlank()) {
            binding.tvTestResult.text = "Enter Server URL and API Key first"
            binding.tvTestResult.visibility = View.VISIBLE
            return
        }
        binding.btnTestConnection.isEnabled = false
        binding.tvTestResult.text = "Testing..."
        binding.tvTestResult.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build()
                    val body = JSONObject().apply {
                        put("type", "event")
                        put("source", "android-bridge")
                        put("payload", JSONObject().apply {
                            put("action", "connection_test")
                            put("device", "android-bridge/v1")
                        })
                    }.toString()
                    val request = Request.Builder()
                        .url("$serverUrl/api/relay/inbound")
                        .addHeader("X-API-Key", apiKey)
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                    val response = client.newCall(request).execute()
                    response.use {
                        if (response.isSuccessful) "Connected! HTTP ${response.code}"
                        else "Error: HTTP ${response.code} — check your API key"
                    }
                } catch (e: Exception) {
                    "Failed: ${e.message}"
                }
            }
            binding.tvTestResult.text = result
            binding.btnTestConnection.isEnabled = true
        }
    }

    private fun refreshStatus() {
        // --- Notification listener ---
        val notifEnabled = isNotificationListenerEnabled()
        binding.rowNotifAccess.statusDot.setBackgroundResource(
            if (notifEnabled) R.drawable.dot_green else R.drawable.dot_red
        )
        binding.rowNotifAccess.tvPermissionStatus.text =
            if (notifEnabled) "Granted — capturing Google Messages" else "Not granted — tap to open settings"
        binding.btnGrantNotificationAccess.visibility =
            if (notifEnabled) View.GONE else View.VISIBLE

        // --- READ_SMS permission ---
        val readSmsGranted = isReadSmsGranted()
        binding.rowReadSms.statusDot.setBackgroundResource(
            if (readSmsGranted) R.drawable.dot_green else R.drawable.dot_red
        )
        binding.rowReadSms.tvPermissionStatus.text =
            if (readSmsGranted) "Granted — capturing sent SMS/MMS"
            else "Not granted — required for outgoing SMS capture"
        binding.btnGrantSmsPermission.visibility =
            if (readSmsGranted) View.GONE else View.VISIBLE

        // --- Battery optimization ---
        val batteryOptExempt = isBatteryOptimizationExempt()
        binding.rowBattery.statusDot.setBackgroundResource(
            if (batteryOptExempt) R.drawable.dot_green else R.drawable.dot_amber
        )
        binding.rowBattery.tvPermissionStatus.text =
            if (batteryOptExempt) "Unrestricted — bridge will survive background"
            else "Restricted — tap to allow unrestricted battery usage"
        binding.btnBatteryOptimization.visibility =
            if (batteryOptExempt) View.GONE else View.VISIBLE

        // --- POST_NOTIFICATIONS (Android 13+) ---
        val notifPermGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        binding.rowNotifPermission.root.visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) View.VISIBLE else View.GONE
        binding.rowNotifPermission.statusDot.setBackgroundResource(
            if (notifPermGranted) R.drawable.dot_green else R.drawable.dot_amber
        )
        binding.rowNotifPermission.tvPermissionStatus.text =
            if (notifPermGranted) "Granted" else "Needed for persistent notification"
        binding.btnGrantNotifications.visibility =
            if (!notifPermGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                View.VISIBLE else View.GONE

        // --- Overall bridge status ---
        val isReady = notifEnabled && readSmsGranted && prefs.isConfigured() && prefs.enabled
        binding.tvBridgeStatus.text = when {
            !prefs.isConfigured() -> "Not configured"
            !readSmsGranted -> "READ_SMS permission required"
            !prefs.enabled -> "Disabled"
            !notifEnabled -> "Waiting for notification access"
            else -> "Active"
        }
        binding.tvBridgeStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (isReady) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )
        )

        // --- Stats ---
        val lastRelayed = prefs.lastRelayedAt
        binding.tvLastRelayed.text = if (lastRelayed > 0) {
            "Last relayed: ${DateUtils.getRelativeTimeSpanString(lastRelayed)}"
        } else {
            "Last relayed: never"
        }
        binding.tvRelayedToday.text = "Relayed today: ${prefs.relayedTodayCount}"
        binding.tvPendingRetry.text = "Pending retry: ${prefs.pendingRetryCount}"
        binding.tvErrors.text = "Errors: ${prefs.errorCount}  |  Permanently failed: ${prefs.permanentFailureCount}"
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val component = ComponentName(this, KeryxNotificationListener::class.java)
        return flat.contains(component.flattenToString()) || flat.contains(component.flattenToShortString())
    }

    private fun isReadSmsGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private fun isBatteryOptimizationExempt(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }
}
