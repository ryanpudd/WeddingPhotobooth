package com.ryanpudd.photobooth

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.usb.UsbDevice
import android.media.ExifInterface
import android.os.BatteryManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.ViewAnimationUtils
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

enum class BoothState {
    IDLE, COUNTDOWN_PRECAPTURE, CAPTURING, REVIEW
}

class MainActivity : AppCompatActivity(), CameraDialog.CameraDialogParent  {

    private val PREVIEW_MODE: Int = UVCCamera.FRAME_FORMAT_MJPEG

    // Config constants — set once during physical setup, not exposed to guests
    private val PHOTO_ROTATION_DEGREES = 0   // verify CW/CCW against physical webcam mount during setup
    private val PHOTO_JPEG_QUALITY = 92
    private val LOW_STORAGE_THRESHOLD_BYTES = 50L * 1024 * 1024
    private val ADMIN_PIN = "1234"

    // Camera calibration — reduces backlight overexposure and motion blur.
    // Applied automatically after each camera connect; not exposed to guests.
    private val ENABLE_BACKLIGHT_COMPENSATION = true
    private val BACKLIGHT_COMPENSATION_LEVEL = 100      // % of device's backlight-comp range
    private val ENABLE_CONTINUOUS_AUTOFOCUS = true
    private val PREFER_CONSTANT_FRAME_RATE_EXPOSURE = true  // caps exposure time to cut motion blur; trade-off: more noise in low light
    private val CAMERA_CALIBRATION_DELAY_MS = 300L      // UVCCamera control ranges aren't reliable immediately after open

    private lateinit var stillImageView: ImageView
    private lateinit var flashOverlay: View
    private lateinit var statusOverlayText: TextView
    private lateinit var btnKeep: Button
    private lateinit var btnRetake: Button
    private lateinit var btnAdminGear: ImageButton

    private lateinit var uploadWorker: UploadWorker
    private val gearHideRunnable = Runnable { btnAdminGear.visibility = View.GONE }

    private lateinit var cameraView: UVCCameraTextureView

    private var currentState = BoothState.IDLE
    private var reviewTimer: CountDownTimer? = null
    private var preCaptureTimer: CountDownTimer? = null

    private val discordNotifier = DiscordNotifier { AlertSettingsStore.loadWebhookUrl(this) }

    private val recentCameraErrors = ConcurrentLinkedQueue<String>()
    private val appStartedElapsedMs = SystemClock.elapsedRealtime()

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
        mainHandler.removeCallbacks(gearHideRunnable)
        uploadWorker.stop()
        discordNotifier.shutdown()
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

        // Initialize and start the background S3 upload worker
        uploadWorker = UploadWorker(applicationContext)
        uploadWorker.start()

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
        btnAdminGear = findViewById(R.id.btnAdminGear)

        btnKeep.setOnClickListener { handleKeep() }
        btnRetake.setOnClickListener { handleRetake() }
        statusOverlayText.setOnClickListener {
            if (currentState == BoothState.IDLE) {
                startPreCaptureCountdown()
            }
        }

        val rootLayout = findViewById<View>(R.id.rootLayout)
        // Deliberately NOT gated on BoothState.IDLE: a camera failure can park the
        // app in CAPTURING or REVIEW, and that is exactly when diagnostics are needed.
        rootLayout.setOnLongClickListener {
            btnAdminGear.visibility = View.VISIBLE
            mainHandler.removeCallbacks(gearHideRunnable)
            mainHandler.postDelayed(gearHideRunnable, 8000)
            true
        }
        btnAdminGear.setOnClickListener { showAdminPinDialog() }
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
        val bitmap = mPreviewImage ?: return
        try {
            val pendingDir = UploadQueueManager.pendingDir(this)
            if (!pendingDir.exists()) pendingDir.mkdirs()

            val statFs = StatFs(pendingDir.path)
            if (statFs.availableBytes < LOW_STORAGE_THRESHOLD_BYTES) {
                Toast.makeText(this, R.string.storage_low_warning, Toast.LENGTH_LONG).show()
            }

            val finalFile = UploadQueueManager.nextAvailableFile(pendingDir)
            val tempFile = File(pendingDir, "${finalFile.name}.tmp")

            BufferedOutputStream(FileOutputStream(tempFile)).use { os ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, PHOTO_JPEG_QUALITY, os)
                os.flush()
            }
            applyExifOrientation(tempFile, PHOTO_ROTATION_DEGREES)

            if (!tempFile.renameTo(finalFile)) {
                throw IOException("rename failed: ${tempFile.path} -> ${finalFile.path}")
            }

            uploadWorker.kickNow()
        } catch (e: Exception) {
            Log.e(TAG, "storePhoto failed", e)
            Toast.makeText(this, R.string.save_failed_warning, Toast.LENGTH_LONG).show()
        }
    }

    private fun applyExifOrientation(file: File, rotationDegrees: Int) {
        val exifValue = when (rotationDegrees) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
        val exif = ExifInterface(file.path)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifValue.toString())
        exif.saveAttributes()
    }

    private fun showAdminPinDialog() {
        // The gear is reachable from any BoothState (including mid-countdown), but the
        // dialog doesn't pause the Looper — an in-flight preCaptureTimer/reviewTimer would
        // otherwise fire behind the modal. Drop back to idle before showing the PIN prompt.
        resetToIdle()

        val view = layoutInflater.inflate(R.layout.dialog_admin_pin, null)
        val pinInput = view.findViewById<EditText>(R.id.pinInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.admin_pin_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.submit, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (pinInput.text.toString() == ADMIN_PIN) {
                    dialog.dismiss()
                    showAdminSettingsDialog()
                } else {
                    pinInput.error = getString(R.string.admin_pin_incorrect)
                }
            }
        }
        dialog.show()
    }

    private fun showAdminSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_admin_settings, null)
        val inputAccessKeyId = view.findViewById<EditText>(R.id.inputAccessKeyId)
        val inputSecretKey = view.findViewById<EditText>(R.id.inputSecretKey)
        val inputBucket = view.findViewById<EditText>(R.id.inputBucket)
        val inputRegion = view.findViewById<EditText>(R.id.inputRegion)
        val inputKeyPrefix = view.findViewById<EditText>(R.id.inputKeyPrefix)
        val inputWebhookUrl = view.findViewById<EditText>(R.id.inputWebhookUrl)
        val btnSendTestAlert = view.findViewById<Button>(R.id.btnSendTestAlert)

        val existing = CredentialsStore.loadNonSecretFields(this)
        inputAccessKeyId.setText(existing.accessKeyId)
        inputBucket.setText(existing.bucket)
        inputRegion.setText(existing.region)
        inputKeyPrefix.setText(existing.keyPrefix)
        if (CredentialsStore.hasStoredSecret(this)) {
            inputSecretKey.hint = getString(R.string.hint_secret_unchanged)
        }

        if (AlertSettingsStore.hasWebhookUrl(this)) {
            inputWebhookUrl.hint = getString(R.string.hint_webhook_unchanged)
        }

        btnSendTestAlert.setOnClickListener {
            if (AlertSettingsStore.hasWebhookUrl(this)) {
                discordNotifier.send(
                    "✅ **Photobooth test alert** — if you can see this, alerts are working.",
                    mentionEveryone = true
                )
                Toast.makeText(this, R.string.toast_test_alert_sent, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.toast_no_webhook, Toast.LENGTH_SHORT).show()
            }
        }

        val btnSendDiagnostics = view.findViewById<Button>(R.id.btnSendDiagnostics)
        btnSendDiagnostics.setOnClickListener {
            val report = BoothDiagnostics.format(collectDiagnostics())
            discordNotifier.send(report, mentionEveryone = false)
            AlertDialog.Builder(this)
                .setTitle(R.string.btn_send_diagnostics)
                .setMessage(report)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.admin_settings_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val accessKeyId = inputAccessKeyId.text.toString().trim()
                val bucket = inputBucket.text.toString().trim()
                val region = inputRegion.text.toString().trim()
                val keyPrefix = inputKeyPrefix.text.toString().trim()
                val secretInput = inputSecretKey.text.toString()
                val secretKey = if (secretInput.isBlank()) {
                    CredentialsStore.load(this)?.secretKey ?: ""
                } else {
                    secretInput
                }

                if (accessKeyId.isBlank() || secretKey.isBlank() || bucket.isBlank() || region.isBlank()) {
                    Toast.makeText(this, R.string.admin_settings_incomplete, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val webhookInput = inputWebhookUrl.text.toString().trim()
                if (webhookInput.isNotBlank()) {
                    if (!DiscordPayload.isValidWebhookUrl(webhookInput)) {
                        Toast.makeText(this, R.string.admin_webhook_invalid, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    AlertSettingsStore.saveWebhookUrl(this, webhookInput)
                }

                CredentialsStore.save(
                    this,
                    CredentialsStore.S3Config(accessKeyId, secretKey, bucket, region, keyPrefix)
                )
                uploadWorker.kickNow()
            }
            .show()
    }

    private fun collectDiagnostics(): DiagnosticsSnapshot {
        val device: UsbDevice? = runCatching { mUSBMonitor?.deviceList?.firstOrNull() }.getOrNull()
        val batteryIntent = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        return DiagnosticsSnapshot(
            boothState = currentState.name,
            cameraOpened = runCatching { mCameraHandler?.isOpened == true }.getOrDefault(false),
            cameraPreviewing = runCatching { mCameraHandler?.isPreviewing == true }.getOrDefault(false),
            usbDeviceAttached = device != null,
            usbPermissionGranted = device != null &&
                runCatching { mUSBMonitor?.hasPermission(device) == true }.getOrDefault(false),
            deviceName = device?.deviceName,
            vendorId = device?.vendorId,
            productId = device?.productId,
            // TODO(Task 6): replace with cameraAlertState.phase.name once that field exists.
            alertPhase = "UNWIRED",
            batteryLevelPercent = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1,
            batteryPluggedRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1,
            appUptimeMs = SystemClock.elapsedRealtime() - appStartedElapsedMs,
            lastCameraErrors = recentCameraErrors.toList()
        )
    }

    private fun recordCameraError(message: String) {
        recentCameraErrors.add(message)
        while (recentCameraErrors.size > MAX_RECENT_CAMERA_ERRORS) {
            recentCameraErrors.poll()
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
        mainHandler.postDelayed({ applyCameraCalibration() }, CAMERA_CALIBRATION_DELAY_MS)

        runOnUiThread { resetToIdle() }
    }

    private fun applyCameraCalibration() {
        mCameraHandler?.post {
            if (ENABLE_BACKLIGHT_COMPENSATION) {
                try {
                    if (mCameraHandler?.checkSupportFlag(UVCCamera.PU_BACKLIGHT.toLong()) == true) {
                        mCameraHandler?.setValue(UVCCamera.PU_BACKLIGHT, BACKLIGHT_COMPENSATION_LEVEL)
                    } else {
                        Log.w(TAG, "backlight compensation not supported by this webcam")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "failed to apply backlight compensation", e)
                }
            }

            if (ENABLE_CONTINUOUS_AUTOFOCUS) {
                try {
                    mCameraHandler?.setAutoFocus(true)
                } catch (e: Exception) {
                    Log.w(TAG, "failed to enable autofocus", e)
                }
            }

            if (PREFER_CONSTANT_FRAME_RATE_EXPOSURE) {
                try {
                    if (mCameraHandler?.checkSupportFlag(UVCCamera.CTRL_AE_PRIORITY.toLong()) == true) {
                        mCameraHandler?.setExposurePriority(true)
                    } else {
                        Log.w(TAG, "exposure priority control not supported by this webcam")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "failed to apply exposure priority", e)
                }
            }
        }
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

    companion object {
        private const val MAX_RECENT_CAMERA_ERRORS = 5
    }
}
