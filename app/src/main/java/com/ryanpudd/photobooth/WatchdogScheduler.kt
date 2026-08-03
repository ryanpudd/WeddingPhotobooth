package com.ryanpudd.photobooth

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object WatchdogScheduler {
    private const val PREFS_NAME = "watchdog_prefs"
    private const val KEY_LAST_HEARTBEAT = "last_heartbeat"
    private const val TIMEOUT_MS = 30000L

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val heartbeatIntent = Intent(context, HeartbeatReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, heartbeatIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 5000,
            15000, // 15 second interval
            pendingIntent
        )
    }

    fun updateHeartbeat(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis()).apply()
    }

    fun shouldRestart(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastHeartbeat = prefs.getLong(KEY_LAST_HEARTBEAT, 0L)
        return System.currentTimeMillis() - lastHeartbeat > TIMEOUT_MS
    }
}
