# Camera Idle Suspend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After 3 minutes idle, release the camera so the kernel's USB autosuspend can cut its power, and reopen it transparently on the guest's next capture tap — without ever leaving the booth silently dead if the resume fails.

**Architecture:** A pure, Android-free `CameraSuspendPolicy` object decides *whether* to suspend, evaluated from the *existing* 5-second heartbeat (no new thread), the same way `BrightnessPolicy` already decides dimming. `MainActivity` executes the decision by calling the existing `UVCCameraHandler.close()` (already used in the disconnect path) and, on the next capture tap, reopens the camera by replaying `onConnect`'s `open(ctrlBlock)` + `startPreview()` sequence against a `UsbControlBlock` retained from the last real `onConnect` — no new USB enumeration, no `USBMonitor` involvement. A dedicated one-shot confirm check, mirroring the existing `startupCameraCheckRunnable`, declares a real camera fault (reusing the existing fault-screen + Discord-alert path) if the resumed camera never starts previewing, so a failed resume is never silent.

**Tech Stack:** Kotlin, Android (minSdk/targetSdk 27, compileSdk 33), saki4510t UVCCamera AARs, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-26-camera-idle-suspend-design.md`

## Global Constraints

- `minSdk 27`, `targetSdk 27`, `compileSdk 33`, `abiFilters` = `armeabi-v7a` only.
- **No new Gradle dependencies.**
- **Unit tests are plain JVM JUnit 4** in `app/src/test/java/com/ryanpudd/photobooth/`. There is no Robolectric. Test code and the code under test must not touch Android framework classes at runtime.
- Build requires the UVCCamera AARs at `C:/workspace/repos/github.com/saki4510t/UVCCamera` (set via `uvccRoot` in `settings.gradle.kts`).
- Test command: `./gradlew :app:testDebugUnitTest`. Build command: `./gradlew :app:assembleDebug`.
- Follow existing patterns: `object` singletons for stateless pure decisions (e.g. `BrightnessPolicy`), background work off the UI thread, explicit `mainHandler.removeCallbacks(...)` for every named runnable in `onDestroy`.
- Webcam is a Logitech BRIO: `vendor-id 1133`, `product-id 2142`. USB autosuspend is already configured on it (`control=auto`, `autosuspend_delay_ms=2000`) — confirmed present, not something this plan configures.
- **Suspend threshold: 3 minutes** (`180_000L` ms) of idle, only from `BoothState.IDLE`, never in demo mode, never while an admin dialog is open.
- **Resume grace period: 15 seconds** (`15_000L` ms), deliberately reused from `STARTUP_CAMERA_CHECK_DELAY_MS`'s existing conservative value — the design defers measuring the real resume latency to hardware testing in Task 2 rather than guessing it up front.
- **Key design constraint this plan satisfies:** the 2026-09-20 spec explicitly forbade a camera cooldown before camera-health detection existed, because a deliberately-suspended camera that fails to wake would never fire `onDettach`, leaving the booth silently dead. That detection (`9936cf4`) is what Task 2's resume-confirm check reuses.
- Camera suspend must **never** trigger while `currentState != BoothState.IDLE` — the camera must never go down mid-capture or mid-review.

---

### Task 1: Camera suspend policy (pure)

Decides *whether* to suspend the camera this heartbeat tick. Pure and no Android imports, exactly like `BrightnessPolicy`, so the idle threshold and every exemption (demo mode, admin dialog, non-idle state, already-suspended) are tested without touching a device.

**Files:**
- Create: `app/src/main/java/com/ryanpudd/photobooth/CameraSuspendPolicy.kt`
- Test: `app/src/test/java/com/ryanpudd/photobooth/CameraSuspendPolicyTest.kt`

**Interfaces:**
- Consumes: `BoothState` (existing enum in `MainActivity.kt`, no Android imports)
- Produces:
  - `object CameraSuspendPolicy` with `const val SUSPEND_AFTER_MS = 180_000L`
  - `fun shouldSuspend(currentState: BoothState, cameraSuspended: Boolean, demoMode: Boolean, adminDialogShowing: Boolean, msSinceLastTouch: Long): Boolean`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/ryanpudd/photobooth/CameraSuspendPolicyTest.kt`:

```kotlin
package com.ryanpudd.photobooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSuspendPolicyTest {

    private fun shouldSuspend(
        currentState: BoothState = BoothState.IDLE,
        cameraSuspended: Boolean = false,
        demoMode: Boolean = false,
        adminDialogShowing: Boolean = false,
        msSinceLastTouch: Long = 0L
    ): Boolean = CameraSuspendPolicy.shouldSuspend(
        currentState, cameraSuspended, demoMode, adminDialogShowing, msSinceLastTouch
    )

    @Test
    fun constantMatchesTheAgreedValue() {
        assertEquals(180_000L, CameraSuspendPolicy.SUSPEND_AFTER_MS)
    }

    @Test
    fun idleBeyondTimeout_suspends() {
        assertTrue(shouldSuspend(msSinceLastTouch = 181_000L))
    }

    @Test
    fun exactlyAtTheTimeout_suspends() {
        assertTrue(shouldSuspend(msSinceLastTouch = 180_000L))
    }

    @Test
    fun idleWithinTimeout_doesNotSuspend() {
        assertFalse(shouldSuspend(msSinceLastTouch = 179_000L))
    }

    @Test
    fun capturingNeverSuspends_howeverLongSinceTheLastTouch() {
        assertFalse(shouldSuspend(currentState = BoothState.CAPTURING, msSinceLastTouch = 600_000L))
    }

    @Test
    fun reviewNeverSuspends_howeverLongSinceTheLastTouch() {
        assertFalse(shouldSuspend(currentState = BoothState.REVIEW, msSinceLastTouch = 600_000L))
    }

    @Test
    fun demoModeNeverSuspends_soTheDrainTestStaysAWorstCase() {
        assertFalse(shouldSuspend(demoMode = true, msSinceLastTouch = 600_000L))
    }

    @Test
    fun adminDialogOpenNeverSuspends() {
        assertFalse(shouldSuspend(adminDialogShowing = true, msSinceLastTouch = 600_000L))
    }

    @Test
    fun alreadySuspended_doesNotSuspendAgain() {
        assertFalse(shouldSuspend(cameraSuspended = true, msSinceLastTouch = 600_000L))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*CameraSuspendPolicyTest*"`
Expected: FAIL — `Unresolved reference: CameraSuspendPolicy`

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/ryanpudd/photobooth/CameraSuspendPolicy.kt`:

```kotlin
package com.ryanpudd.photobooth

/**
 * Decides when to release the camera so the kernel's USB autosuspend can cut
 * its power, and never lets it if the booth is in a state where the camera
 * must stay live.
 *
 * Demo mode never suspends: the drain test is meant to be the worst case, and
 * exercising this path there would undermine that. An open admin dialog never
 * suspends either, so a helper mid-setup never gets the camera pulled out
 * from under them.
 */
object CameraSuspendPolicy {

    const val SUSPEND_AFTER_MS = 180_000L

    fun shouldSuspend(
        currentState: BoothState,
        cameraSuspended: Boolean,
        demoMode: Boolean,
        adminDialogShowing: Boolean,
        msSinceLastTouch: Long
    ): Boolean {
        if (cameraSuspended) return false
        if (demoMode) return false
        if (adminDialogShowing) return false
        if (currentState != BoothState.IDLE) return false
        return msSinceLastTouch >= SUSPEND_AFTER_MS
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*CameraSuspendPolicyTest*"`
Expected: PASS, 9 tests

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ryanpudd/photobooth/CameraSuspendPolicy.kt app/src/test/java/com/ryanpudd/photobooth/CameraSuspendPolicyTest.kt
git commit -m "feat: add camera suspend policy for idle battery savings

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AGz3hsSLyn8QH3SV5R73V3"
```

---

### Task 2: Wire idle-suspend and resume into MainActivity

Connects `CameraSuspendPolicy` to the real camera handle, adds the resume path (reopening via a retained `UsbControlBlock`, no new USB enumeration), and adds a dedicated one-shot confirm check that declares a real camera fault — reusing the existing fault-screen + Discord-alert path — if the resumed camera never starts previewing.

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/ryanpudd/photobooth/MainActivity.kt` (fields near `cameraAlertState`; `startupCameraCheckRunnable` region; `heartbeatRunnable`; `statusOverlayText` click listener; `mOnDeviceConnectListener.onConnect`; `onDestroy`; companion object)

**Interfaces:**
- Consumes: `CameraSuspendPolicy.shouldSuspend` (Task 1)
- Produces:
  - `MainActivity.cameraSuspended: Boolean` (private)
  - `MainActivity.pendingCaptureAfterResume: Boolean` (private)
  - `MainActivity.suspendCamera()`, `MainActivity.resumeCameraForCapture()` (private)

- [ ] **Step 1: Add the resume string**

In `app/src/main/res/values/strings.xml`, before `</resources>`:

```xml
    <string name="camera_warming_up">Getting ready…</string>
```

- [ ] **Step 2: Add the new fields**

In `MainActivity.kt`, immediately after this existing line (around line 97):

```kotlin
    private val cameraAlertState = CameraAlertState()
```

add:

```kotlin

    // Camera idle-suspend: closes the camera after CameraSuspendPolicy.SUSPEND_AFTER_MS of
    // idle to save battery, and reopens it on the next capture tap using the control block
    // retained from the last onConnect — no new USB enumeration, no USBMonitor involvement.
    // cameraAlertState stays HEALTHY throughout a deliberate suspend; only a failed resume
    // (see resumeConfirmRunnable) ever raises a real fault.
    private var cameraSuspended = false
    private var pendingCaptureAfterResume = false
    private var lastCtrlBlock: UsbControlBlock? = null
```

- [ ] **Step 3: Add the resume-confirm runnable and the suspend/resume functions**

Immediately after the existing `startupCameraCheckRunnable` block (around lines 123-133):

```kotlin
    private val startupCameraCheckRunnable = Runnable {
        if (mCameraHandler?.isPreviewing != true) {
            Log.w(TAG, "startup camera check: no preview after ${STARTUP_CAMERA_CHECK_DELAY_MS}ms")
            applyAlertEffects(cameraAlertState.onLost(SystemClock.elapsedRealtime()))
        }
    }
```

add:

```kotlin

    // Mirrors startupCameraCheckRunnable's pattern for the wake-from-suspend case.
    // onConnect's reconnect-confirm path (startReconnectConfirm) assumes a real USB
    // reconnect already happened and only "holds" whatever fault state already exists;
    // that never applies here, since a deliberate suspend never touches cameraAlertState.
    // This is the one place a failed resume turns into a real, alerted fault.
    private val resumeConfirmRunnable = Runnable {
        pendingCaptureAfterResume = false
        if (mCameraHandler?.isPreviewing == true) {
            startPreCaptureCountdown()
        } else {
            Log.w(TAG, "resume: no preview after ${RESUME_CONFIRM_DELAY_MS}ms")
            recordCameraError(
                "resume without preview at uptime " +
                    "${SystemClock.elapsedRealtime() - appStartedElapsedMs}ms"
            )
            applyAlertEffects(cameraAlertState.onLost(SystemClock.elapsedRealtime()))
        }
    }

    /** Releases the camera session so the kernel's USB autosuspend can cut power. */
    private fun suspendCamera() {
        Log.i(TAG, "suspending camera after ${CameraSuspendPolicy.SUSPEND_AFTER_MS}ms idle")
        mCameraHandler?.close()
        cameraSuspended = true
    }

    /**
     * Reopens a suspended camera using the control block retained from the last onConnect.
     * Deliberately does not start the capture countdown itself — resumeConfirmRunnable does,
     * once the resumed camera is confirmed actually previewing.
     */
    private fun resumeCameraForCapture() {
        val ctrlBlock = lastCtrlBlock
        if (ctrlBlock == null) {
            Log.w(TAG, "resume requested but no control block retained from onConnect")
            applyAlertEffects(cameraAlertState.onLost(SystemClock.elapsedRealtime()))
            return
        }
        cameraSuspended = false
        pendingCaptureAfterResume = true
        mCameraHandler!!.open(ctrlBlock)
        startPreview()
        // startPreview() -> resetToIdle() just overwrote this text; set it after, not before.
        statusOverlayText.text = getString(R.string.camera_warming_up)
        mainHandler.removeCallbacks(resumeConfirmRunnable)
        mainHandler.postDelayed(resumeConfirmRunnable, RESUME_CONFIRM_DELAY_MS)
    }
```

- [ ] **Step 4: Wire the suspend check into the heartbeat**

Replace the existing `heartbeatRunnable` (around lines 183-207) with:

```kotlin
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            WatchdogScheduler.updateHeartbeat(this@MainActivity)
            // Safety net: the onConnect confirm block is one-shot, so if that single
            // check ever misses, nothing else clears the fault screen. Re-check here on
            // every heartbeat and clear it whenever the camera is demonstrably previewing
            // again. Runs before onTick so a recovery that lands right as the debounce
            // expires clears the fault instead of first firing a false disconnect alert.
            if (cameraAlertState.phase != CameraAlertState.Phase.HEALTHY &&
                mCameraHandler?.isPreviewing == true) {
                applyAlertEffects(cameraAlertState.onBack(SystemClock.elapsedRealtime()))
            }
            applyAlertEffects(cameraAlertState.onTick(SystemClock.elapsedRealtime()))

            if (isOnExternalPower()) {
                applyPowerAlertEffects(powerAlertState.onBack(SystemClock.elapsedRealtime()))
            } else {
                applyPowerAlertEffects(powerAlertState.onLost(SystemClock.elapsedRealtime()))
            }
            applyPowerAlertEffects(powerAlertState.onTick(SystemClock.elapsedRealtime()))

            if (CameraSuspendPolicy.shouldSuspend(
                    currentState = currentState,
                    cameraSuspended = cameraSuspended,
                    demoMode = isDemoModeEnabled(),
                    adminDialogShowing = adminDialog?.isShowing == true,
                    msSinceLastTouch = SystemClock.elapsedRealtime() - lastTouchElapsedMs
                )
            ) {
                suspendCamera()
            }

            applyBrightness()
            mainHandler.postDelayed(this, 5000)
        }
    }
```

- [ ] **Step 5: Wire the resume trigger into the capture tap, with a duplicate-tap guard**

Replace the existing `statusOverlayText` click listener (around lines 313-317):

```kotlin
        statusOverlayText.setOnClickListener {
            if (currentState == BoothState.IDLE) {
                startPreCaptureCountdown()
            }
        }
```

with:

```kotlin
        statusOverlayText.setOnClickListener {
            when {
                // First tap while suspended: start the resume, don't start a countdown yet.
                cameraSuspended -> resumeCameraForCapture()
                // A resume is already in flight (resumeConfirmRunnable hasn't fired yet):
                // ignore extra taps rather than starting a countdown against a camera that
                // may not be previewing yet.
                pendingCaptureAfterResume -> Unit
                currentState == BoothState.IDLE -> startPreCaptureCountdown()
            }
        }
```

- [ ] **Step 6: Retain the control block and clear stale suspend state on every real connect**

Replace the existing `onConnect` (around lines 1052-1072):

```kotlin
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
                mainHandler.post { startReconnectConfirm() }
            }
```

with:

```kotlin
            override fun onConnect(
                device: UsbDevice?,
                ctrlBlock: UsbControlBlock?,
                createNew: Boolean
            ) {
                //if (MainActivity.DEBUG) Log.v(MainActivity.TAG, "onConnect:")
                // Retained so a later idle-suspend can reopen the camera without a real USB
                // reconnect. Also clears any stale suspended/pending state from a physical
                // unplug that happened while the camera was suspended — onConnect always means
                // the camera session is (re)opening for real, regardless of how it got here.
                lastCtrlBlock = ctrlBlock
                cameraSuspended = false
                pendingCaptureAfterResume = false
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
                mainHandler.post { startReconnectConfirm() }
            }
```

- [ ] **Step 7: Clean up the new runnable in onDestroy**

In `onDestroy()`, immediately after this existing line (around line 234):

```kotlin
        mainHandler.removeCallbacks(startupCameraCheckRunnable)
```

add:

```kotlin
        mainHandler.removeCallbacks(resumeConfirmRunnable)
```

- [ ] **Step 8: Add the resume grace period constant**

In the `companion object` (around line 1146), immediately after:

```kotlin
        private const val STARTUP_CAMERA_CHECK_DELAY_MS = 15_000L
```

add:

```kotlin
        /**
         * One-shot grace period for a suspended camera to resume previewing after a
         * wake tap. Deliberately reuses the cold-start value: resuming from a lightweight
         * suspend should be faster than full USB enumeration, but that has not been
         * measured on hardware yet. Tighten this once Step 9's hardware test measures it.
         */
        private const val RESUME_CONFIRM_DELAY_MS = 15_000L
```

- [ ] **Step 9: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, all tests (including the 9 new `CameraSuspendPolicyTest` cases) — no regressions.

- [ ] **Step 10: Build and verify on the tablet**

Run: `./gradlew :app:installDebug`

1. Camera connected, booth idle. Leave it untouched for **3+ minutes**. **Expect:** `adb logcat` shows `suspending camera after 180000ms idle`; the preview view freezes on its last frame (no crash, no fault screen).
2. **Tap once.** **Expect:** the text immediately changes to *"Getting ready…"*, and within a few seconds (watch logcat for `isPreviewing` timing) it transitions straight into the normal 3-2-1 countdown and takes a photo successfully. No "capture timed out" on this first tap.
3. Record the actual elapsed time from tap to `isPreviewing == true` in logcat. If it's reliably well under 15s, note the real number as a candidate to tighten `RESUME_CONFIRM_DELAY_MS` in a follow-up (do not change the constant now — this is a measurement, not a required edit).
4. **Simulate a failed resume:** go idle 3+ minutes again, tap once, and immediately **physically unplug the BRIO** during the "Getting ready…" window. **Expect:** the fault screen appears within `RESUME_CONFIRM_DELAY_MS`, and the normal 30s-debounced Discord alert still fires per the existing disconnect path (this is now a real `onDettach`, handled exactly as before).
5. **Demo mode is unaffected:** enable demo mode, leave it for 5 minutes. **Expect:** auto-capture keeps firing roughly once a minute; "Getting ready…" never appears; logcat never logs a suspend. Disable demo mode afterward.
6. **Admin dialog blocks suspend:** open admin settings (long-press → gear → PIN) and leave it open for 3+ minutes. **Expect:** no `suspending camera` log line while the dialog is open. Close it and confirm suspend resumes working afterward.
7. **Duplicate-tap guard:** go idle 3+ minutes, tap once, then tap several more times rapidly during the "Getting ready…" window. **Expect:** no more than one countdown ever starts, and it starts only once the camera is confirmed previewing.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/java/com/ryanpudd/photobooth/MainActivity.kt
git commit -m "feat: suspend the camera on idle and resume it on the next capture tap

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AGz3hsSLyn8QH3SV5R73V3"
```

---

### Task 3: Reconcile the docs that previously ruled this out

The 2026-09-20 spec and the battery-drain-test runbook both explicitly say *not* to build this until camera-health detection existed. Both need a pointer forward now that it's built, so a future reader doesn't find two contradictory documents.

**Files:**
- Modify: `docs/superpowers/specs/2026-09-20-booth-resilience-spec.md`
- Modify: `docs/superpowers/runbooks/battery-drain-test.md`

**Interfaces:**
- Consumes: nothing
- Produces: nothing (docs only)

- [ ] **Step 1: Update the old spec's out-of-scope table**

In `docs/superpowers/specs/2026-09-20-booth-resilience-spec.md`, replace this row in the "Out of scope" table:

```markdown
| Idle preview shutdown / camera cooldown | Cycles the exact path that stalls, and converts a loud failure into a silent one. Revisit only if the drain test says runtime is marginal |
```

with:

```markdown
| ~~Idle preview shutdown / camera cooldown~~ | Implemented once camera-health detection landed — see `docs/superpowers/specs/2026-09-26-camera-idle-suspend-design.md` |
```

- [ ] **Step 2: Point the old spec's key design constraint forward**

In the same file, at the end of the "Key design constraint" section, after:

```markdown
**A camera cooldown cannot ship before camera-health detection.** If the app
switches the camera off deliberately and it fails to wake, `onDettach` never
fires, so the booth sits looking healthy while being dead and nothing alerts
anyone. Recorded here so the reasoning is not lost when the drain test result
makes the cooldown tempting.
```

add:

```markdown

> **Update 2026-09-26:** camera-health detection landed in `9936cf4`, satisfying
> this constraint. The cooldown is now designed and implemented — see
> `docs/superpowers/specs/2026-09-26-camera-idle-suspend-design.md`.
```

- [ ] **Step 3: Update the battery-drain-test runbook's caveat**

In `docs/superpowers/runbooks/battery-drain-test.md`, after the existing final paragraph:

```markdown
> **Do not build the camera cooldown without camera-health detection first.**
> If the app switches the camera off deliberately and it fails to wake,
> `onDettach` never fires, so the booth sits looking healthy while being dead
> and nothing alerts anyone. See the spec's "Key design constraint".
```

add:

```markdown
>
> **Update 2026-09-26:** this constraint is now satisfied and the cooldown is
> built — see `docs/superpowers/specs/2026-09-26-camera-idle-suspend-design.md`.
> This drain test still measures the worst case unaffected: demo mode never
> suspends the camera, so its result is unchanged by the new feature.
```

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-09-20-booth-resilience-spec.md docs/superpowers/runbooks/battery-drain-test.md
git commit -m "docs: point the old cooldown ban forward at the shipped design

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AGz3hsSLyn8QH3SV5R73V3"
```

---
