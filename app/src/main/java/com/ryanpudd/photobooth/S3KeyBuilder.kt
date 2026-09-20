package com.ryanpudd.photobooth

/**
 * Builds the S3 object key for a queued photo. Demo captures land under a
 * `demo/` folder so a test run can be deleted in one click without touching
 * real wedding photos.
 */
object S3KeyBuilder {

    fun buildKey(keyPrefix: String, fileName: String): String {
        val parts = mutableListOf<String>()
        val prefix = keyPrefix.trim().trim('/')
        if (prefix.isNotEmpty()) parts.add(prefix)
        if (UploadQueueManager.isDemoFile(fileName)) parts.add("demo")
        parts.add(fileName)
        return parts.joinToString("/")
    }
}
