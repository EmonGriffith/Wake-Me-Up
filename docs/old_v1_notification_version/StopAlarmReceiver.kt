package com.emon.wakemeup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Runs when you press "STOP ALARM" on the notification. */
class StopAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmPlayer.stop(context)
    }
}
