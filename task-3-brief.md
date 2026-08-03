### Task 3: Base Activity & UI State Machine

**Files:**
- Create: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/java/com/ryanpudd/photobooth/MainActivity.kt`

**Interfaces:**
- Consumes: App module environment
- Produces: A single Activity with 4 states: IDLE, COUNTDOWN_PRECAPTURE, REVIEW, and transition logic.

- [ ] **Step 1: Create Layout**

```xml
<!-- app/src/main/res/layout/activity_main.xml -->
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout 
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#000000">

    <!-- Camera Preview Surface (stub for now, will be updated to TextureView) -->
    <FrameLayout
        android:id="@+id/cameraPreviewContainer"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <!-- Overlay UI Text -->
    <TextView
        android:id="@+id/statusOverlayText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="48sp"
        android:textColor="#FFFFFF"
        android:text="Tap to take your photo!"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <Button
        android:id="@+id/btnKeep"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Keep"
        android:visibility="gone"
        android:textSize="32sp"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_margin="32dp"/>

    <Button
        android:id="@+id/btnRetake"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Retake"
        android:visibility="gone"
        android:textSize="32sp"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        android:layout_margin="32dp"/>

</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 2: Create Main Activity**

```kotlin
// app/src/main/java/com/ryanpudd/photobooth/MainActivity.kt
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
```

- [ ] **Step 3: Update Manifest**

Update `AndroidManifest.xml` to declare the `MainActivity`.

```xml
<!-- Inside <application> in app/src/main/AndroidManifest.xml -->
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="landscape">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/activity_main.xml app/src/main/java/com/ryanpudd/photobooth/MainActivity.kt app/src/main/AndroidManifest.xml
git commit -m "feat: implement main UI state machine"
```
