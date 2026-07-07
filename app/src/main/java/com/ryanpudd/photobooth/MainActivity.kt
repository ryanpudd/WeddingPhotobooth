package com.ryanpudd.photobooth

import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

enum class BoothState {
    IDLE, COUNTDOWN_PRECAPTURE, CAPTURING, REVIEW
}

class MainActivity : AppCompatActivity() {

    private lateinit var statusOverlayText: TextView
    private lateinit var btnKeep: Button
    private lateinit var btnRetake: Button

    private var currentState = BoothState.IDLE
    private var reviewTimer: CountDownTimer? = null
    private var preCaptureTimer: CountDownTimer? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureRunnable = Runnable { showReviewScreen() }
    
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

        // Initialize and start watchdog heartbeat
        WatchdogScheduler.schedule(this)
        mainHandler.post(heartbeatRunnable)

        statusOverlayText = findViewById(R.id.statusOverlayText)
        btnKeep = findViewById(R.id.btnKeep)
        btnRetake = findViewById(R.id.btnRetake)

        findViewById<View>(android.R.id.content).setOnClickListener {
            if (currentState == BoothState.IDLE) {
                startPreCaptureCountdown()
            }
        }

        btnKeep.setOnClickListener { resetToIdle() }
        btnRetake.setOnClickListener { startPreCaptureCountdown() }

        resetToIdle()
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

        startReviewAutoAdvanceCountdown()
    }

    private fun startReviewAutoAdvanceCountdown() {
        reviewTimer?.cancel()
        reviewTimer = object : CountDownTimer(8000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000) + 1
                statusOverlayText.text = getString(R.string.saving_in_seconds, seconds)
            }

            override fun onFinish() {
                // Auto-save photo here
                resetToIdle()
            }
        }.start()
    }
}
