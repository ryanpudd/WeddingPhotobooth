package com.ryanpudd.photobooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowBatteryPolicyTest {

    private fun shouldBeLocked(
        currentlyLocked: Boolean = false,
        currentState: BoothState = BoothState.IDLE,
        batteryPercent: Int
    ): Boolean = LowBatteryPolicy.shouldBeLocked(currentlyLocked, currentState, batteryPercent)

    @Test
    fun constantsMatchTheAgreedValues() {
        assertEquals(20, LowBatteryPolicy.LOCK_AT_PERCENT)
        assertEquals(50, LowBatteryPolicy.UNLOCK_AT_PERCENT)
    }

    @Test
    fun exactlyAtTheLockThreshold_locks() {
        assertTrue(shouldBeLocked(batteryPercent = 20))
    }

    @Test
    fun oneAboveTheLockThreshold_doesNotLock() {
        assertFalse(shouldBeLocked(batteryPercent = 21))
    }

    @Test
    fun wellBelowTheLockThreshold_locks() {
        assertTrue(shouldBeLocked(batteryPercent = 5))
    }

    @Test
    fun neverLocksOutsideIdle_evenAtZeroPercent() {
        assertFalse(shouldBeLocked(currentState = BoothState.CAPTURING, batteryPercent = 0))
        assertFalse(shouldBeLocked(currentState = BoothState.REVIEW, batteryPercent = 0))
    }

    @Test
    fun onceLocked_stayingBelowUnlockThreshold_staysLocked() {
        assertTrue(shouldBeLocked(currentlyLocked = true, batteryPercent = 49))
    }

    @Test
    fun onceLocked_exactlyAtTheUnlockThreshold_unlocks() {
        assertFalse(shouldBeLocked(currentlyLocked = true, batteryPercent = 50))
    }

    @Test
    fun onceLocked_wellAboveTheUnlockThreshold_unlocks() {
        assertFalse(shouldBeLocked(currentlyLocked = true, batteryPercent = 90))
    }

    @Test
    fun onceLocked_unlockGateIgnoresBoothState() {
        // Unlocking never interrupts anything, so it isn't gated on IDLE the way locking is.
        assertFalse(shouldBeLocked(currentlyLocked = true, currentState = BoothState.CAPTURING, batteryPercent = 60))
    }

    @Test
    fun onceUnlocked_mustReachLockThresholdAgain_notJustBelowUnlockThreshold() {
        // 45% is below the 50% unlock threshold, but above the 20% lock threshold — must not re-lock.
        assertFalse(shouldBeLocked(currentlyLocked = false, batteryPercent = 45))
    }

    @Test
    fun invalidReading_whileUnlocked_neverLocks() {
        // BatteryManager.getIntProperty can return -1 when the platform can't report a
        // level. A bad reading must never be treated as "0%", which would instantly lock.
        assertFalse(shouldBeLocked(currentlyLocked = false, batteryPercent = -1))
    }

    @Test
    fun invalidReading_whileLocked_staysLockedRatherThanGuessing() {
        // Equally, a bad reading must never be treated as "recovered" and silently unlock.
        assertTrue(shouldBeLocked(currentlyLocked = true, batteryPercent = -1))
    }
}
