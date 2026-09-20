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
}
