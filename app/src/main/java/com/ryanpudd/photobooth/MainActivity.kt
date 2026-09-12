package com.ryanpudd.photobooth

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.ViewAnimationUtils
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.serenegiant.encoder.MediaMuxerWrapper
import com.serenegiant.usb.CameraDialog
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener
import com.serenegiant.usb.USBMonitor.UsbControlBlock
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usbcameracommon.UVCCameraHandler
import com.serenegiant.utils.HandlerThreadHandler
import com.serenegiant.widget.CameraViewInterface
import com.serenegiant.widget.UVCCameraTextureView
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

enum class BoothState {
    IDLE, COUNTDOWN_PRECAPTURE, CAPTURING, REVIEW
}

class MainActivity : AppCompatActivity(), CameraDialog.CameraDialogParent  {

    private val PREVIEW_MODE: Int = UVCCamera.FRAME_FORMAT_MJPEG

    private lateinit var stillImageView: ImageView
    private lateinit var flashOverlay: View
    private lateinit var statusOverlayText: TextView
    private lateinit var btnKeep: Button
    private lateinit var btnRetake: Button

    private lateinit var cameraView: UVCCameraTextureView

    private var currentState = BoothState.IDLE
    private var reviewTimer: CountDownTimer? = null
    private var preCaptureTimer: CountDownTimer? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureRunnable = Runnable { showReviewScreen() }

    // Sync lock for the camera
    private val mSync = Any()

    // for accessing USB and USB camera
    private var mCameraHandler: UVCCameraHandler? = null
    private var mUSBMonitor: USBMonitor? = null
    private var mUVCCamera: UVCCamera? = null
    private var mUVCCameraView: CameraViewInterface? = null

    // TODO: should be in base class
    val TAG: String = MainActivity::class.java.getSimpleName()
    private var mWorkerHandler: Handler? = null
    private var mWorkerThreadID: Long = -1

    private var mPreviewWidth: Int = -1
    private var mPreviewHeight: Int = -1

    private var mPreviewImage: Bitmap? = null

    // Heartbeat runnable to update watchdog preference every 5 seconds
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            WatchdogScheduler.updateHeartbeat(this@MainActivity)
            mainHandler.postDelayed(this, 5000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        preCaptureTimer?.cancel()
        reviewTimer?.cancel()
        mainHandler.removeCallbacks(captureRunnable)
        mainHandler.removeCallbacks(heartbeatRunnable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // TODO: Should be in base class
        // ワーカースレッドを生成
        if (mWorkerHandler == null) {
            mWorkerHandler = HandlerThreadHandler.createHandler(TAG)
            mWorkerThreadID = mWorkerHandler!!.getLooper().getThread().getId()
        }

        // Initialize and start watchdog heartbeat
        WatchdogScheduler.schedule(this)
        mainHandler.post(heartbeatRunnable)

        // Get the dimensions
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        mPreviewWidth = displayMetrics.widthPixels
        mPreviewHeight = displayMetrics.heightPixels

        // Initialize the camera
        cameraView = findViewById(R.id.camera_view)
        cameraView.scaleX = -1f // Mirror the preview

        mUVCCameraView = cameraView as CameraViewInterface
        mUVCCameraView?.aspectRatio = mPreviewWidth / mPreviewHeight.toDouble()

        mUSBMonitor = USBMonitor(this, mOnDeviceConnectListener)

        mCameraHandler = UVCCameraHandler.createHandler(this, mUVCCameraView,
            2, mPreviewWidth, mPreviewHeight, PREVIEW_MODE);
        mCameraHandler?.setStoreOnCapture(false);

        // Get UI elements
        stillImageView = findViewById(R.id.stillImageView)
        flashOverlay = findViewById(R.id.flashOverlay)
        statusOverlayText = findViewById(R.id.statusOverlayText)
        btnKeep = findViewById(R.id.btnKeep)
        btnRetake = findViewById(R.id.btnRetake)

        btnKeep.setOnClickListener { handleKeep() }
        btnRetake.setOnClickListener { handleRetake() }
        statusOverlayText.setOnClickListener {
            if (currentState == BoothState.IDLE) {
                startPreCaptureCountdown()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mUSBMonitor?.register()
        synchronized(mSync) {
            if (mUVCCamera != null) {
                mUVCCamera!!.startPreview()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
        }
    }

    private fun applyImmersiveMode() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    private fun resetToIdle() {
        currentState = BoothState.IDLE
        statusOverlayText.text = getString(R.string.tap_to_take_photo)
        resetToCapture()
    }

    private fun resetToCapture() {
        reviewTimer?.cancel()
        preCaptureTimer?.cancel()
        mainHandler.removeCallbacks(captureRunnable)
        statusOverlayText.visibility = View.VISIBLE
        btnKeep.visibility = View.GONE
        btnRetake.visibility = View.GONE

        stillImageView.visibility = View.INVISIBLE
        stillImageView.setImageDrawable(null)
        // Fix any transformations on stillImage
        stillImageView.translationX = 0f
        stillImageView.translationY = 0f
        stillImageView.translationZ = 0f
        stillImageView.rotation = 0f
        stillImageView.scaleX = 1f
        stillImageView.scaleY = 1f
        stillImageView.alpha = 1f

        cameraView.visibility = View.VISIBLE

        mPreviewImage?.let {
            if (!it.isRecycled) it.recycle()
        }
        mPreviewImage = null
    }

    private fun startPreCaptureCountdown() {
        resetToCapture()

        preCaptureTimer = object : CountDownTimer(3000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000) + 1
                statusOverlayText.text = getString(R.string.countdown_seconds, seconds)
            }

            override fun onFinish() {
                currentState = BoothState.CAPTURING
                statusOverlayText.text = getString(R.string.smile)

                // Take the photo
                takePhoto()
                //mCameraHandler?.captureStill()

                // Trigger capture pipeline here
                //mainHandler.postDelayed(captureRunnable, 100)
            }
        }.start()
    }

    private fun showReviewScreen() {
        currentState = BoothState.REVIEW

        statusOverlayText.visibility = View.VISIBLE
        btnKeep.visibility = View.VISIBLE
        btnRetake.visibility = View.VISIBLE

        startReviewAutoAdvanceCountdown()
    }

    private fun startReviewAutoAdvanceCountdown() {
        reviewTimer?.cancel()
        reviewTimer = object : CountDownTimer(8000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000) + 1
                statusOverlayText.text = getString(R.string.saving_in_seconds, seconds)
            }

            private val sDateTimeFormat: SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)

            override fun onFinish() {
                handleKeep()
            }
        }.start()
    }

    private fun takePhoto() {
        playFlash()
        mCameraHandler!!.post {
            try {
                val source = mUVCCameraView!!.captureStillImage()
                val matrix = Matrix().apply { preScale(-1f, 1f) }
                val copy = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false)
                mainHandler.post {
                    mPreviewImage = copy
                    irisCloseThenReveal()
                }
            } catch (e: Exception) {
                Log.e(TAG, "takePhoto", e)
            }
        }
    }

    private fun playFlash() {
        flashOverlay.animate()
            .alpha(0.9f)
            .setDuration(60)
            .withEndAction {
                flashOverlay.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .start()
            }
            .start()
    }

    private fun irisCloseThenReveal() {
        val cx = mPreviewWidth / 2
        val cy = mPreviewHeight / 2
        val startRadius = kotlin.math.hypot(cx.toDouble(), cy.toDouble()).toFloat()

        // Closing circle shrinks the visible camera view to nothing
        val closeAnim = ViewAnimationUtils.createCircularReveal(cameraView, cx, cy, startRadius, 0f)
        closeAnim.duration = 200
        closeAnim.interpolator = AccelerateInterpolator()

        closeAnim.addListener(object: Animator.AnimatorListener {
            override fun onAnimationStart(p0: Animator) {}
            override fun onAnimationRepeat(p0: Animator) {}
            override fun onAnimationCancel(p0: Animator) {}

            override fun onAnimationEnd(p0: Animator) {
                cameraView.visibility = View.INVISIBLE
                stillImageView.setImageBitmap(mPreviewImage)
                stillImageView.visibility = View.VISIBLE

                // opening circle reviews still
                val openAnim =
                    ViewAnimationUtils.createCircularReveal(stillImageView, cx, cy, 0f, startRadius)
                openAnim.duration = 250
                openAnim.interpolator = DecelerateInterpolator()
                openAnim.addListener(object : Animator.AnimatorListener {
                    override fun onAnimationStart(animation: Animator) {}
                    override fun onAnimationEnd(animation: Animator) {
                        showReviewScreen()
                    }
                    override fun onAnimationCancel(animation: Animator) {}
                    override fun onAnimationRepeat(animation: Animator) {}
                })
                openAnim.start()
            }
        })
        closeAnim.start()
    }

    private fun handleKeep() {
        animateSaveAndExit {
            storePhoto()
            resetToIdle()
        }
    }

    private fun handleRetake() {
        animateRetakeToTrash {
            startPreCaptureCountdown()
        }
    }

    private fun storePhoto() {
        try {
            val outputFile = MediaMuxerWrapper.getCaptureFile(Environment.DIRECTORY_DCIM, ".png")
            val os = BufferedOutputStream(FileOutputStream(outputFile))

            try {
                try {
                    mPreviewImage!!.compress(Bitmap.CompressFormat.PNG, 100, os)
                    os.flush()
                } catch (e: IOException) {
                }
            } finally {
                os.close()
            }

        } catch (e: Exception) {
            // TODO - Report error
        }
    }

    private fun animateSaveAndExit(onComplete: () -> Unit) {
        val screenWidth = (stillImageView.parent as View).width

        val translateX = ObjectAnimator.ofFloat(
            stillImageView, View.TRANSLATION_X, 0f, screenWidth.toFloat() * 1.2f
        )
        val translateY = ObjectAnimator.ofFloat(
            stillImageView, View.TRANSLATION_Y, 0f, -80f
        )
        val rotate = ObjectAnimator.ofFloat(
            stillImageView, View.ROTATION, 0f, 15f
        )
        val scale = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(stillImageView, View.SCALE_X, 1f, 0.6f),
                ObjectAnimator.ofFloat(stillImageView, View.SCALE_Y, 1f, 0.6f)
            )
        }

        val flyOut = AnimatorSet().apply {
            playTogether(translateX, translateY, rotate, scale)
            duration = 450
            interpolator = AccelerateInterpolator()
        }

        flyOut.addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {}
            override fun onAnimationEnd(animation: Animator) {
                onComplete()
            }
            override fun onAnimationCancel(animation: Animator) {}
            override fun onAnimationRepeat(animation: Animator) {}
        })

        flyOut.start()
    }

    private fun animateRetakeToTrash(onComplete: () -> Unit) {
        val parentHeight = (stillImageView.parent as View).height
        val parentWidth = (stillImageView.parent as View).width

        // Hop 1: tumble toward bottom-left
        val hop1 = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(stillImageView, View.TRANSLATION_X, 0f, -parentWidth * 0.25f),
                ObjectAnimator.ofFloat(stillImageView, View.TRANSLATION_Y, 0f, parentHeight * 0.3f),
                ObjectAnimator.ofFloat(stillImageView, View.ROTATION, 0f, -25f)
            )
            duration = 220
            interpolator = AccelerateInterpolator()
        }

        // Hop 2: tumble toward bottom-right, lower than hop1
        val hop2 = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(stillImageView, View.TRANSLATION_X, -parentWidth * 0.25f, parentWidth * 0.2f),
                ObjectAnimator.ofFloat(stillImageView, View.TRANSLATION_Y, parentHeight * 0.3f, parentHeight * 0.65f),
                ObjectAnimator.ofFloat(stillImageView, View.ROTATION, -25f, 20f)
            )
            duration = 220
            interpolator = AccelerateInterpolator()
        }

        // Final drop: straight down and off, shrinking + fading like it's landing in the bin
        val finalDrop = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(stillImageView, View.TRANSLATION_Y, parentHeight * 0.65f, parentHeight.toFloat() * 1.1f),
                ObjectAnimator.ofFloat(stillImageView, View.ROTATION, 20f, 35f),
                ObjectAnimator.ofFloat(stillImageView, View.SCALE_X, 1f, 0.4f),
                ObjectAnimator.ofFloat(stillImageView, View.SCALE_Y, 1f, 0.4f),
                ObjectAnimator.ofFloat(stillImageView, View.ALPHA, 1f, 0f)
            )
            duration = 280
            interpolator = AccelerateInterpolator()
        }

        val fullSequence = AnimatorSet().apply {
            playSequentially(hop1, hop2, finalDrop)
        }

        fullSequence.addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {}
            override fun onAnimationEnd(animation: Animator) {
                onComplete()
            }
            override fun onAnimationCancel(animation: Animator) {}
            override fun onAnimationRepeat(animation: Animator) {}
        })

        fullSequence.start()
    }


    private var mSurface: Surface? = null

    private fun startPreview() {
        val st = mUVCCameraView!!.surfaceTexture
        if (mSurface != null) {
            mSurface?.release()
        }
        mSurface = Surface(st)
        mCameraHandler!!.startPreview(mSurface)

        runOnUiThread { resetToIdle() }
    }

    private val mOnDeviceConnectListener: OnDeviceConnectListener =
        object : OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice?) {
                Toast.makeText(this@MainActivity, "Camera Connected", Toast.LENGTH_SHORT).show()
                mUSBMonitor!!.requestPermission(device)
            }

            override fun onConnect(
                device: UsbDevice?,
                ctrlBlock: UsbControlBlock?,
                createNew: Boolean
            ) {
                //if (MainActivity.DEBUG) Log.v(MainActivity.TAG, "onConnect:")
                mCameraHandler!!.open(ctrlBlock)
                startPreview()
            }

            override fun onDisconnect(device: UsbDevice?, ctrlBlock: UsbControlBlock?) {
                //if (MainActivity.DEBUG) Log.v(MainActivity.TAG, "onDisconnect:")
                if (mCameraHandler != null) {
                    mCameraHandler!!.close()
                    // TODO - Stop showing the button if there is no camera
                    //setCameraButton(false)
                }
            }

            override fun onDettach(device: UsbDevice?) {
                Toast.makeText(this@MainActivity, "Camera Disconnected", Toast.LENGTH_SHORT).show()
            }

            override fun onCancel(device: UsbDevice?) {
            }
        }

    /**
     * to access from CameraDialog
     * @return
     */
    override fun getUSBMonitor(): USBMonitor? {
        return mUSBMonitor
    }

    override fun onDialogResult(canceled: Boolean) {
        if (canceled) {
            runOnUiThread(object : Runnable {
                override fun run() {
                    // FIXME
                }
            })
        }
    }

    // Should be in base class
    @Synchronized
    protected fun queueEvent(task: Runnable?, delayMillis: Long) {
        if ((task == null) || (mWorkerHandler == null)) return
        try {
            mWorkerHandler?.removeCallbacks(task)
            if (delayMillis > 0) {
                mWorkerHandler?.postDelayed(task, delayMillis)
            } else if (mWorkerThreadID == Thread.currentThread().getId()) {
                task.run()
            } else {
                mWorkerHandler?.post(task)
            }
        } catch (e: java.lang.Exception) {
            // ignore
        }
    }
}
