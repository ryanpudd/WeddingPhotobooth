package com.ryanpudd.photobooth

import android.content.Context

/**
 * Inverted mount toggle. Plain SharedPreferences, not encrypted: it is a switch,
 * not a secret. Persists across restarts by design - a reboot or a USB-attach
 * auto-launch must bring the booth back up the same way round it was mounted.
 */
object InvertedMountStore {
    private const val PREFS_NAME = "inverted_mount_prefs"
    private const val KEY_ENABLED = "inverted_mount_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
