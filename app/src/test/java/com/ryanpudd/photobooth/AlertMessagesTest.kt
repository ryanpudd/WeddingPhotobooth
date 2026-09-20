package com.ryanpudd.photobooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertMessagesTest {

    @Test
    fun faultScreenText_isTheExactAgreedCopy() {
        assertEquals("Booth needs a battery swap, find someone to help", AlertMessages.FAULT_SCREEN_TEXT)
    }

    @Test
    fun disconnect_mentionsEveryoneAndExplainsTheFix() {
        val message = AlertMessages.disconnect(isDemoMode = false)
        assertTrue(message.contains("@everyone"))
        assertTrue(message.contains("battery"))
        assertFalse(message.contains("[TEST]"))
    }

    @Test
    fun disconnect_inDemoMode_isPrefixedSoHelpersDoNotPanic() {
        val message = AlertMessages.disconnect(isDemoMode = true)
        assertTrue(message.startsWith("[TEST]"))
        assertTrue(message.contains("@everyone"))
    }

    @Test
    fun allClear_saysTheBoothIsBackAndDoesNotMentionEveryone() {
        val message = AlertMessages.allClear(isDemoMode = false)
        assertTrue(message.contains("back"))
        assertFalse(message.contains("@everyone"))
        assertFalse(message.contains("[TEST]"))
    }

    @Test
    fun allClear_inDemoMode_isPrefixed() {
        assertTrue(AlertMessages.allClear(isDemoMode = true).startsWith("[TEST]"))
    }
}
