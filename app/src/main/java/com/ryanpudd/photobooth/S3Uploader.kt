package com.ryanpudd.photobooth

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import java.io.File

sealed class UploadResult {
    object Success : UploadResult()
    data class Failure(val reason: String, val exception: Exception? = null) : UploadResult()
}

/** Blocking S3 upload of a single file. Must only ever be called off the main thread. */
class S3Uploader(private val config: CredentialsStore.S3Config) {

    fun upload(file: File): UploadResult {
        return try {
            val credentials = BasicAWSCredentials(config.accessKeyId, config.secretKey)
            val clientConfig = ClientConfiguration().apply {
                connectionTimeout = CONNECT_TIMEOUT_MS
                socketTimeout = SOCKET_TIMEOUT_MS
            }
            val s3 = AmazonS3Client(credentials, clientConfig).apply {
                setRegion(Region.getRegion(Regions.fromName(config.region)))
            }
            val key = S3KeyBuilder.buildKey(config.keyPrefix, file.name)
            s3.putObject(config.bucket, key, file)
            UploadResult.Success
        } catch (e: Exception) {
            UploadResult.Failure(e.message ?: e.javaClass.simpleName, e)
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val SOCKET_TIMEOUT_MS = 30_000
    }
}
