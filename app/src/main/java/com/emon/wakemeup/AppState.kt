package com.emon.wakemeup

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** One stop you saved: a name and its position on Earth (latitude / longitude). */
data class SavedStop(val name: String, val lat: Double, val lng: Double)

/**
 * Shared state that the screen and the trip service both use.
 * Saved on the phone only (SharedPreferences): your stops, which one is picked, and the distance.
 * Your live location is never saved and never sent anywhere.
 */
object AppState {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The screen sets this so it can redraw when something changes. */
    var onChange: (() -> Unit)? = null

    private var loaded = false
    private const val PREFS = "wakemeup_settings"

    /** The distances you can choose from, in metres. */
    val radiusOptions = listOf(300, 500, 750, 1000, 1500, 2000, 3000)

    // ---------- saved on the phone ----------
    val stops = mutableListOf<SavedStop>()
    var selectedIndex = -1
        private set
    var radiusMeters = 1000
        private set

    val selectedStop: SavedStop? get() = stops.getOrNull(selectedIndex)

    // ---------- live, in memory only ----------
    var locationAllowed = false
        set(v) { field = v; changed() }
    var approximateOnly = false
        set(v) { field = v; changed() }
    var locationSwitchedOn = true
        set(v) { field = v; changed() }
    var notificationsAllowed = true
        set(v) { field = v; changed() }
    var ringing = false
        set(v) { field = v; changed() }
    var tripOn = false
        set(v) { field = v; changed() }
    var tripIsDeskTest = false
    var tripStopName = ""
    /** Distance to the stop in metres, or null when we have no position yet. */
    var distanceMeters: Float? = null
        set(v) { field = v; changed() }
    var lastUpdate = ""
    var tripNote = ""
        set(v) { field = v; changed() }

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        radiusMeters = prefs.getInt("radius_m", 1000).let { if (it in radiusOptions) it else 1000 }
        stops.clear()
        try {
            val arr = JSONArray(prefs.getString("stops", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                stops.add(SavedStop(o.getString("n"), o.getDouble("lat"), o.getDouble("lng")))
            }
        } catch (_: Exception) { }
        selectedIndex = prefs.getInt("selected", -1).coerceIn(-1, stops.size - 1)
        if (selectedIndex == -1 && stops.isNotEmpty()) selectedIndex = 0
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        for (s in stops) arr.put(JSONObject().put("n", s.name).put("lat", s.lat).put("lng", s.lng))
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("stops", arr.toString())
            .putInt("selected", selectedIndex)
            .putInt("radius_m", radiusMeters)
            .apply()
        changed()
    }

    fun addStop(context: Context, stop: SavedStop) {
        stops.add(stop)
        selectedIndex = stops.size - 1
        save(context)
    }

    fun deleteSelected(context: Context) {
        if (selectedIndex !in stops.indices) return
        stops.removeAt(selectedIndex)
        selectedIndex = if (stops.isEmpty()) -1 else 0
        save(context)
    }

    fun select(context: Context, index: Int) {
        if (index !in stops.indices) return
        selectedIndex = index
        save(context)
    }

    /** Moves the distance one step up (+1) or down (-1) through radiusOptions. */
    fun stepRadius(context: Context, direction: Int) {
        val i = (radiusOptions.indexOf(radiusMeters) + direction).coerceIn(0, radiusOptions.size - 1)
        radiusMeters = radiusOptions[i]
        save(context)
    }

    fun changed() {
        mainHandler.post { onChange?.invoke() }
    }

    fun now(): String = SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date())

    /** 850 -> "850 m", 3200 -> "3.2 km". */
    fun pretty(meters: Float): String =
        if (meters < 1000f) "${(meters / 10f).roundToInt() * 10} m"
        else String.format(Locale.UK, "%.1f km", meters / 1000f)
}
