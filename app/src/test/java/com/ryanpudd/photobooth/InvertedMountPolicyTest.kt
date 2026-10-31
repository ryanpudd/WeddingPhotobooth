package com.ryanpudd.photobooth

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class InvertedMountPolicyTest {

    @Test
    fun normalMount_isLandscape() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            InvertedMountPolicy.screenOrientation(inverted = false)
        )
    }

    @Test
    fun invertedMount_isReverseLandscape() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            InvertedMountPolicy.screenOrientation(inverted = true)
        )
    }

    @Test
    fun normalMount_mirrorsHorizontallyOnly() {
        assertEquals(
            InvertedMountPolicy.MirrorTransform(scaleX = -1f, scaleY = 1f),
            InvertedMountPolicy.mirrorTransform(inverted = false)
        )
    }

    @Test
    fun invertedMount_flipsVerticallyOnly() {
        assertEquals(
            InvertedMountPolicy.MirrorTransform(scaleX = 1f, scaleY = -1f),
            InvertedMountPolicy.mirrorTransform(inverted = true)
        )
    }
}
