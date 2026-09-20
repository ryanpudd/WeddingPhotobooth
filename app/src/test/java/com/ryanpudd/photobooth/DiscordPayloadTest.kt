package com.ryanpudd.photobooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordPayloadTest {

    @Test
    fun isValidWebhookUrl_acceptsDiscordHosts() {
        assertTrue(DiscordPayload.isValidWebhookUrl("https://discord.com/api/webhooks/123/abc"))
        assertTrue(DiscordPayload.isValidWebhookUrl("https://discordapp.com/api/webhooks/123/abc"))
    }

    @Test
    fun isValidWebhookUrl_rejectsAnythingElse() {
        assertFalse(DiscordPayload.isValidWebhookUrl(""))
        assertFalse(DiscordPayload.isValidWebhookUrl("http://discord.com/api/webhooks/123/abc"))
        assertFalse(DiscordPayload.isValidWebhookUrl("https://example.com/api/webhooks/123/abc"))
        assertFalse(DiscordPayload.isValidWebhookUrl("   "))
    }

    @Test
    fun escapeJson_escapesQuotesBackslashesAndNewlines() {
        assertEquals("""a\"b""", DiscordPayload.escapeJson("a\"b"))
        assertEquals("""a\\b""", DiscordPayload.escapeJson("a\\b"))
        assertEquals("""a\nb""", DiscordPayload.escapeJson("a\nb"))
        assertEquals("""a\tb""", DiscordPayload.escapeJson("a\tb"))
    }

    @Test
    fun escapeJson_escapesOtherControlCharacters() {
        assertEquals("""a\u0000b""", DiscordPayload.escapeJson("a\u0000b"))
    }

    @Test
    fun escapeJson_leavesPlainTextAlone() {
        assertEquals("Booth needs a battery swap", DiscordPayload.escapeJson("Booth needs a battery swap"))
    }

    @Test
    fun build_withMention_requestsEveryoneParse() {
        val json = DiscordPayload.build("camera lost", mentionEveryone = true)
        assertEquals(
            """{"content":"camera lost","allowed_mentions":{"parse":["everyone"]}}""",
            json
        )
    }

    @Test
    fun build_withoutMention_suppressesAllMentions() {
        val json = DiscordPayload.build("all clear", mentionEveryone = false)
        assertEquals(
            """{"content":"all clear","allowed_mentions":{"parse":[]}}""",
            json
        )
    }

    @Test
    fun build_escapesContent() {
        val json = DiscordPayload.build("line1\nline2 \"quoted\"", mentionEveryone = false)
        assertEquals(
            """{"content":"line1\nline2 \"quoted\"","allowed_mentions":{"parse":[]}}""",
            json
        )
    }
}
