package com.ryanpudd.photobooth

import org.junit.Assert.assertEquals
import org.junit.Test

class BrightnessPolicyTest {

    @Test
    fun constantsMatchTheAgreedValues() {
        assertEquals(1.0f, BrightnessPolicy.ACTIVE, 0.0001f)
        assertEquals(0.3f, BrightnessPolicy.DIMMED, 0.0001f)
        assertEquals(30_000L, BrightnessPolicy.IDLE_DIM_AFTER_MS)
    }

    @Test
    fun idleBeyondTimeout_dims() {
        assertEquals(
            BrightnessPolicy.DIMMED,
            BrightnessPolicy.brightnessFor(demoMode = false, isIdle = true, msSinceLastTouch = 31_000L),
            0.0001f
        )
    }

    @Test
    fun idleWithinTimeout_staysBright() {
        assertEquals(
            BrightnessPolicy.ACTIVE,
            BrightnessPolicy.brightnessFor(demoMode = false, isIdle = true, msSinceLastTouch = 29_000L),
            0.0001f
        )
    }

    @Test
    fun exactlyAtTheTimeout_dims() {
        assertEquals(
            BrightnessPolicy.DIMMED,
            BrightnessPolicy.brightnessFor(demoMode = false, isIdle = true, msSinceLastTouch = 30_000L),
            0.0001f
        )
    }

    @Test
    fun midCaptureNeverDims_howeverLongSinceTheLastTouch() {
        assertEquals(
            BrightnessPolicy.ACTIVE,
            BrightnessPolicy.brightnessFor(demoMode = false, isIdle = false, msSinceLastTouch = 600_000L),
            0.0001f
        )
    }

    @Test
    fun demoModeNeverDims_soTheDrainTestStaysAWorstCase() {
        assertEquals(
            BrightnessPolicy.ACTIVE,
            BrightnessPolicy.brightnessFor(demoMode = true, isIdle = true, msSinceLastTouch = 600_000L),
            0.0001f
        )
    }
}
