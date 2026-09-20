package com.ryanpudd.photobooth

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Background upload queue processor. Runs on its own HandlerThread (mirrors the
 * existing heartbeatRunnable pattern) so venue wifi latency/failures can never
 * block the capture/UI thread. Polls the on-disk queue maintained by
 * UploadQueueManager; never caches credentials so a settings change takes effect
 * on the very next tick.
 */
class UploadWorker(private val context: Context) {

    companion object {
        private const val TAG = "UploadWorker"
        private const val POLL_INTERVAL_MS = 20_000L
    }

    private val workerThread = HandlerThread("UploadWorker").apply { start() }
    private val handler = Handler(workerThread.looper)
    @Volatile private var running = false

    private val tick = object : Runnable {
        override fun run() {
            runCatching { runOneUploadAttempt() }
                .onFailure { Log.e(TAG, "upload tick failed", it) }
            if (running) handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    /** Wakes the queue immediately instead of waiting for the next poll tick. */
    fun kickNow() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun runOneUploadAttempt() {
        if (!isNetworkConnected()) return

        val config = CredentialsStore.load(context) ?: return

        val pendingDir = UploadQueueManager.pendingDir(context)
        val retryDir = UploadQueueManager.retryDir(context)
        val failedDir = UploadQueueManager.failedDir(context)
        if (!retryDir.exists()) retryDir.mkdirs()
        if (!failedDir.exists()) failedDir.mkdirs()

        val file = UploadQueueManager.findNextPendingFile(pendingDir)
            ?: UploadQueueManager.findNextDueRetryFile(retryDir, System.currentTimeMillis())
            ?: return

        when (val result = S3Uploader(config).upload(file)) {
            is UploadResult.Success -> file.delete()
            is UploadResult.Failure -> {
                Log.w(TAG, "upload failed for ${file.name}: ${result.reason}")
                val nextAttempt = UploadQueueManager.attemptCount(file.name) + 1
                if (nextAttempt >= UploadQueueManager.MAX_ATTEMPTS) {
                    UploadQueueManager.moveToFailed(file, failedDir)
                } else {
                    UploadQueueManager.moveToRetry(file, retryDir)
                }
            }
        }
    }

    private fun isNetworkConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.isConnected == true
    }
}
