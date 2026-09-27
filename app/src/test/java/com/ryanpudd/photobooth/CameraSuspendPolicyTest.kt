package com.ryanpudd.photobooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSuspendPolicyTest {

    private fun shouldSuspend(
        currentState: BoothState = BoothState.IDLE,
        cameraSuspended: Boolean = false,
        demoMode: Boolean = false,
        adminDialogShowing: Boolean = false,
        msSinceLastTouch: Long = 0L
    ): Boolean = CameraSuspendPolicy.shouldSuspend(
        currentState, cameraSuspended, demoMode, adminDialogShowing, msSinceLastTouch
    )

    @Test
    fun constantMatchesTheAgreedValue() {
        assertEquals(180_000L, CameraSuspendPolicy.SUSPEND_AFTER_MS)
    }

    @Test
    fun idleBeyondTimeout_suspends() {
        assertTrue(shouldSuspend(msSinceLastTouch = 181_000L))
    }

    @Test
    fun exactlyAtTheTimeout_suspends() {
        assertTrue(shouldSuspend(msSinceLastTouch = 180_000L))
    }

    @Test
    fun idleWithinTimeout_doesNotSuspend() {
        assertFalse(shouldSuspend(msSinceLastTouch = 179_000L))
    }

    @Test
    fun capturingNeverSuspends_howeverLongSinceTheLastTouch() {
        assertFalse(shouldSuspend(currentState = BoothState.CAPTURING, msSinceLastTouch = 600_000L))
    }

    @Test
    fun reviewNeverSuspends_howeverLongSinceTheLastTouch() {
        assertFalse(shouldSuspend(currentState = BoothState.REVIEW, msSinceLastTouch = 600_000L))
    }

    @Test
    fun demoModeNeverSuspends_soTheDrainTestStaysAWorstCase() {
        assertFalse(shouldSuspend(demoMode = true, msSinceLastTouch = 600_000L))
    }

    @Test
    fun adminDialogOpenNeverSuspends() {
        assertFalse(shouldSuspend(adminDialogShowing = true, msSinceLastTouch = 600_000L))
    }

    @Test
    fun alreadySuspended_doesNotSuspendAgain() {
        assertFalse(shouldSuspend(cameraSuspended = true, msSinceLastTouch = 600_000L))
    }
}
