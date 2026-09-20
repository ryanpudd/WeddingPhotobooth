package com.ryanpudd.photobooth

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Fire-and-forget Discord webhook sender.
 *
 * No retries by design: a battery alert that lands 40 minutes late is noise,
 * and the full-screen message is the primary channel. Failures are logged
 * (not surfaced or retried) so a dead webhook leaves a trace instead of
 * vanishing silently — see the `onFailure`/status-code checks in [send].
 */
class DiscordNotifier(private val webhookUrlProvider: () -> String?) {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DiscordNotifier").apply { isDaemon = true }
    }

    /** Queues a send on the background executor. Never throws, never blocks the caller. */
    fun send(content: String, mentionEveryone: Boolean) {
        val url = webhookUrlProvider() ?: return
        if (!DiscordPayload.isValidWebhookUrl(url)) return
        executor.execute {
            runCatching { sendBlocking(url, content, mentionEveryone) }
                .onSuccess { code ->
                    if (code !in 200..299) {
                        Log.w(TAG, "Discord webhook responded with non-success status $code")
                    }
                }
                .onFailure { Log.w(TAG, "Discord send failed", it) }
        }
    }

    /** Returns the HTTP status code. Discord answers 204 on success. Must not run on the UI thread. */
    fun sendBlocking(url: String, content: String, mentionEveryone: Boolean): Int {
        val body = DiscordPayload.build(content, mentionEveryone).toByteArray(Charsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "WeddingPhotobooth/1.0")
        }
        return try {
            connection.outputStream.use { it.write(body) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "DiscordNotifier"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
    }
}
