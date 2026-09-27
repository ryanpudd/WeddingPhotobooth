package com.ryanpudd.photobooth

/**
 * Decides when to release the camera so the kernel's USB autosuspend can cut
 * its power, and never lets it if the booth is in a state where the camera
 * must stay live.
 *
 * Demo mode never suspends: the drain test is meant to be the worst case, and
 * exercising this path there would undermine that. An open admin dialog never
 * suspends either, so a helper mid-setup never gets the camera pulled out
 * from under them.
 */
object CameraSuspendPolicy {

    const val SUSPEND_AFTER_MS = 180_000L

    fun shouldSuspend(
        currentState: BoothState,
        cameraSuspended: Boolean,
        demoMode: Boolean,
        adminDialogShowing: Boolean,
        msSinceLastTouch: Long
    ): Boolean {
        if (cameraSuspended) return false
        if (demoMode) return false
        if (adminDialogShowing) return false
        if (currentState != BoothState.IDLE) return false
        return msSinceLastTouch >= SUSPEND_AFTER_MS
    }
}
