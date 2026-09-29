package com.ryanpudd.photobooth

/**
 * Every piece of user-facing alert copy, in one place and free of Android
 * imports so the wording is covered by tests.
 */
object AlertMessages {

    /** Shown full-screen the instant the camera is lost. Anyone can act on it. */
    const val FAULT_SCREEN_TEXT = "Booth needs a battery swap, find someone to help"

    private const val TEST_PREFIX = "[TEST] "

    fun disconnect(isDemoMode: Boolean): String {
        val body = "🔴 @everyone **Photobooth camera lost** — most likely the battery pack. " +
            "Swap in the spare and it should come back on its own."
        return if (isDemoMode) TEST_PREFIX + body else body
    }

    fun allClear(isDemoMode: Boolean): String {
        val body = "🟢 **Photobooth is back up** — camera reconnected, nothing more to do."
        return if (isDemoMode) TEST_PREFIX + body else body
    }

    fun powerLost(isDemoMode: Boolean): String {
        val body = "🔋 @everyone **Photobooth lost external power** — it's running on the tablet's " +
            "own battery now. Check the pack and splitter connection."
        return if (isDemoMode) TEST_PREFIX + body else body
    }

    fun powerRestored(isDemoMode: Boolean): String {
        val body = "🟢 **Photobooth external power is back** — nothing more to do."
        return if (isDemoMode) TEST_PREFIX + body else body
    }

    /** Shown full-screen the instant the tablet's own battery hits 20%. */
    const val LOW_BATTERY_SCREEN_TEXT = "😴 Whoa there, superstars — you've partied my " +
        "battery right into the ground. I'm taking a quick nap to recharge myself — no need to " +
        "do anything, I'll be back for more smiles soon!"

    fun lowBatteryLock(isDemoMode: Boolean): String {
        // No call to action here: the tablet stays on the same pack the whole night for the
        // OTG splitter to work at all, so it recharges itself once the load drops — nobody
        // needs to go plug anything in. A pack that actually dies is a different situation,
        // already covered by disconnect()'s alert when the camera itself drops.
        val body = "😴 @everyone **The photobooth is taking a nap** — tablet battery's down to " +
            "20%. Nothing to do here: it recharges itself off the same pack and will wake back " +
            "up once it's above 50%."
        return if (isDemoMode) TEST_PREFIX + body else body
    }

    fun lowBatteryUnlock(isDemoMode: Boolean): String {
        val body = "🔋 **Photobooth is back and well-rested** — battery's above 50% " +
            "again, snapping away as normal."
        return if (isDemoMode) TEST_PREFIX + body else body
    }
}
