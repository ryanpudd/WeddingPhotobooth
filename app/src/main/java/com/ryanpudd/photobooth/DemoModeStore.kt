package com.ryanpudd.photobooth

import android.content.Context

/**
 * Demo mode toggle. Plain SharedPreferences, not encrypted: it is a switch,
 * not a secret. Persists across restarts by design - the overnight drain test
 * needs it to survive, and setup happens on the day so it will be noticed.
 */
object DemoModeStore {
    private const val PREFS_NAME = "demo_mode_prefs"
    private const val KEY_ENABLED = "demo_mode_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
