package com.ryanpudd.photobooth

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages the on-disk photo upload queue: pending -> retry -> failed.
 * State lives entirely on the filesystem (directory + filename), so a process
 * restart just re-scans the folders - nothing to repair, nothing to duplicate.
 */
object UploadQueueManager {
    const val MAX_ATTEMPTS = 5
    private val BACKOFF_MS = longArrayOf(30_000, 60_000, 120_000, 300_000) // index = attempt-1, attempts 1..4

    private const val RETRY_DIR_NAME = ".upload_retry"
    private const val FAILED_DIR_NAME = ".upload_failed"
    private const val ATTEMPT_MARKER = "__u"

    private val FILENAME_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun pendingDir(context: Context): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "WeddingPhotobooth")

    fun retryDir(context: Context): File = File(pendingDir(context), RETRY_DIR_NAME)

    fun failedDir(context: Context): File = File(pendingDir(context), FAILED_DIR_NAME)

    /** Collision-safe IMG_yyyyMMdd_HHmmss_NNN.jpg name inside [dir]. */
    fun nextAvailableFile(dir: File, timestamp: Date = Date()): File {
        val stamp = FILENAME_DATE_FORMAT.format(timestamp)
        var counter = 1
        while (true) {
            val candidate = File(dir, "IMG_%s_%03d.jpg".format(stamp, counter))
            if (!candidate.exists()) return candidate
            counter++
        }
    }

    /** Oldest (by filename, which sorts chronologically) not-yet-attempted file, or null. */
    fun findNextPendingFile(pendingDir: File): File? =
        pendingDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
            ?.minByOrNull { it.name }

    /** Oldest retry file whose backoff window has elapsed, or null if none are due yet. */
    fun findNextDueRetryFile(retryDir: File, now: Long): File? =
        retryDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
            ?.filter { isDue(it, now) }
            ?.minByOrNull { it.lastModified() }

    private fun isDue(file: File, now: Long): Boolean {
        val attempt = attemptCount(file.name)
        val backoff = backoffMillisForAttempt(attempt)
        return now - file.lastModified() >= backoff
    }

    /** Number of failed attempts already recorded in [fileName]; 0 if none. */
    fun attemptCount(fileName: String): Int {
        val base = fileName.substringBeforeLast('.', fileName)
        val markerIndex = base.lastIndexOf(ATTEMPT_MARKER)
        if (markerIndex == -1) return 0
        return base.substring(markerIndex + ATTEMPT_MARKER.length).toIntOrNull() ?: 0
    }

    fun stripAttemptSuffix(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot >= 0) fileName.substring(0, dot) else fileName
        val ext = if (dot >= 0) fileName.substring(dot) else ""
        val markerIndex = base.lastIndexOf(ATTEMPT_MARKER)
        val strippedBase = if (markerIndex == -1) base else base.substring(0, markerIndex)
        return strippedBase + ext
    }

    fun withAttemptSuffix(fileName: String, attempt: Int): String {
        val stripped = stripAttemptSuffix(fileName)
        val dot = stripped.lastIndexOf('.')
        val base = if (dot >= 0) stripped.substring(0, dot) else stripped
        val ext = if (dot >= 0) stripped.substring(dot) else ""
        return "$base$ATTEMPT_MARKER$attempt$ext"
    }

    /** Backoff delay before attempt [attempt] (1-based) becomes due for retry. */
    fun backoffMillisForAttempt(attempt: Int): Long {
        if (attempt <= 0) return 0L
        val index = (attempt - 1).coerceIn(0, BACKOFF_MS.size - 1)
        return BACKOFF_MS[index]
    }

    /** Moves [file] into [retryDir], incrementing its attempt suffix and refreshing mtime. */
    fun moveToRetry(file: File, retryDir: File): File {
        val nextAttempt = attemptCount(file.name) + 1
        val target = File(retryDir, withAttemptSuffix(file.name, nextAttempt))
        file.renameTo(target)
        target.setLastModified(System.currentTimeMillis())
        return target
    }

    fun moveToFailed(file: File, failedDir: File): File {
        val target = File(failedDir, file.name)
        file.renameTo(target)
        return target
    }
}
