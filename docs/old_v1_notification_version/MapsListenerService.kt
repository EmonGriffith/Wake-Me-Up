package com.emon.wakemeup

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Android calls this service every time any notification appears.
 * We ignore everything except Google Maps (and our own fake test notifications).
 */
class MapsListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        AppState.init(this)
        AppState.accessGranted = true
    }

    override fun onListenerDisconnected() {
        AppState.accessGranted = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        AppState.init(this)
        val n = sbn.notification ?: return
        val extras = n.extras

        val isMaps = sbn.packageName == MAPS_PACKAGE
        val isOurTest = sbn.packageName == packageName && extras.getBoolean(EXTRA_TEST, false)
        if (!isMaps && !isOurTest) return // every other app is ignored

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(" / ").orEmpty()
        val ticker = n.tickerText?.toString().orEmpty()

        val combined = listOf(title, text, bigText, subText, infoText, lines, ticker)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" | ")

        @Suppress("DEPRECATION")
        val details = buildString {
            append("id=${sbn.id}  ongoing=${sbn.isOngoing}  channel=${n.channelId}\n")
            append("customView=${n.contentView != null || n.bigContentView != null}\n")
            for (key in extras.keySet()) {
                append(key).append(" = ").append(extras.get(key).toString().take(200)).append('\n')
            }
        }

        val decision = decide(combined)
        AppState.lastDecision = "${AppState.now()}  $decision"
        AppState.addLog(
            LogEntry(
                time = AppState.now(),
                source = if (isMaps) "Google Maps" else "FAKE TEST",
                text = combined.ifBlank { "(no readable text)" },
                details = details,
                decision = decision,
            )
        )
    }

    /** Decides whether to ring. Returns a short reason that is shown on screen. */
    private fun decide(text: String): String {
        val stops = StopParser.stopsRemaining(text)
            ?: return "No stop count in this notification."

        if (stops > AppState.triggerStops) {
            AppState.armed = true // still far away: get ready to ring later
            return "$stops stops left. Waiting for ${AppState.triggerStops}."
        }
        if (!AppState.armed) {
            return "$stops stops left. Already rang for this journey."
        }
        AppState.armed = false
        AlarmPlayer.start(this, if (stops == 1) "1 stop remaining" else "$stops stops remaining")
        return "ALARM! $stops stops left."
    }

    companion object {
        const val EXTRA_TEST = "com.emon.wakemeup.FAKE_TEST"
    }
}
