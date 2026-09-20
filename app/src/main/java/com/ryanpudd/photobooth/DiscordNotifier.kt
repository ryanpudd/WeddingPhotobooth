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
        val url = webhookUrlProvider()
        if (url == null) {
            // The most likely failure of all, and previously the only silent one.
            Log.w(TAG, "Discord send skipped: no webhook URL configured (or it could not be read)")
            return
        }
        if (!DiscordPayload.isValidWebhookUrl(url)) {
            Log.w(TAG, "Discord send skipped: stored webhook URL is not a valid Discord webhook")
            return
        }
        executor.execute {
            runCatching { sendBlocking(url, content, mentionEveryone) }
                .onSuccess { code ->
                    if (code !in 200..299) {
                        Log.w(TAG, "Discord webhook responded with non-success status $code")
                    }
                }
                // Never log the throwable itself: some IOException subclasses put the full
                // request URL — which contains the webhook secret — in getMessage().
                .onFailure { Log.w(TAG, "Discord send failed: ${it.javaClass.simpleName}: ${redact(it.message)}") }
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

    /**
     * Exception messages from the HTTP stack routinely echo the request URL, and the
     * webhook URL's last path segment is a bearer token. Strip anything URL-shaped
     * before it reaches logcat.
     */
    private fun redact(message: String?): String {
        if (message.isNullOrBlank()) return "(no message)"
        return URL_PATTERN.replace(message, "<redacted-url>")
            .let { WEBHOOK_PATH_PATTERN.replace(it, "/api/webhooks/<redacted>") }
    }

    companion object {
        private const val TAG = "DiscordNotifier"
        private val URL_PATTERN = Regex("""\bhttps?://\S+""", RegexOption.IGNORE_CASE)
        /** Catches a scheme-less "discord.com/api/webhooks/<id>/<token>" too. */
        private val WEBHOOK_PATH_PATTERN = Regex("""/api/webhooks/\S*""", RegexOption.IGNORE_CASE)
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
    }
}
