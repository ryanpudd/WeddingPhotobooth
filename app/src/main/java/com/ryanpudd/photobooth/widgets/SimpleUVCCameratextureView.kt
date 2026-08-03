/*
 *  UVCCamera
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */
package com.ryanpudd.photobooth.widget

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView
import kotlin.math.abs

/**
 * change the view size with keeping the specified aspect ratio.
 * if you set this view with in a FrameLayout and set property "android:layout_gravity="center",
 * you can show this view in the center of screen and keep the aspect ratio of content
 * XXX it is better that can set the aspect raton a a xml property
 */
class SimpleUVCCameraTextureView // API >= 14
@JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) :
    TextureView(context, attrs, defStyle), AspectRatioViewInterface {
    private var mRequestedAspect = -1.0

    public override fun onResume() {
    }

    public override fun onPause() {
    }

    public override fun setAspectRatio(aspectRatio: Double) {
        require(!(aspectRatio < 0))
        if (mRequestedAspect != aspectRatio) {
            mRequestedAspect = aspectRatio
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var widthMeasureSpec = widthMeasureSpec
        var heightMeasureSpec = heightMeasureSpec
        if (mRequestedAspect > 0) {
            var initialWidth = MeasureSpec.getSize(widthMeasureSpec)
            var initialHeight = MeasureSpec.getSize(heightMeasureSpec)

            val horizPadding = getPaddingLeft() + getPaddingRight()
            val vertPadding = getPaddingTop() + getPaddingBottom()
            initialWidth -= horizPadding
            initialHeight -= vertPadding

            val viewAspectRatio = initialWidth.toDouble() / initialHeight
            val aspectDiff = mRequestedAspect / viewAspectRatio - 1

            if (abs(aspectDiff) > 0.01) {
                if (aspectDiff > 0) {
                    // width priority decision
                    initialHeight = (initialWidth / mRequestedAspect).toInt()
                } else {
                    // height priority decison
                    initialWidth = (initialHeight * mRequestedAspect).toInt()
                }
                initialWidth += horizPadding
                initialHeight += vertPadding
                widthMeasureSpec = MeasureSpec.makeMeasureSpec(initialWidth, MeasureSpec.EXACTLY)
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(initialHeight, MeasureSpec.EXACTLY)
            }
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}