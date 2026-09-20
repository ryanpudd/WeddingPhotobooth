package com.ryanpudd.photobooth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Encrypted on-device storage for S3 upload credentials, entered via the admin
 * settings screen. Uses EncryptedSharedPreferences (stable 1.0.0 API) since these
 * are live AWS credentials, not just app preferences like WatchdogScheduler's.
 */
object CredentialsStore {
    private const val PREFS_NAME = "s3_credentials_prefs"
    private const val KEY_ACCESS_KEY_ID = "access_key_id"
    private const val KEY_SECRET_KEY = "secret_access_key"
    private const val KEY_BUCKET = "bucket_name"
    private const val KEY_REGION = "region"
    private const val KEY_PREFIX = "key_prefix"

    data class S3Config(
        val accessKeyId: String,
        val secretKey: String,
        val bucket: String,
        val region: String,
        val keyPrefix: String
    )

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

    fun save(context: Context, config: S3Config) {
        prefs(context).edit()
            .putString(KEY_ACCESS_KEY_ID, config.accessKeyId)
            .putString(KEY_SECRET_KEY, config.secretKey)
            .putString(KEY_BUCKET, config.bucket)
            .putString(KEY_REGION, config.region)
            .putString(KEY_PREFIX, config.keyPrefix)
            .apply()
    }

    /** Returns the stored config, or null if not fully configured yet. */
    fun load(context: Context): S3Config? {
        val p = prefs(context)
        val accessKeyId = p.getString(KEY_ACCESS_KEY_ID, "") ?: ""
        val secretKey = p.getString(KEY_SECRET_KEY, "") ?: ""
        val bucket = p.getString(KEY_BUCKET, "") ?: ""
        val region = p.getString(KEY_REGION, "") ?: ""
        val keyPrefix = p.getString(KEY_PREFIX, "") ?: ""
        if (accessKeyId.isBlank() || secretKey.isBlank() || bucket.isBlank() || region.isBlank()) return null
        return S3Config(accessKeyId, secretKey, bucket, region, keyPrefix)
    }

    /** Non-secret fields only, for prefilling the settings form without ever re-displaying the secret. */
    fun loadNonSecretFields(context: Context): S3Config {
        val p = prefs(context)
        return S3Config(
            accessKeyId = p.getString(KEY_ACCESS_KEY_ID, "") ?: "",
            secretKey = "",
            bucket = p.getString(KEY_BUCKET, "") ?: "",
            region = p.getString(KEY_REGION, "") ?: "",
            keyPrefix = p.getString(KEY_PREFIX, "") ?: ""
        )
    }

    fun hasStoredSecret(context: Context): Boolean =
        !prefs(context).getString(KEY_SECRET_KEY, "").isNullOrBlank()
}
