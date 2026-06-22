package com.ryanpudd.photobooth

import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

enum class BoothState {
    IDLE, COUNTDOWN_PRECAPTURE, REVIEW
}

class MainActivity : AppCompatActivity() {

    private lateinit var statusOverlayText: TextView
    private lateinit var btnKeep: Button
    private lateinit var btnRetake: Button

    private var currentState = BoothState.IDLE
    private var reviewTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Force fullscreen immersion
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)

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

    private fun resetToIdle() {
        currentState = BoothState.IDLE
        reviewTimer?.cancel()
        statusOverlayText.text = "Tap to take your photo!"
        statusOverlayText.visibility = View.VISIBLE
        btnKeep.visibility = View.GONE
        btnRetake.visibility = View.GONE
    }

    private fun startPreCaptureCountdown() {
        currentState = BoothState.COUNTDOWN_PRECAPTURE
        btnKeep.visibility = View.GONE
        btnRetake.visibility = View.GONE
        statusOverlayText.visibility = View.VISIBLE

        object : CountDownTimer(3000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000) + 1
                statusOverlayText.text = "$seconds..."
            }

            override fun onFinish() {
                statusOverlayText.text = "Smile!"
                // Trigger capture pipeline here
                showReviewScreen()
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
                statusOverlayText.text = "Saving in $seconds..."
            }

            override fun onFinish() {
                // Auto-save photo here
                resetToIdle()
            }
        }.start()
    }
}
