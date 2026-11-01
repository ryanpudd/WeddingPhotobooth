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

    /**
     * Pins why the inverted transform is a vertical flip: the webcam turned 180° scales the
     * raw frame by (-1, -1), and the inverted transform applied on top of that must give back
     * exactly the normal selfie view.
     */
    @Test
    fun invertedTransform_undoesTheCameraTurnAndKeepsTheSelfieMirror() {
        val cameraTurn = InvertedMountPolicy.MirrorTransform(scaleX = -1f, scaleY = -1f)
        val inverted = InvertedMountPolicy.mirrorTransform(inverted = true)
        val net = InvertedMountPolicy.MirrorTransform(
            scaleX = cameraTurn.scaleX * inverted.scaleX,
            scaleY = cameraTurn.scaleY * inverted.scaleY
        )
        assertEquals(InvertedMountPolicy.mirrorTransform(inverted = false), net)
    }
}
