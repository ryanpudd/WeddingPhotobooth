package com.ryanpudd.photobooth

/**
 * Decides whether the booth should currently be locked behind the low-battery
 * screen. A wide hysteresis band (lock at 20%, unlock at 50%) is the anti-flap
 * guard here — there is no separate debounce timer the way BrightnessPolicy or
 * CameraSuspendPolicy have one, because a 30-point gap already makes boundary
 * noise a non-issue: once locked, a momentary blip back above 20% changes
 * nothing, and it takes a real recharge to 50% to unlock again.
 *
 * Locking never happens outside BoothState.IDLE, so a guest mid-countdown,
 * mid-capture, or reading their review screen is never interrupted. Unlocking
 * has no such gate: removing a full-screen overlay never disrupts anything.
 */
object LowBatteryPolicy {

    const val LOCK_AT_PERCENT = 20
    const val UNLOCK_AT_PERCENT = 50

    fun shouldBeLocked(
        currentlyLocked: Boolean,
        currentState: BoothState,
        batteryPercent: Int
    ): Boolean {
        // BatteryManager.getIntProperty can return -1 when the platform can't report a
        // level. Treat that as "no new information" — stay exactly as locked/unlocked as
        // before, rather than guessing "0%" (instant lock) or "recovered" (silent unlock).
        if (batteryPercent < 0) return currentlyLocked
        if (currentlyLocked) {
            return batteryPercent < UNLOCK_AT_PERCENT
        }
        if (currentState != BoothState.IDLE) return false
        return batteryPercent <= LOCK_AT_PERCENT
    }
}
