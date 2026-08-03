package com.ryanpudd.photobooth

import android.graphics.Bitmap
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
import android.widget.Button
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

    private lateinit var statusOverlayText: TextView
    private lateinit var btnKeep: Button
    private lateinit var btnRetake: Button
    //private lateinit var btnStart: Button

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
        mUVCCameraView = findViewById(R.id.camera_view) as CameraViewInterface
        mUVCCameraView?.aspectRatio = mPreviewWidth / mPreviewHeight.toDouble()

        mUSBMonitor = USBMonitor(this, mOnDeviceConnectListener)

        mCameraHandler = UVCCameraHandler.createHandler(this, mUVCCameraView,
            2, mPreviewWidth, mPreviewHeight, PREVIEW_MODE);
        mCameraHandler?.setStoreOnCapture(false);

        statusOverlayText = findViewById(R.id.statusOverlayText)
        btnKeep = findViewById(R.id.btnKeep)
        btnRetake = findViewById(R.id.btnRetake)

        btnKeep.setOnClickListener { storePhoto() }
        btnRetake.setOnClickListener { startPreCaptureCountdown() }
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
        reviewTimer?.cancel()
        preCaptureTimer?.cancel()
        mainHandler.removeCallbacks(captureRunnable)
        statusOverlayText.text = getString(R.string.tap_to_take_photo)
        statusOverlayText.visibility = View.VISIBLE
        btnKeep.visibility = View.GONE
        btnRetake.visibility = View.GONE

        startPreview()
    }

    private fun startPreCaptureCountdown() {
        reviewTimer?.cancel()
        preCaptureTimer?.cancel()
        mainHandler.removeCallbacks(captureRunnable)
        currentState = BoothState.COUNTDOWN_PRECAPTURE
        btnKeep.visibility = View.GONE
        btnRetake.visibility = View.GONE
        statusOverlayText.visibility = View.VISIBLE

        preCaptureTimer = object : CountDownTimer(3000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000) + 1
                statusOverlayText.text = getString(R.string.countdown_seconds, seconds)
            }

            override fun onFinish() {
                currentState = BoothState.CAPTURING
                statusOverlayText.text = getString(R.string.smile)

                // Take the photo
                mCameraHandler?.captureStill()
                //takePhoto()

                // Trigger capture pipeline here
                mainHandler.postDelayed(captureRunnable, 1000)
            }
        }.start()
    }

    private fun showReviewScreen() {
        currentState = BoothState.REVIEW
        statusOverlayText.visibility = View.VISIBLE
        btnKeep.visibility = View.VISIBLE
        btnRetake.visibility = View.VISIBLE

        // Show the preview
        mCameraHandler?.stopPreview()
        val canvas = mSurface?.lockCanvas(null)

        mPreviewImage = mCameraHandler?.getLastStillCapture()
        if (mPreviewImage != null) {
            canvas?.drawBitmap(mPreviewImage!!, 0f, 0f, null);
            mSurface?.unlockCanvasAndPost(canvas)
        }

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
                storePhoto()
            }
        }.start()
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

        resetToIdle()
    }


    private var mSurface: Surface? = null

    private fun startPreview() {
        val st = mUVCCameraView!!.surfaceTexture
        if (mSurface != null) {
            mSurface?.release()
        }
        mSurface = Surface(st)
        mCameraHandler!!.startPreview(mSurface)
//        runOnUiThread(object : Runnable {
//            override fun run() {
//                // TODO - If we disable, we need to enable here
//                //mCaptureButton.setVisibility(View.VISIBLE)
//            }
//        })
    }

    private val mOnDeviceConnectListener: OnDeviceConnectListener =
        object : OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice?) {
                Toast.makeText(this@MainActivity, "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show()
                mUSBMonitor!!.requestPermission(device)
            }

            override fun onConnect(
                device: UsbDevice?,
                ctrlBlock: UsbControlBlock?,
                createNew: Boolean
            ) {
                //if (MainActivity.DEBUG) Log.v(MainActivity.TAG, "onConnect:")
                mCameraHandler!!.open(ctrlBlock)
                runOnUiThread(object : Runnable {
                    override fun run() {
                        resetToIdle()
                    }
                })
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
                Toast.makeText(this@MainActivity, "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show()
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
