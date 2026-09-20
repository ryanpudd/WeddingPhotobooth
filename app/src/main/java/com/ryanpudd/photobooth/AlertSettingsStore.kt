package com.ryanpudd.photobooth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Encrypted on-device storage for the Discord webhook URL, entered via the
 * admin settings screen. Mirrors CredentialsStore: the webhook URL is a bearer
 * token, so it gets the same treatment as the AWS secret.
 */
object AlertSettingsStore {
    private const val PREFS_NAME = "alert_settings_prefs"
    private const val KEY_WEBHOOK_URL = "discord_webhook_url"

    private fun prefs(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveWebhookUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_WEBHOOK_URL, url.trim()).apply()
    }

    /** The stored webhook URL, or null if absent or not a Discord webhook. */
    fun loadWebhookUrl(context: Context): String? {
        val stored = prefs(context).getString(KEY_WEBHOOK_URL, "") ?: ""
        return if (DiscordPayload.isValidWebhookUrl(stored)) stored.trim() else null
    }

    fun hasWebhookUrl(context: Context): Boolean = loadWebhookUrl(context) != null
}
