package app.keryx.bridge

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import app.keryx.bridge.network.RelayClient
import app.keryx.bridge.util.PhoneNormalizer
import app.keryx.bridge.util.Prefs
import java.time.Instant

private const val TAG = "KeryxNotifListener"
private const val GOOGLE_MESSAGES_PKG = "com.google.android.apps.messaging"

/**
 * Captures Google Messages notifications and relays them to Keryx.
 *
 * Received messages: title = sender name, text = message body
 * Outgoing RCS (best-effort): Google Messages sometimes posts a "sent" notification.
 *   Known patterns:
 *   - EXTRA_TITLE = "You" (some versions)
 *   - EXTRA_TITLE contains "Message sent" (some versions)
 *   These are highly version-dependent and may not fire on all devices.
 */
class KeryxNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != GOOGLE_MESSAGES_PKG) return

        val prefs = Prefs.get(applicationContext)
        if (!prefs.enabled || !prefs.isConfigured()) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Skip group summary notifications — they duplicate content
        val isGroupSummary = (notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) return

        val title = extras.getCharSequence("android.title")?.toString() ?: return
        val text = extras.getCharSequence("android.text")?.toString() ?: return

        if (text.isBlank()) return

        val timestampMs = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        val timestampIso = Instant.ofEpochMilli(timestampMs).toString()

        // Detect direction
        val isSentNotification = isSentRcsNotification(title, text)

        if (isSentNotification) {
            // Best-effort outgoing RCS
            val body = extractSentBody(text) ?: return
            Log.d(TAG, "Outgoing RCS notification detected (best-effort): \"${body.take(40)}\"")
            RelayClient.get(applicationContext).enqueue(
                RelayClient.RelayPayload(
                    address = "outgoing",
                    body = body,
                    direction = "sent",
                    timestamp = timestampIso,
                    name = null
                )
            )
        } else {
            // Standard received message
            val normalizedAddress = PhoneNormalizer.normalize(title)
            val senderName = if (normalizedAddress != title) null else title

            Log.d(TAG, "Received notification from \"$title\": \"${text.take(60)}\"")
            RelayClient.get(applicationContext).enqueue(
                RelayClient.RelayPayload(
                    address = normalizedAddress,
                    body = text,
                    direction = "received",
                    timestamp = timestampIso,
                    name = senderName
                )
            )
        }
    }

    /**
     * Detect if this is an outgoing-RCS "sent" or "sending" notification.
     * Google Messages posts transient progress notifications ("Sending") and
     * final confirmation notifications ("Sent"). We capture both — the relay
     * server deduplicates by conversation/timestamp.
     *
     * Known patterns (vary by Google Messages version):
     *   title == "You" — older versions
     *   title == "Message sent" | "Message sending" — newer versions
     *   title starts with "Sent" | "Sending" — some OEM variants
     *   text == "Message sent" | "Sending…"
     *   text starts with "Message sent to " | "Sending to "
     */
    private fun isSentRcsNotification(title: String, text: String): Boolean {
        val titleLower = title.lowercase()
        val textLower = text.lowercase()
        return titleLower == "you"
            || titleLower == "message sent"
            || titleLower == "message sending"
            || titleLower.startsWith("sent")
            || titleLower.startsWith("sending")
            || textLower == "message sent"
            || textLower == "sending"
            || textLower == "sending\u2026" // ellipsis variant
            || textLower.startsWith("message sent to ")
            || textLower.startsWith("sending to ")
            || textLower.startsWith("message sending")
    }

    private fun extractSentBody(text: String): String? {
        val textLower = text.lowercase()
        // Skip pure status strings that carry no body content
        if (textLower == "message sent"
            || textLower == "sending"
            || textLower == "sending\u2026") return null

        // "Message sent to John: Hello there" → "Hello there"
        // "Sending to John: Hello there" → "Hello there"
        val colonIdx = text.indexOf(':')
        return if (colonIdx > 0 && colonIdx < text.length - 1) {
            text.substring(colonIdx + 1).trim().takeIf { it.isNotBlank() }
        } else {
            text.takeIf { it.isNotBlank() }
        }
    }

    override fun onListenerConnected() {
        Log.d(TAG, "NotificationListenerService connected")
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "NotificationListenerService disconnected — will reconnect automatically")
    }
}
