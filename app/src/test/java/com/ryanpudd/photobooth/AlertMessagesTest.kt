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

    @Test
    fun powerLost_mentionsEveryoneAndExplainsTheFix() {
        val message = AlertMessages.powerLost(isDemoMode = false)
        assertTrue(message.contains("@everyone"))
        assertTrue(message.contains("power"))
        assertFalse(message.contains("[TEST]"))
    }

    @Test
    fun powerLost_inDemoMode_isPrefixedSoHelpersDoNotPanic() {
        val message = AlertMessages.powerLost(isDemoMode = true)
        assertTrue(message.startsWith("[TEST]"))
        assertTrue(message.contains("@everyone"))
    }

    @Test
    fun powerRestored_saysPowerIsBackAndDoesNotMentionEveryone() {
        val message = AlertMessages.powerRestored(isDemoMode = false)
        assertTrue(message.contains("power"))
        assertFalse(message.contains("@everyone"))
        assertFalse(message.contains("[TEST]"))
    }

    @Test
    fun powerRestored_inDemoMode_isPrefixed() {
        assertTrue(AlertMessages.powerRestored(isDemoMode = true).startsWith("[TEST]"))
    }

    @Test
    fun lowBatteryScreenText_isTheExactAgreedCopy() {
        assertEquals(
            "😴 Whoa there, superstars — you've partied my battery right into the " +
                "ground. I need a little nap. Find someone with a charger and I'll be back for more " +
                "smiles soon!",
            AlertMessages.LOW_BATTERY_SCREEN_TEXT
        )
    }

    @Test
    fun lowBatteryLock_mentionsEveryoneAndExplainsTheFix() {
        val message = AlertMessages.lowBatteryLock(isDemoMode = false)
        assertTrue(message.contains("@everyone"))
        assertTrue(message.contains("battery"))
        assertFalse(message.contains("[TEST]"))
    }

    @Test
    fun lowBatteryLock_inDemoMode_isPrefixedSoHelpersDoNotPanic() {
        val message = AlertMessages.lowBatteryLock(isDemoMode = true)
        assertTrue(message.startsWith("[TEST]"))
        assertTrue(message.contains("@everyone"))
    }

    @Test
    fun lowBatteryUnlock_saysItsBackAndDoesNotMentionEveryone() {
        val message = AlertMessages.lowBatteryUnlock(isDemoMode = false)
        assertTrue(message.contains("back"))
        assertFalse(message.contains("@everyone"))
        assertFalse(message.contains("[TEST]"))
    }

    @Test
    fun lowBatteryUnlock_inDemoMode_isPrefixed() {
        assertTrue(AlertMessages.lowBatteryUnlock(isDemoMode = true).startsWith("[TEST]"))
    }
}
