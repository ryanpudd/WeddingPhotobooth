package com.ryanpudd.photobooth

import android.content.pm.ActivityInfo

/**
 * Decides how the booth presents itself on an inverted mount: the tablet and
 * webcam turned 180 degrees together as one unit.
 *
 * The screen flips to reverse landscape. The camera then sees the scene rotated
 * 180 degrees, which is a scale of (-1, -1); combined with the usual selfie mirror
 * of (-1, 1) that leaves a pure vertical flip. One transform drives both the live
 * preview and the saved photo so the two can never disagree.
 */
object InvertedMountPolicy {

    data class MirrorTransform(val scaleX: Float, val scaleY: Float)

    fun screenOrientation(inverted: Boolean): Int =
        if (inverted) ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

    fun mirrorTransform(inverted: Boolean): MirrorTransform =
        if (inverted) MirrorTransform(scaleX = 1f, scaleY = -1f)
        else MirrorTransform(scaleX = -1f, scaleY = 1f)
}
