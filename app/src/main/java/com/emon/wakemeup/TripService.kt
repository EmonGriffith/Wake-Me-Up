package com.emon.wakemeup

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Runs while a trip is on. Watches your position and rings the alarm when you are
 * close enough to your stop. Shows a small "Trip on" notification the whole time
 * (Android requires that for anything using location with the screen off).
 */
class TripService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var locationManager: LocationManager? = null
    private var target: SavedStop? = null
    private var currentInterval = 0L
    private var startedAt = 0L
    private var simulatedDistance = 0f

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    /** Desk test: pretend we get 200 m closer every second. */
    private val simulationTick = object : Runnable {
        override fun run() {
            simulatedDistance -= 200f
            onDistance(simulatedDistance.coerceAtLeast(0f))
            if (AppState.tripOn && !AppState.ringing) handler.postDelayed(this, 1000)
        }
    }

    private val autoOff = Runnable { endTrip() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppState.init(this)
        if (intent == null || intent.action == ACTION_STOP) {
            endTrip()
            return START_NOT_STICKY
        }

        val stop = SavedStop(
            intent.getStringExtra(EXTRA_NAME).orEmpty(),
            intent.getDoubleExtra(EXTRA_LAT, 0.0),
            intent.getDoubleExtra(EXTRA_LNG, 0.0),
        )
        val simulate = intent.getBooleanExtra(EXTRA_SIMULATE, false)
        target = stop
        startedAt = SystemClock.elapsedRealtime()

        AppState.tripStopName = stop.name
        AppState.tripIsDeskTest = simulate
        AppState.lastUpdate = ""
        AppState.distanceMeters = null
        AppState.tripNote = if (simulate) "Desk test: pretend distance" else "Waiting for GPS…"

        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(TRIP_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(TRIP_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Android refused (for example: location permission missing).
            AppState.tripNote = "Could not start the trip: ${e.message}"
            AppState.tripOn = false
            stopSelf()
            return START_NOT_STICKY
        }
        AppState.tripOn = true

        stopWatching()
        handler.removeCallbacks(autoOff)
        handler.postDelayed(autoOff, MAX_TRIP_MS)
        if (simulate) {
            simulatedDistance = AppState.radiusMeters + 4000f
            onDistance(simulatedDistance)
            handler.postDelayed(simulationTick, 1000)
        } else {
            locationManager = getSystemService(LocationManager::class.java)
            watch(INTERVAL_NEAR) // start fast so the first position arrives quickly
        }
        // If Android kills the app during a trip, it restarts the trip with the same stop.
        return START_REDELIVER_INTENT
    }

    /** Ask Android for a position every [intervalMs] milliseconds. */
    @SuppressLint("MissingPermission")
    private fun watch(intervalMs: Long) {
        val lm = locationManager ?: return
        currentInterval = intervalMs
        try {
            lm.removeUpdates(listener)
            var any = false
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.allProviders.contains(provider) && lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, intervalMs, 0f, listener, Looper.getMainLooper())
                    any = true
                }
            }
            if (!any) AppState.tripNote = "Location is switched off on the phone!"
        } catch (e: SecurityException) {
            AppState.tripNote = "Location permission is missing."
        }
    }

    private fun stopWatching() {
        handler.removeCallbacks(simulationTick)
        try { locationManager?.removeUpdates(listener) } catch (_: Exception) { }
    }

    private fun onFix(location: Location) {
        val stop = target ?: return
        // Ignore very rough positions (for example from phone masts only): they could ring too early.
        val roughLimit = maxOf(150f, AppState.radiusMeters / 2f)
        if (location.hasAccuracy() && location.accuracy > roughLimit) {
            if (AppState.distanceMeters == null) AppState.tripNote = "Waiting for a better GPS signal…"
            return
        }
        val result = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, stop.lat, stop.lng, result)
        onDistance(result[0])
    }

    private fun onDistance(distance: Float) {
        if (AppState.ringing || !AppState.tripOn) return
        val radius = AppState.radiusMeters
        AppState.lastUpdate = AppState.now()
        AppState.tripNote = if (AppState.tripIsDeskTest) "Desk test: pretend distance" else ""
        AppState.distanceMeters = distance

        if (distance <= radius) {
            stopWatching()
            handler.removeCallbacks(autoOff)
            AlarmPlayer.start(this, "${AppState.pretty(distance)} from ${AppState.tripStopName}")
            updateNotification()
            return // the service stays alive while the alarm rings; AlarmPlayer.stop() ends it
        }

        updateNotification()
        if (!AppState.tripIsDeskTest) {
            // Far away: check rarely (saves battery). Close: check often.
            val gap = distance - radius
            val wanted = when {
                gap > 3000f -> INTERVAL_FAR
                gap > 1000f -> INTERVAL_MID
                else -> INTERVAL_NEAR
            }
            if (wanted != currentInterval) watch(wanted)
        }
    }

    private fun endTrip() {
        if (AppState.ringing) AlarmPlayer.stop(this) // this also stops the service
        stopSelf()
    }

    override fun onDestroy() {
        stopWatching()
        handler.removeCallbacks(autoOff)
        AppState.distanceMeters = null
        AppState.tripOn = false
        super.onDestroy()
    }

    // ---------- the small "Trip on" notification ----------

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(TRIP_NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) { }
    }

    private fun buildNotification(): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(TRIP_CHANNEL, "Trip in progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows the distance to your stop while a trip is on"
            }
        )
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val end = PendingIntent.getService(
            this, 2,
            Intent(this, TripService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val distance = AppState.distanceMeters
        val text = when {
            AppState.ringing -> "Alarm ringing"
            distance == null -> AppState.tripNote.ifBlank { "Waiting for GPS…" }
            else -> "${AppState.pretty(distance)} to go · rings at ${AppState.pretty(AppState.radiusMeters.toFloat())}"
        }
        return NotificationCompat.Builder(this, TRIP_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Trip on: ${AppState.tripStopName}")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, "End trip", end)
            .build()
    }

    companion object {
        private const val TRIP_CHANNEL = "trip_progress"
        private const val TRIP_NOTIFICATION_ID = 43
        private const val ACTION_START = "com.emon.wakemeup.START_TRIP"
        private const val ACTION_STOP = "com.emon.wakemeup.END_TRIP"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LNG = "lng"
        private const val EXTRA_SIMULATE = "simulate"

        private const val INTERVAL_FAR = 60_000L  // more than 3 km before the ring point
        private const val INTERVAL_MID = 20_000L  // 1–3 km before the ring point
        private const val INTERVAL_NEAR = 5_000L  // last kilometre
        private const val MAX_TRIP_MS = 3 * 60 * 60 * 1000L // switch off after 3 hours

        fun start(context: Context, stop: SavedStop, simulate: Boolean) {
            val intent = Intent(context, TripService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_NAME, stop.name)
                .putExtra(EXTRA_LAT, stop.lat)
                .putExtra(EXTRA_LNG, stop.lng)
                .putExtra(EXTRA_SIMULATE, simulate)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TripService::class.java))
        }
    }
}
