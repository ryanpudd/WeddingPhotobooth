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
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewAnimationUtils
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.CheckBox
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
    // COUNTDOWN_PRECAPTURE is documentation-only: it is never assigned. currentState
    // deliberately stays IDLE through the pre-capture countdown, because resetToCapture()
    // does not clear currentState — assigning it here would risk leaving the booth wedged
    // in a state nothing returns it from. Kept as a named description of the phase.
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
    private lateinit var faultOverlay: TextView
    private lateinit var demoBanner: TextView

    // Ruling 8: tracks whichever admin dialog (PIN entry or settings, including
    // the diagnostics report shown on top of settings) is currently up, so the
    // demo auto-capture runnable can skip its countdown instead of firing behind
    // it. AlertDialog.isShowing reflects dismiss/cancel automatically, so this
    // only needs to be set on show - never cleared by hand.
    private var adminDialog: AlertDialog? = null

    private val cameraAlertState = CameraAlertState()

    private lateinit var uploadWorker: UploadWorker
    private val gearHideRunnable = Runnable { btnAdminGear.visibility = View.GONE }

    // C1 / spec R4: admin must be reachable from ANY state, above all from the wedged one.
    // faultOverlay is match_parent and the last child of the ConstraintLayout, so it receives
    // touches first and View.onTouchEvent consumes them — the root's long-click would never
    // run while the fault screen is up. This same listener is therefore attached to BOTH
    // rootLayout and faultOverlay. bringToFront() is required as well: btnAdminGear is declared
    // before faultOverlay in the layout, so without it the revealed gear draws underneath the
    // overlay and cannot be tapped. Only the LONG press is handled here; a short tap on the
    // overlay is still swallowed by its clickable="true", so no countdown starts on a dead camera.
    private val revealGear = View.OnLongClickListener {
        btnAdminGear.visibility = View.VISIBLE
        btnAdminGear.bringToFront()
        mainHandler.removeCallbacks(gearHideRunnable)
        mainHandler.postDelayed(gearHideRunnable, GEAR_VISIBLE_MS)
        true
    }

    // I1: a cold start while the camera is already gone produces no onDisconnect/onDettach —
    // there is no device to disconnect — so nothing would ever alert. This is a ONE-SHOT
    // check, not a watchdog: a normal onConnect has already driven onCameraBack by the time
    // it runs, making it a no-op in the healthy case. A repeating health check is explicitly
    // out of scope (spec line 95).
    private val startupCameraCheckRunnable = Runnable {
        if (mCameraHandler?.isPreviewing != true) {
            Log.w(TAG, "startup camera check: no preview after ${STARTUP_CAMERA_CHECK_DELAY_MS}ms")
            applyAlertEffects(cameraAlertState.onCameraLost(SystemClock.elapsedRealtime()))
        }
    }

    private lateinit var cameraView: UVCCameraTextureView

    private var currentState = BoothState.IDLE
    private var reviewTimer: CountDownTimer? = null
    private var preCaptureTimer: CountDownTimer? = null

    // Task 8: screen brightness. Window-level only (see applyBrightness), so it
    // needs no permission and Android restores normal brightness automatically
    // if this app loses focus or dies.
    private var lastTouchElapsedMs = SystemClock.elapsedRealtime()
    private var appliedBrightness = -1f

    // I3: loadWebhookUrl goes through EncryptedSharedPreferences/MasterKeys, which can throw
    // on a corrupted keyset or a restore onto new hardware. This provider is called from the
    // alert path (heartbeat -> applyAlertEffects -> SEND_DISCONNECT_ALERT) on the main thread,
    // so an uncaught throw would kill the app during the very outage it is reporting.
    private val discordNotifier = DiscordNotifier {
        runCatching { AlertSettingsStore.loadWebhookUrl(this) }.getOrNull()
    }

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

    // Heartbeat runnable: updates the watchdog preference and drives the alert
    // state machine's clock. 5s granularity against a 30s debounce is plenty,
    // and reusing this runnable avoids introducing another thread.
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            WatchdogScheduler.updateHeartbeat(this@MainActivity)
            applyAlertEffects(cameraAlertState.onTick(SystemClock.elapsedRealtime()))
            applyBrightness()
            mainHandler.postDelayed(this, 5000)
        }
    }

    // Ruling 8: currentState == IDLE alone isn't enough here — showAdminPinDialog()
    // calls resetToIdle(), so the booth sits at IDLE the whole time an admin dialog
    // is open. adminDialog?.isShowing guards against starting a countdown behind it.
    // The reschedule below stays unconditional so capture resumes once the dialog closes.
    private val demoCaptureRunnable = object : Runnable {
        override fun run() {
            if (isDemoModeEnabled() &&
                currentState == BoothState.IDLE &&
                adminDialog?.isShowing != true &&
                mCameraHandler?.isPreviewing == true
            ) {
                startPreCaptureCountdown()
            }
            mainHandler.postDelayed(this, DEMO_CAPTURE_INTERVAL_MS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        preCaptureTimer?.cancel()
        reviewTimer?.cancel()
        mainHandler.removeCallbacks(captureRunnable)
        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.removeCallbacks(gearHideRunnable)
        mainHandler.removeCallbacks(demoCaptureRunnable)
        mainHandler.removeCallbacks(startupCameraCheckRunnable)
        // The USB alert wiring posts anonymous lambdas (onConnect/onDisconnect/onDettach)
        // that can't be removed by reference above. mUSBMonitor is never unregistered, so
        // one could still be queued here; drop everything before the executor beneath
        // discordNotifier.shutdown() goes away, or a late SEND_* effect throws
        // RejectedExecutionException on the main thread.
        mainHandler.removeCallbacksAndMessages(null)
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
        faultOverlay = findViewById(R.id.faultOverlay)
        // The XML's @string/fault_battery_swap is a design-time preview only; the copy the
        // guest actually sees is driven from the tested AlertMessages constant so the two
        // can't silently drift apart.
        faultOverlay.text = AlertMessages.FAULT_SCREEN_TEXT
        demoBanner = findViewById(R.id.demoBanner)
        syncDemoBanner()
        // Must run after the views above are bound: applyBrightness() now reads
        // faultOverlay.visibility (M2), which is lateinit until findViewById above.
        applyBrightness()
        mainHandler.postDelayed(demoCaptureRunnable, DEMO_CAPTURE_INTERVAL_MS)

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
        // Attached to the fault overlay too — see revealGear for why the root listener
        // alone is not enough once the overlay is up.
        rootLayout.setOnLongClickListener(revealGear)
        faultOverlay.setOnLongClickListener(revealGear)
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
        // onStart can run more than once for this Activity, so drop any pending copy
        // first — the check must never be scheduled twice.
        mainHandler.removeCallbacks(startupCameraCheckRunnable)
        mainHandler.postDelayed(startupCameraCheckRunnable, STARTUP_CAMERA_CHECK_DELAY_MS)
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(startupCameraCheckRunnable)
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

    fun isDemoModeEnabled(): Boolean = DemoModeStore.isEnabled(this)

    private fun syncDemoBanner() {
        demoBanner.visibility = if (isDemoModeEnabled()) View.VISIBLE else View.GONE
    }

    // Task 8: any touch anywhere wakes the screen. Returning super means the
    // touch still reaches whatever view is underneath (including the Task 6
    // fault overlay, which is clickable="true" specifically so it swallows
    // taps and blocks a countdown against a dead camera) - so a tap on the
    // idle screen both wakes the display and starts the countdown in one go,
    // while a tap on the fault overlay only wakes the display.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouchElapsedMs = SystemClock.elapsedRealtime()
        applyBrightness()
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Window-level brightness only: needs no permission, touches nothing
     * system-wide, and Android restores it if the app loses focus or dies.
     */
    private fun applyBrightness() {
        // M2: the fault screen is the spec's PRIMARY alert channel ("the screen carries it").
        // SHOW_FAULT_SCREEN pins currentState to IDLE, so without the overlay check the booth
        // would dim its own emergency message to 30% after 30s of nobody touching it — which is
        // exactly what happens during a real outage.
        val target = BrightnessPolicy.brightnessFor(
            demoMode = isDemoModeEnabled(),
            isIdle = currentState == BoothState.IDLE && faultOverlay.visibility != View.VISIBLE,
            msSinceLastTouch = SystemClock.elapsedRealtime() - lastTouchElapsedMs
        )
        if (target == appliedBrightness) return
        appliedBrightness = target
        window.attributes = window.attributes.apply { screenBrightness = target }
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
                    // M4: the camera can die between this post to the camera worker and the
                    // callback landing on the main thread. By then SHOW_FAULT_SCREEN has already
                    // run resetToIdle(); carrying on would drive the review screen up behind the
                    // fault overlay and let the 8s auto-advance upload a garbage frame.
                    if (cameraAlertState.phase != CameraAlertState.Phase.HEALTHY) {
                        Log.w(TAG, "takePhoto: camera lost mid-capture, dropping frame")
                        return@post
                    }
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

            val finalFile = UploadQueueManager.nextAvailableFile(pendingDir, demo = isDemoModeEnabled())
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
        adminDialog = dialog

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

        // I3: the keystore behind AlertSettingsStore can throw on a corrupted keyset or a
        // restore onto new hardware. Treat that as "no webhook configured" rather than
        // crashing the admin screen.
        if (runCatching { AlertSettingsStore.hasWebhookUrl(this) }.getOrDefault(false)) {
            inputWebhookUrl.hint = getString(R.string.hint_webhook_unchanged)
        }

        btnSendTestAlert.setOnClickListener {
            if (runCatching { AlertSettingsStore.hasWebhookUrl(this) }.getOrDefault(false)) {
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

        val checkDemoMode = view.findViewById<CheckBox>(R.id.checkDemoMode)
        checkDemoMode.isChecked = isDemoModeEnabled()

        adminDialog = AlertDialog.Builder(this)
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

                // M6: written before the credentials validation below, which short-circuits
                // with return@setPositiveButton. On a partly configured booth the demo toggle
                // would otherwise silently refuse to persist.
                DemoModeStore.setEnabled(this, checkDemoMode.isChecked)
                syncDemoBanner()

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
            alertPhase = cameraAlertState.phase.name,
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

    /** Applies the state machine's decisions. Main thread only. */
    private fun applyAlertEffects(effects: List<AlertEffect>) {
        for (effect in effects) {
            when (effect) {
                AlertEffect.SHOW_FAULT_SCREEN -> {
                    // A countdown or review screen in flight when the camera dies must not
                    // keep running behind the overlay — same defect class as the admin-PIN
                    // fix: an in-flight timer ignoring a state interruption. resetToIdle()
                    // cancels both timers and drops currentState back to IDLE so that when
                    // HIDE_FAULT_SCREEN later fires, the guest lands on a clean idle booth
                    // instead of a stale review screen for a frame that was never taken.
                    resetToIdle()
                    faultOverlay.visibility = View.VISIBLE
                }
                AlertEffect.HIDE_FAULT_SCREEN -> faultOverlay.visibility = View.GONE
                AlertEffect.SEND_DISCONNECT_ALERT ->
                    discordNotifier.send(AlertMessages.disconnect(isDemoModeEnabled()), mentionEveryone = true)
                AlertEffect.SEND_ALL_CLEAR ->
                    discordNotifier.send(AlertMessages.allClear(isDemoModeEnabled()), mentionEveryone = false)
            }
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
                // USBMonitor delivers onConnect on its own async handler thread, not the
                // main thread, so cameraAlertState (main-thread-only) is driven via mainHandler.
                //
                // I2: onConnect is the event, not evidence that the preview actually came up —
                // spec R7 documents an undiagnosed stall specifically on start/reconnect. Firing
                // onCameraBack here would hide the fault screen and post "back up" while the booth
                // is still frozen, turning back the person walking over to fix it. So the check is
                // deferred past the same delay startPreview() already uses for calibration (plus a
                // margin) and gated on isPreviewing. If the preview did not come up, the overlay
                // stays and nothing is sent. nowMs is read when the check runs, so the debounce
                // arithmetic reflects when recovery was confirmed.
                mainHandler.postDelayed({
                    if (mCameraHandler?.isPreviewing == true) {
                        applyAlertEffects(cameraAlertState.onCameraBack(SystemClock.elapsedRealtime()))
                    } else {
                        Log.w(TAG, "onConnect: preview not up after reconnect; holding fault screen")
                        recordCameraError(
                            "reconnect without preview at uptime " +
                                "${SystemClock.elapsedRealtime() - appStartedElapsedMs}ms"
                        )
                    }
                }, CAMERA_CALIBRATION_DELAY_MS + RECONNECT_CONFIRM_MARGIN_MS)
            }

            override fun onDisconnect(device: UsbDevice?, ctrlBlock: UsbControlBlock?) {
                //if (MainActivity.DEBUG) Log.v(MainActivity.TAG, "onDisconnect:")
                if (mCameraHandler != null) {
                    mCameraHandler!!.close()
                    // TODO - Stop showing the button if there is no camera
                    //setCameraButton(false)
                }
                // Idempotent: onDisconnect and onDettach both fire for one unplug.
                // Driven via mainHandler since USBMonitor's delivery thread for this
                // callback should not be relied upon; cameraAlertState is main-thread-only.
                val nowMs = SystemClock.elapsedRealtime()
                mainHandler.post { applyAlertEffects(cameraAlertState.onCameraLost(nowMs)) }
            }

            override fun onDettach(device: UsbDevice?) {
                Toast.makeText(this@MainActivity, "Camera Disconnected", Toast.LENGTH_SHORT).show()
                recordCameraError("onDettach at ${System.currentTimeMillis()}")
                // USBMonitor delivers onDettach on its own async handler thread, not the
                // main thread, so cameraAlertState (main-thread-only) is driven via mainHandler.
                val nowMs = SystemClock.elapsedRealtime()
                mainHandler.post { applyAlertEffects(cameraAlertState.onCameraLost(nowMs)) }
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
        private const val DEMO_CAPTURE_INTERVAL_MS = 60_000L
        private const val GEAR_VISIBLE_MS = 8_000L
        /** One-shot grace period for the camera to come up from cold before we call it lost. */
        private const val STARTUP_CAMERA_CHECK_DELAY_MS = 15_000L
        /** Slack on top of CAMERA_CALIBRATION_DELAY_MS before a reconnect counts as confirmed. */
        private const val RECONNECT_CONFIRM_MARGIN_MS = 700L
    }
}
