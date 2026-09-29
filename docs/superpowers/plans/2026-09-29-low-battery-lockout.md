# Low Battery Lockout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the tablet's own battery drops to 20%, lock the booth behind a full-screen message and alert Discord, so it can never run itself completely dead; automatically wake it back up once the battery recovers to 50%.

**Architecture:** A pure hysteresis latch (`LowBatteryPolicy`) decides, each 5-second heartbeat tick, whether the booth should be locked — lock at ≤20%, require climbing back to ≥50% to unlock, and never lock away from `BoothState.IDLE` so an in-flight photo is never interrupted. The transition is fed into a **third instance of the existing `CameraAlertState`** (already reused once for external-power loss), which gives the feature the same 30-second Discord debounce, the same idempotent show/hide, and the same effect-list plumbing everything else in this file already uses — no new state machine, only a new decision function and a new effect handler.

**Tech Stack:** Kotlin, Android (minSdk/targetSdk 27, compileSdk 33), JUnit 4.

**Spec:** This plan has no separate design-doc spec — it implements a directly-specified feature request: lock the booth at 20% tablet battery with a Discord alert and a funny full-screen message; auto re-enable at 50%.

## Global Constraints

- `minSdk 27`, `targetSdk 27`, `compileSdk 33`, `abiFilters` = `armeabi-v7a` only. **No new Gradle dependencies.**
- Unit tests are plain JVM JUnit 4 in `app/src/test/java/com/ryanpudd/photobooth/`. No Robolectric. Test code and code under test must not touch Android framework classes at runtime.
- Test command: `./gradlew :app:testDebugUnitTest`. Build command: `./gradlew :app:assembleDebug`.
- **Lock threshold: 20%** (`<=`). **Unlock threshold: 50%** (`>=`, only checked while already locked).
- Never lock while `currentState != BoothState.IDLE` — a guest mid-capture or mid-review must never be interrupted by this feature. Unlocking has no such gate (nothing is disrupted by removing an overlay).
- This is the tablet's **own** internal battery (`BatteryManager.BATTERY_PROPERTY_CAPACITY`), not the external USB pack that `powerAlertState`/`isOnExternalPower()` already track. The two features are independent and can both be active at once.
- This must apply in **demo mode too** — demo mode is explicitly used to run the battery drain test, and that test can and does deplete the tablet's own battery when the external pack can't keep up (verified empirically this session: a sustained 100-photo burst dropped the tablet's own battery from 100% to 20% net of external power). Do not copy the idle-suspend feature's demo-mode exclusion pattern here — it does not apply.
- Follow existing patterns: reuse `CameraAlertState` as a third instance (`lowBatteryAlertState`), exactly as `powerAlertState` already reuses it; a new `applyLowBatteryAlertEffects(effects: List<AlertEffect>)` mirrors `applyPowerAlertEffects`; all Discord copy lives in `AlertMessages.kt`; the on-screen overlay follows `faultOverlay`'s existing XML/behavior pattern (full-screen, `clickable="true"` so it swallows taps, long-press reveals the admin gear).

## Review Focus

- **Invalid/unsupported battery reading.** `BatteryManager.getIntProperty` can return `-1` (property unsupported) or the call can throw on some OEM builds. A bad reading must never be treated as "0%" (which would instantly lock the booth) or silently treated as "no change forever" without anyone noticing — it must simply skip that tick's lock/unlock decision.
- **Demo mode must not be excluded.** The existing idle-suspend feature explicitly *never* suspends in demo mode; a reviewer familiar with that pattern could reflexively add the same exclusion here. This feature must fire identically in demo and real use — only the Discord `[TEST]` prefix differs.
- **Never interrupts an in-progress photo.** Locking must be gated on `currentState == BoothState.IDLE`; a battery reading that crosses 20% while a guest is mid-countdown, mid-capture, or reading their review screen must wait for them to finish.
- **Admin gear must stay reachable while locked**, exactly like it does behind the camera fault overlay — a helper must be able to check diagnostics or settings without waiting for the battery to recover.
- **Hysteresis boundary correctness.** Exactly 20% must lock; 21% must not. Exactly 50% must unlock; 49% must not. Once unlocked, climbing back down must require reaching 20% again (not merely dropping below 50%) to re-lock.

---

### Task 1: Low battery hysteresis policy (pure)

Decides, each heartbeat tick, whether the booth should currently be locked. Pure and no Android imports, mirroring `CameraSuspendPolicy`, so the 20%/50% hysteresis band and the IDLE-only lock gate are tested without touching a device.

**Files:**
- Create: `app/src/main/java/com/ryanpudd/photobooth/LowBatteryPolicy.kt`
- Test: `app/src/test/java/com/ryanpudd/photobooth/LowBatteryPolicyTest.kt`

**Interfaces:**
- Consumes: `BoothState` (existing enum in `MainActivity.kt`, no Android imports)
- Produces:
  - `object LowBatteryPolicy` with `const val LOCK_AT_PERCENT = 20` and `const val UNLOCK_AT_PERCENT = 50`
  - `fun shouldBeLocked(currentlyLocked: Boolean, currentState: BoothState, batteryPercent: Int): Boolean`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/ryanpudd/photobooth/LowBatteryPolicyTest.kt`:

```kotlin
package com.ryanpudd.photobooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowBatteryPolicyTest {

    private fun shouldBeLocked(
        currentlyLocked: Boolean = false,
        currentState: BoothState = BoothState.IDLE,
        batteryPercent: Int
    ): Boolean = LowBatteryPolicy.shouldBeLocked(currentlyLocked, currentState, batteryPercent)

    @Test
    fun constantsMatchTheAgreedValues() {
        assertEquals(20, LowBatteryPolicy.LOCK_AT_PERCENT)
        assertEquals(50, LowBatteryPolicy.UNLOCK_AT_PERCENT)
    }

    @Test
    fun exactlyAtTheLockThreshold_locks() {
        assertTrue(shouldBeLocked(batteryPercent = 20))
    }

    @Test
    fun oneAboveTheLockThreshold_doesNotLock() {
        assertFalse(shouldBeLocked(batteryPercent = 21))
    }

    @Test
    fun wellBelowTheLockThreshold_locks() {
        assertTrue(shouldBeLocked(batteryPercent = 5))
    }

    @Test
    fun neverLocksOutsideIdle_evenAtZeroPercent() {
        assertFalse(shouldBeLocked(currentState = BoothState.CAPTURING, batteryPercent = 0))
        assertFalse(shouldBeLocked(currentState = BoothState.REVIEW, batteryPercent = 0))
    }

    @Test
    fun onceLocked_stayingBelowUnlockThreshold_staysLocked() {
        assertTrue(shouldBeLocked(currentlyLocked = true, batteryPercent = 49))
    }

    @Test
    fun onceLocked_exactlyAtTheUnlockThreshold_unlocks() {
        assertFalse(shouldBeLocked(currentlyLocked = true, batteryPercent = 50))
    }

    @Test
    fun onceLocked_wellAboveTheUnlockThreshold_unlocks() {
        assertFalse(shouldBeLocked(currentlyLocked = true, batteryPercent = 90))
    }

    @Test
    fun onceLocked_unlockGateIgnoresBoothState() {
        // Unlocking never interrupts anything, so it isn't gated on IDLE the way locking is.
        assertFalse(shouldBeLocked(currentlyLocked = true, currentState = BoothState.CAPTURING, batteryPercent = 60))
    }

    @Test
    fun onceUnlocked_mustReachLockThresholdAgain_notJustBelowUnlockThreshold() {
        // 45% is below the 50% unlock threshold, but above the 20% lock threshold — must not re-lock.
        assertFalse(shouldBeLocked(currentlyLocked = false, batteryPercent = 45))
    }

    @Test
    fun invalidReading_whileUnlocked_neverLocks() {
        // BatteryManager.getIntProperty can return -1 when the platform can't report a
        // level. A bad reading must never be treated as "0%", which would instantly lock.
        assertFalse(shouldBeLocked(currentlyLocked = false, batteryPercent = -1))
    }

    @Test
    fun invalidReading_whileLocked_staysLockedRatherThanGuessing() {
        // Equally, a bad reading must never be treated as "recovered" and silently unlock.
        assertTrue(shouldBeLocked(currentlyLocked = true, batteryPercent = -1))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*LowBatteryPolicyTest*"`
Expected: FAIL — `Unresolved reference: LowBatteryPolicy`

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/ryanpudd/photobooth/LowBatteryPolicy.kt`:

```kotlin
package com.ryanpudd.photobooth

/**
 * Decides whether the booth should currently be locked behind the low-battery
 * screen. A wide hysteresis band (lock at 20%, unlock at 50%) is the anti-flap
 * guard here — there is no separate debounce timer the way BrightnessPolicy or
 * CameraSuspendPolicy have one, because a 30-point gap already makes boundary
 * noise a non-issue: once locked, a momentary blip back above 20% changes
 * nothing, and it takes a real recharge to 50% to unlock again.
 *
 * Locking never happens outside BoothState.IDLE, so a guest mid-countdown,
 * mid-capture, or reading their review screen is never interrupted. Unlocking
 * has no such gate: removing a full-screen overlay never disrupts anything.
 */
object LowBatteryPolicy {

    const val LOCK_AT_PERCENT = 20
    const val UNLOCK_AT_PERCENT = 50

    fun shouldBeLocked(
        currentlyLocked: Boolean,
        currentState: BoothState,
        batteryPercent: Int
    ): Boolean {
        // BatteryManager.getIntProperty can return -1 when the platform can't report a
        // level. Treat that as "no new information" — stay exactly as locked/unlocked as
        // before, rather than guessing "0%" (instant lock) or "recovered" (silent unlock).
        if (batteryPercent < 0) return currentlyLocked
        if (currentlyLocked) {
            return batteryPercent < UNLOCK_AT_PERCENT
        }
        if (currentState != BoothState.IDLE) return false
        return batteryPercent <= LOCK_AT_PERCENT
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*LowBatteryPolicyTest*"`
Expected: PASS, 12 tests

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ryanpudd/photobooth/LowBatteryPolicy.kt app/src/test/java/com/ryanpudd/photobooth/LowBatteryPolicyTest.kt
git commit -m "feat: add low-battery lockout hysteresis policy

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AGz3hsSLyn8QH3SV5R73V3"
```

---

### Task 2: Low-battery copy (on-screen text + Discord messages)

Adds the funny lock-screen text and the two new Discord messages to the existing, already-tested copy object, following its exact established pattern (a `const val` for on-screen text, a `[TEST]`-prefixed function per Discord message, `@everyone` only on the alert that needs action).

**Files:**
- Modify: `app/src/main/java/com/ryanpudd/photobooth/AlertMessages.kt`
- Modify: `app/src/test/java/com/ryanpudd/photobooth/AlertMessagesTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `AlertMessages.LOW_BATTERY_SCREEN_TEXT: String`
  - `AlertMessages.lowBatteryLock(isDemoMode: Boolean): String`
  - `AlertMessages.lowBatteryUnlock(isDemoMode: Boolean): String`

- [ ] **Step 1: Write the failing tests**

In `app/src/test/java/com/ryanpudd/photobooth/AlertMessagesTest.kt`, add before the closing `}`:

```kotlin
    @Test
    fun lowBatteryScreenText_isTheExactAgreedCopy() {
        assertEquals(
            "😴 Whoa there, superstars — you've partied my battery right into the " +
                "ground. I need a little nap. Find someone with a charger and I'll be back for more " +
                "smiles soon!",
            AlertMessages.LOW_BATTERY_SCREEN_TEXT
        )
    }

    @Test
    fun lowBatteryLock_mentionsEveryoneAndExplainsTheFix() {
        val message = AlertMessages.lowBatteryLock(isDemoMode = false)
        assertTrue(message.contains("@everyone"))
        assertTrue(message.contains("battery"))
        assertFalse(message.contains("[TEST]"))
    }

    @Test
    fun lowBatteryLock_inDemoMode_isPrefixedSoHelpersDoNotPanic() {
        val message = AlertMessages.lowBatteryLock(isDemoMode = true)
        assertTrue(message.startsWith("[TEST]"))
        assertTrue(message.contains("@everyone"))
    }

    @Test
    fun lowBatteryUnlock_saysItsBackAndDoesNotMentionEveryone() {
        val message = AlertMessages.lowBatteryUnlock(isDemoMode = false)
        assertTrue(message.contains("back"))
        assertFalse(message.contains("@everyone"))
        assertFalse(message.contains("[TEST]"))
    }

    @Test
    fun lowBatteryUnlock_inDemoMode_isPrefixed() {
        assertTrue(AlertMessages.lowBatteryUnlock(isDemoMode = true).startsWith("[TEST]"))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*AlertMessagesTest*"`
Expected: FAIL — `Unresolved reference: LOW_BATTERY_SCREEN_TEXT` (and the two new functions)

- [ ] **Step 3: Write the implementation**

In `app/src/main/java/com/ryanpudd/photobooth/AlertMessages.kt`, add inside the `object AlertMessages` body, after `powerRestored`'s closing brace and before the object's final closing brace:

```kotlin

    /** Shown full-screen the instant the tablet's own battery hits 20%. */
    const val LOW_BATTERY_SCREEN_TEXT = "😴 Whoa there, superstars — you've partied my " +
        "battery right into the ground. I need a little nap. Find someone with a charger and " +
        "I'll be back for more smiles soon!"

    fun lowBatteryLock(isDemoMode: Boolean): String {
        val body = "😴 @everyone **The photobooth needs a nap** — tablet battery's down to " +
            "20%. Plug it into a charger (or swap the pack) and it'll wake itself back up once " +
            "it's above 50%."
        return if (isDemoMode) TEST_PREFIX + body else body
    }

    fun lowBatteryUnlock(isDemoMode: Boolean): String {
        val body = "🔋 **Photobooth is back and well-rested** — battery's above 50% " +
            "again, snapping away as normal."
        return if (isDemoMode) TEST_PREFIX + body else body
    }
```

> Note for whoever reviews this: the exact wording of `LOW_BATTERY_SCREEN_TEXT` and the two Discord messages is easy to swap later — it's one `const val` and two string literals, all in this one file, with no other code depending on the specific words (only on `@everyone`/`battery`/`back`/`[TEST]` substrings, per the tests above). Change it freely before the wedding if a funnier line comes up.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*AlertMessagesTest*"`
Expected: PASS, 15 tests (10 existing + 5 new)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ryanpudd/photobooth/AlertMessages.kt app/src/test/java/com/ryanpudd/photobooth/AlertMessagesTest.kt
git commit -m "feat: add low-battery lock screen and Discord copy

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AGz3hsSLyn8QH3SV5R73V3"
```

---

### Task 3: Wire the low-battery lockout into MainActivity

Reads the tablet's own battery percentage every heartbeat, drives a third `CameraAlertState` instance through the new hysteresis policy, and shows/hides a new full-screen overlay exactly the way the camera fault overlay already works — same swallowed taps, same admin-gear reachability, same "never dims itself" brightness exception.

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/ryanpudd/photobooth/MainActivity.kt` (fields near `powerAlertState`; `onCreate`'s view-binding and long-click-listener block; `heartbeatRunnable`; `applyBrightness`; new `applyLowBatteryAlertEffects` + `readTabletBatteryPercent` functions)

**Interfaces:**
- Consumes: `LowBatteryPolicy.shouldBeLocked` (Task 1), `AlertMessages.LOW_BATTERY_SCREEN_TEXT` / `lowBatteryLock` / `lowBatteryUnlock` (Task 2)
- Produces: nothing consumed by a later task — this is the last task in the plan

- [ ] **Step 1: Add the lock overlay to the layout**

In `app/src/main/res/layout/activity_main.xml`, add as the **last child** of the root layout, after the existing `powerWarningBanner` `TextView` and before the closing `</androidx.constraintlayout.widget.ConstraintLayout>` tag, so it draws on top of everything else — including the camera fault overlay, since a dying tablet is the more urgent of the two if both ever happen to coincide:

```xml
    <!-- Low-battery lockout — drawn last so it takes priority even over the
         camera fault overlay. Same pattern as faultOverlay: full-screen,
         clickable swallows taps, long-press still reveals the admin gear. -->
    <TextView
        android:id="@+id/lowBatteryOverlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="#CC000000"
        android:clickable="true"
        android:focusable="true"
        android:gravity="center"
        android:padding="48dp"
        android:text="@string/low_battery_lock_text"
        android:textColor="#FFFFFF"
        android:textSize="40sp"
        android:textStyle="bold"
        android:visibility="gone" />
```

- [ ] **Step 2: Add the design-time preview string**

In `app/src/main/res/values/strings.xml`, before `</resources>`:

```xml
    <string name="low_battery_lock_text">Zzz… low battery, taking a nap</string>
```

> This string is a design-time preview only, same as `fault_battery_swap` — the copy the guest actually sees is set at runtime from the tested `AlertMessages.LOW_BATTERY_SCREEN_TEXT` constant in Step 5, so the two can't silently drift apart.

- [ ] **Step 3: Add the new field**

In `MainActivity.kt`, immediately after this existing line (around line 131):

```kotlin
    private val powerAlertState = CameraAlertState()
```

add:

```kotlin

    // Third reuse of CameraAlertState: the tablet's OWN battery (not the external pack
    // powerAlertState already tracks) crossing the LowBatteryPolicy hysteresis band. Locking
    // pins currentState to IDLE and shows lowBatteryOverlay, exactly like the camera fault
    // screen; the 30s Discord debounce this class already provides is more than enough here.
    private val lowBatteryAlertState = CameraAlertState()
```

- [ ] **Step 4: Bind the view and wire the long-click listener**

In `onCreate()`, immediately after this existing line (around line 450):

```kotlin
        powerWarningBanner = findViewById(R.id.powerWarningBanner)
```

add:

```kotlin
        lowBatteryOverlay = findViewById(R.id.lowBatteryOverlay)
        // Design-time XML string is a preview only; the tested copy wins at runtime, same
        // reasoning as faultOverlay.text a few lines above this in the diff.
        lowBatteryOverlay.text = AlertMessages.LOW_BATTERY_SCREEN_TEXT
```

Add the field declaration alongside the other `lateinit var` view fields. Find this existing line (near the top of the class, alongside `faultOverlay`'s own declaration — search for `private lateinit var faultOverlay: TextView`) and add immediately after it:

```kotlin
    private lateinit var lowBatteryOverlay: TextView
```

Then, in `onCreate()`, immediately after this existing line (around line 480):

```kotlin
        faultOverlay.setOnLongClickListener(revealGear)
```

add:

```kotlin
        lowBatteryOverlay.setOnLongClickListener(revealGear)
```

- [ ] **Step 5: Add the battery-reading helper and the effect handler**

Add these two new functions to `MainActivity.kt`, near the existing `applyPowerAlertEffects`/`isOnExternalPower` functions (same file, any location outside another function body — for example immediately after `isOnExternalPower()`'s closing brace):

```kotlin
    /**
     * Applies the low-battery lockout's decisions. Main thread only. Mirrors
     * applyAlertEffects for the camera fault screen: locking pins currentState back to
     * IDLE via resetToIdle() so an interrupted countdown/review never keeps running behind
     * the overlay, and the overlay itself swallows taps (see the layout's clickable="true").
     */
    private fun applyLowBatteryAlertEffects(effects: List<AlertEffect>) {
        for (effect in effects) {
            when (effect) {
                AlertEffect.SHOW_FAULT_SCREEN -> {
                    resetToIdle()
                    lowBatteryOverlay.visibility = View.VISIBLE
                }
                AlertEffect.HIDE_FAULT_SCREEN -> lowBatteryOverlay.visibility = View.GONE
                AlertEffect.SEND_DISCONNECT_ALERT ->
                    discordNotifier.send(AlertMessages.lowBatteryLock(isDemoModeEnabled()), mentionEveryone = true)
                AlertEffect.SEND_ALL_CLEAR ->
                    discordNotifier.send(AlertMessages.lowBatteryUnlock(isDemoModeEnabled()), mentionEveryone = false)
            }
        }
    }

    /**
     * The tablet's own battery percentage (0-100), or -1 if the platform can't report one.
     * LowBatteryPolicy.shouldBeLocked treats -1 as "no new information" (see its own
     * doc comment) rather than a real reading, so callers can pass this straight through.
     */
    private fun readTabletBatteryPercent(): Int {
        return runCatching {
            (getSystemService(BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)
    }
```

- [ ] **Step 6: Wire the heartbeat**

Replace this existing block inside `heartbeatRunnable` (around lines 337-346):

```kotlin
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
```

with:

```kotlin
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

            // Deliberately applies in demo mode too: demo mode is what runs the battery
            // drain test, and the tablet's own battery can and does deplete during it when
            // the external pack can't keep up. Unlike CameraSuspendPolicy, there is no
            // demo-mode exemption here.
            //
            // readTabletBatteryPercent() can return -1 (platform can't report a level);
            // LowBatteryPolicy.shouldBeLocked handles that by returning currentlyLocked
            // unchanged, so calling onLost()/onBack() from that unchanged value is always
            // safe here — both are no-ops when the phase wouldn't actually change.
            val currentlyLocked = lowBatteryAlertState.phase != CameraAlertState.Phase.HEALTHY
            if (LowBatteryPolicy.shouldBeLocked(currentlyLocked, currentState, readTabletBatteryPercent())) {
                applyLowBatteryAlertEffects(lowBatteryAlertState.onLost(SystemClock.elapsedRealtime()))
            } else {
                applyLowBatteryAlertEffects(lowBatteryAlertState.onBack(SystemClock.elapsedRealtime()))
            }
            applyLowBatteryAlertEffects(lowBatteryAlertState.onTick(SystemClock.elapsedRealtime()))

            applyBrightness()
            mainHandler.postDelayed(this, 5000)
```

- [ ] **Step 7: Never dim the low-battery screen either**

Replace this existing line inside `applyBrightness()` (around line 548):

```kotlin
            isIdle = currentState == BoothState.IDLE && faultOverlay.visibility != View.VISIBLE,
```

with:

```kotlin
            isIdle = currentState == BoothState.IDLE &&
                faultOverlay.visibility != View.VISIBLE &&
                lowBatteryOverlay.visibility != View.VISIBLE,
```

- [ ] **Step 8: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, all tests (including the 10 new `LowBatteryPolicyTest` cases and 5 new `AlertMessagesTest` cases from Tasks 1-2) — no regressions.

- [ ] **Step 9: Build and verify on the tablet**

Run: `./gradlew :app:installDebug`

Since draining a real device to 20% takes hours (confirmed this session: roughly 1,100+ photos / ~5 hours from a full charge under continuous capture), do not run a real drain for this check — instead verify the wiring directly:

1. **Boundary behavior via ADB, no waiting:** with the booth idle and the tablet on its own battery, use `adb shell dumpsys battery` to confirm the current reported level, then (temporarily, for this check only) run `adb shell dumpsys battery set level 20` to force the reported level to 20% without actually draining anything. **Expect, within 5 seconds** (the next heartbeat tick): the screen goes dark with the low-battery text, taps do nothing, and — after the existing 30s debounce — one `@everyone` Discord message.
2. **Recovery:** `adb shell dumpsys battery set level 55`. **Expect, within 5 seconds:** the overlay clears and a Discord all-clear (no mention) arrives.
3. **Boundary precision:** repeat step 1 with `set level 21` instead of `20`. **Expect:** nothing happens — no overlay, no alert.
4. **Never interrupts a capture:** start a countdown (tap the idle screen), then immediately `adb shell dumpsys battery set level 10` while the countdown is running. **Expect:** the countdown, capture, and review complete normally; only once the booth returns to idle does the low-battery screen appear.
5. **Admin gear still reachable:** with the low-battery screen up, long-press it. **Expect:** the gear appears and is tappable, exactly as it already is behind the camera fault screen.
6. **Demo mode is NOT exempt:** enable demo mode, then `adb shell dumpsys battery set level 15`. **Expect:** the low-battery screen still appears (unlike camera idle-suspend, which explicitly never fires in demo mode) — the Discord alert should carry the `[TEST]` prefix.
7. **Restore real battery reporting when done:** `adb shell dumpsys battery reset` — this is important; without it, the tablet will keep reporting the forced level indefinitely instead of its real one.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml app/src/main/java/com/ryanpudd/photobooth/MainActivity.kt
git commit -m "feat: lock the booth and alert Discord when the tablet's own battery hits 20%

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AGz3hsSLyn8QH3SV5R73V3"
```

---
