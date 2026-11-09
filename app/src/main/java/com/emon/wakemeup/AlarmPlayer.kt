package com.emon.wakemeup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat

/** Rings, vibrates and shows the "WAKE UP" notification. */
object AlarmPlayer {
    private const val ALARM_CHANNEL = "wake_alarm"
    private const val ALARM_NOTIFICATION_ID = 42

    private var player: MediaPlayer? = null
    private var fallbackRingtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var savedVolume: Int? = null

    private val alarmAudio: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun start(context: Context, reason: String) {
        val app = context.applicationContext
        if (AppState.ringing) return
        AppState.ringing = true
        raiseAlarmVolume(app)
        playSound(app)
        vibrate(app)
        showAlarmNotification(app, reason)
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        player?.let {
            try { it.stop() } catch (_: Exception) { }
            it.release()
        }
        player = null
        fallbackRingtone?.stop()
        fallbackRingtone = null
        vibrator?.cancel()
        vibrator = null
        restoreVolume(app)
        app.getSystemService(NotificationManager::class.java).cancel(ALARM_NOTIFICATION_ID)
        AppState.ringing = false
        TripService.stop(app) // the trip is over once the alarm is stopped
    }

    private fun raiseAlarmVolume(context: Context) {
        val am = context.getSystemService(AudioManager::class.java)
        try {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {
            savedVolume = null
        }
    }

    private fun restoreVolume(context: Context) {
        val volume = savedVolume ?: return
        try {
            context.getSystemService(AudioManager::class.java)
                .setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
        } catch (_: Exception) { }
        savedVolume = null
    }

    private fun playSound(context: Context) {
        val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(alarmAudio)
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            player = null
            // Backup plan: play the phone's ringtone instead.
            try {
                fallbackRingtone = RingtoneManager.getRingtone(context, Settings.System.DEFAULT_RINGTONE_URI)?.apply {
                    audioAttributes = alarmAudio
                    if (Build.VERSION.SDK_INT >= 28) isLooping = true
                    play()
                }
            } catch (_: Exception) {
                AppState.tripNote = "Sound failed: ${e.message}"
            }
        }
    }

    private fun vibrate(context: Context) {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!v.hasVibrator()) return
        val pattern = VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0) // repeat forever
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(pattern, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, alarmAudio)
        }
        vibrator = v
    }

    private fun showAlarmNotification(context: Context, reason: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(ALARM_CHANNEL, "Wake-up alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings when you are close to your stop"
            setSound(null, null) // we play our own sound
            enableVibration(false) // we vibrate ourselves
        }
        nm.createNotificationChannel(channel)

        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAlarm = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, StopAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, ALARM_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("WAKE UP! Your stop is coming")
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setFullScreenIntent(openApp, true)
            .setDeleteIntent(stopAlarm)
            .addAction(0, "STOP ALARM", stopAlarm)
            .build()
        try {
            nm.notify(ALARM_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) { }
    }
}
