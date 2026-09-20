package com.ryanpudd.photobooth

/**
 * Pure JSON construction for Discord webhook posts.
 *
 * Deliberately hand-rolled rather than using org.json: org.json ships as a
 * throwing stub in JVM unit tests, and keeping this file free of Android
 * imports is what makes it testable at all.
 */
object DiscordPayload {

    private const val WEBHOOK_PREFIX_CURRENT = "https://discord.com/api/webhooks/"
    private const val WEBHOOK_PREFIX_LEGACY = "https://discordapp.com/api/webhooks/"

    fun isValidWebhookUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith(WEBHOOK_PREFIX_CURRENT) || trimmed.startsWith(WEBHOOK_PREFIX_LEGACY)
    }

    fun escapeJson(raw: String): String {
        val sb = StringBuilder(raw.length + 16)
        for (c in raw) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * A webhook post only produces a phone push when it mentions someone, so
     * [mentionEveryone] is the difference between an alert and a silent log line.
     */
    fun build(content: String, mentionEveryone: Boolean): String {
        val parse = if (mentionEveryone) "\"everyone\"" else ""
        return """{"content":"${escapeJson(content)}","allowed_mentions":{"parse":[$parse]}}"""
    }
}
