package com.ryanpudd.photobooth

/**
 * Decides screen brightness.
 *
 * Pinned to full in normal use so the battery drain test transfers literally
 * rather than approximately, and so the booth looks the same all evening
 * regardless of what auto-brightness thinks of a dim venue.
 *
 * Demo mode never dims: the drain test is meant to be the worst case.
 */
object BrightnessPolicy {

    const val ACTIVE = 1.0f
    const val DIMMED = 0.3f
    const val IDLE_DIM_AFTER_MS = 30_000L

    fun brightnessFor(demoMode: Boolean, isIdle: Boolean, msSinceLastTouch: Long): Float {
        if (demoMode) return ACTIVE
        if (!isIdle) return ACTIVE
        return if (msSinceLastTouch >= IDLE_DIM_AFTER_MS) DIMMED else ACTIVE
    }
}
