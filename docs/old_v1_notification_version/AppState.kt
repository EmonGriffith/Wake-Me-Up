package com.emon.wakemeup

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Google Maps' Android package name. The only app we listen to. */
const val MAPS_PACKAGE = "com.google.android.apps.maps"


/** One line in the on-screen log. Kept in memory only, never saved or sent anywhere. */
data class LogEntry(
    val time: String,
    val source: String,
    val text: String,
    val details: String,
    val decision: String,
)

/** Shared, in-memory state that the screen and the listener both use. */
object AppState {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The screen sets this so it can redraw when something changes. */
    var onChange: (() -> Unit)? = null

    private var loaded = false
    private const val PREFS = "wakemeup_settings"

    /** Load the saved stop choice (only a number is saved on the phone, nothing else). */
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        triggerStops = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("trigger_stops", 2)
    }

    fun setTrigger(context: Context, stops: Int) {
        triggerStops = stops
        armed = true
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt("trigger_stops", triggerStops).apply()
    }

    /** The alarm rings when this many stops (or fewer) are left. */
    var triggerStops = 2
        set(v) { field = v.coerceIn(1, 10); changed() }

    val log = mutableListOf<LogEntry>()
    var accessGranted = false
        set(v) { field = v; changed() }
    var notificationsAllowed = true
        set(v) { field = v; changed() }
    var ringing = false
        set(v) { field = v; changed() }
    var armed = true
        set(v) { field = v; changed() }
    var lastDecision = "No Google Maps notification seen yet."
        set(v) { field = v; changed() }

    fun addLog(entry: LogEntry) {
        log.add(0, entry)
        while (log.size > 40) log.removeAt(log.size - 1)
        changed()
    }

    fun changed() {
        mainHandler.post { onChange?.invoke() }
    }

    fun now(): String = SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date())
}

/** Finds "2 stops", "Get off in 2 stops", "two stops", "1 stop" etc. in notification text. */
object StopParser {
    private val digitRegex = Regex("""\b(\d+)\s*stops?\b""", RegexOption.IGNORE_CASE)
    private val wordRegex = Regex("""\b(one|two|three|four|five)\s+stops?\b""", RegexOption.IGNORE_CASE)
    private val words = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5)

    /** Returns the number of stops found in the text, or null if there is none. */
    fun stopsRemaining(text: String): Int? {
        digitRegex.find(text)?.let { return it.groupValues[1].toIntOrNull() }
        wordRegex.find(text)?.let { return words[it.groupValues[1].lowercase()] }
        return null
    }
}
