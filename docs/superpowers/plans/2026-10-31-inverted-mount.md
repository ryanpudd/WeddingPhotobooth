# Inverted Mount Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an admin-settings toggle that lets the booth run as an **Inverted mount** (see `GLOSSARY.md`), with the tablet and webcam turned 180° together, and still show the screen, live preview and saved photos the right way up.

**Architecture:** A pure `InvertedMountPolicy` object maps the saved boolean to (a) the Activity's requested screen orientation and (b) one mirror/flip transform. That same transform drives both the preview `View`'s scale and the capture `Matrix`, so the preview and the saved photo can't disagree. An `InvertedMountStore` saves the boolean the same way `DemoModeStore` does. `MainActivity` applies the policy in `onCreate` and again when admin settings are saved.

**Tech Stack:** Kotlin, Android Views, saki4510t UVCCamera, JUnit 4 (plain JVM unit tests).

**Spec:** No separate spec. The decisions below were agreed in a grilling session on 2026-10-31 and are the spec.

### Decisions (the spec)

1. Manual toggle only. No sensor or automatic detection.
2. A checkbox in the PIN-protected admin settings dialog, labelled **"Booth mounted upside down"**, saved in plain SharedPreferences so it survives reboots and the auto-launch on USB attach.
3. On/off only (normal landscape vs a 180° flip). No 90/270 or portrait.
4. Takes effect as soon as settings are saved, with no app restart. A single camera reconnect at that moment is acceptable if the platform forces one.
5. Saved photos come out upright, with the existing selfie mirror kept. This is done by changing pixels in the capture `Matrix`, not with an EXIF tag. `PHOTO_ROTATION_DEGREES` stays at `0` and is left alone.

## Global Constraints

- `minSdk`/`targetSdk` stay at **27**. Do not change them (UVC access breaks at 28+).
- Never recreate the Activity or deliberately reopen the camera to apply the toggle. The camera connection is held all night.
- Do not touch Camera2 or the capture pipeline beyond the one `Matrix` change.
- Normal (non-inverted) behaviour must stay exactly as it is today: preview `scaleX = -1f, scaleY = 1f`, capture `preScale(-1f, 1f)`, orientation `landscape`.

## Review Focus

1. **Saving settings with incomplete S3 credentials:** the Save handler returns early when credentials are incomplete or invalid (`return@setPositiveButton`). The inverted-mount toggle must still be saved and applied, just as the demo-mode toggle is (see the M6 comment). Owned by Task 2, step 4.
2. **Cold boot or USB-attach auto-launch with the toggle already on:** the booth must come up inverted without anyone opening settings. The manifest still says `landscape`, so `onCreate` must apply the saved orientation. Owned by Task 2, steps 2 and 6.
3. **Toggling on a live camera:** flipping the screen 180° must not restart the Activity or drop the UVC connection. Owned by Task 2, step 6, with the manifest fallback in step 7.
4. **A capture that overlaps a toggle change:** the capture thread must use the transform read on the main thread when the capture started, not re-read the store from the worker thread. Owned by Task 2, step 3.
5. **Cancel in the admin dialog:** Cancel must leave both the saved value and the screen unchanged. Owned by Task 2, step 6.

---

### Task 1: Inverted mount policy and store

**Files:**
- Create: `app/src/main/java/com/ryanpudd/photobooth/InvertedMountPolicy.kt`
- Create: `app/src/main/java/com/ryanpudd/photobooth/InvertedMountStore.kt`
- Test: `app/src/test/java/com/ryanpudd/photobooth/InvertedMountPolicyTest.kt`

**Interfaces:**
- Produces:
  - `object InvertedMountPolicy`
    - `data class MirrorTransform(val scaleX: Float, val scaleY: Float)`
    - `fun screenOrientation(inverted: Boolean): Int`: returns `ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE` (0) or `ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE` (8)
    - `fun mirrorTransform(inverted: Boolean): MirrorTransform`: returns `(-1f, 1f)` normally and `(1f, -1f)` when inverted. Why: a 180° camera rotation is `(-1, -1)`, and combining it with the selfie mirror `(-1, 1)` gives a pure vertical flip.
  - `object InvertedMountStore`
    - `fun isEnabled(context: Context): Boolean` (default `false`)
    - `fun setEnabled(context: Context, enabled: Boolean)`

- [ ] **Step 1: Write the failing tests** in `InvertedMountPolicyTest`, in the style of `BrightnessPolicyTest`:

```kotlin
@Test fun normalMount_isLandscape() =
    assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, InvertedMountPolicy.screenOrientation(inverted = false))

@Test fun invertedMount_isReverseLandscape() =
    assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, InvertedMountPolicy.screenOrientation(inverted = true))

@Test fun normalMount_mirrorsHorizontallyOnly() =
    assertEquals(InvertedMountPolicy.MirrorTransform(-1f, 1f), InvertedMountPolicy.mirrorTransform(inverted = false))

@Test fun invertedMount_flipsVerticallyOnly() =
    assertEquals(InvertedMountPolicy.MirrorTransform(1f, -1f), InvertedMountPolicy.mirrorTransform(inverted = true))
```

(`ActivityInfo` constants are compile-time `int`s, so they inline and are safe in plain JVM tests.)

- [ ] **Step 2: Run the tests and check they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ryanpudd.photobooth.InvertedMountPolicyTest"`
Expected: compilation FAIL, `Unresolved reference: InvertedMountPolicy`

- [ ] **Step 3: Implement `InvertedMountPolicy`** with the signatures above. Give it a KDoc that names the Inverted mount and explains why the inverted transform is `(1, -1)`.

- [ ] **Step 4: Implement `InvertedMountStore`** as a copy of the `DemoModeStore` pattern, with prefs name `"inverted_mount_prefs"` and key `"inverted_mount_enabled"`. Its KDoc should say it is plain SharedPreferences because it is a switch, not a secret, and that it persists across restarts by design. No unit test, matching `DemoModeStore`.

- [ ] **Step 5: Run the tests and check they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all tests PASS, including the 4 new ones.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/ryanpudd/photobooth/InvertedMountPolicy.kt app/src/main/java/com/ryanpudd/photobooth/InvertedMountStore.kt app/src/test/java/com/ryanpudd/photobooth/InvertedMountPolicyTest.kt
git commit -m "feat: add inverted mount policy and store"
```

---

### Task 2: Wire the toggle into MainActivity and admin settings, then verify on the device

**Files:**
- Modify: `app/src/main/java/com/ryanpudd/photobooth/MainActivity.kt` (`onCreate` ~444-478, `takePhoto` ~732-750, `showAdminSettingsDialog` ~911-985)
- Modify: `app/src/main/res/layout/dialog_admin_settings.xml:103-108` (add a checkbox after `checkDemoMode`)
- Modify: `app/src/main/res/values/strings.xml:39` (add a string after `label_demo_mode`)
- Possibly modify: `app/src/main/AndroidManifest.xml:29` (only if step 7's fallback is needed)

**Interfaces:**
- Consumes: `InvertedMountPolicy.screenOrientation`, `InvertedMountPolicy.mirrorTransform`, `InvertedMountStore.isEnabled/setEnabled` from Task 1.
- Produces: `private fun applyInvertedMount()` in `MainActivity`. It is main-thread only. It calls `requestedOrientation = InvertedMountPolicy.screenOrientation(inverted)` and sets `cameraView.scaleX/scaleY` from `mirrorTransform(inverted)`.

- [ ] **Step 1: Add the string and checkbox.** Add `<string name="label_inverted_mount">Booth mounted upside down</string>`. Add `<CheckBox android:id="@+id/checkInvertedMount" …>` directly below `checkDemoMode`, with the same attributes, `layout_marginTop="8dp"` and `android:text="@string/label_inverted_mount"`.

- [ ] **Step 2: Apply it at startup.** Add `applyInvertedMount()`. In `onCreate`, replace `cameraView.scaleX = -1f // Mirror the preview` (line 478) with a call to `applyInvertedMount()`. Keep a comment saying the transform includes the selfie mirror.

- [ ] **Step 3: Use the policy in the capture `Matrix`.** In `takePhoto`, read `val mirror = InvertedMountPolicy.mirrorTransform(InvertedMountStore.isEnabled(this))` on the main thread **before** creating `captureThread`. Inside the thread, replace `preScale(-1f, 1f)` with `preScale(mirror.scaleX, mirror.scaleY)`.

- [ ] **Step 4: Save and apply it from admin settings.** In `showAdminSettingsDialog`, bind `checkInvertedMount` and initialise it from `InvertedMountStore.isEnabled(this)`. In the positive-button handler, next to the `DemoModeStore.setEnabled` / `syncDemoBanner()` lines and **before** the credentials validation, call `InvertedMountStore.setEnabled(this, checkInvertedMount.isChecked)` and then `applyInvertedMount()`. Extend the M6 comment so it covers both toggles.

- [ ] **Step 5: Build and run the unit tests**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 6: Verify on the device (HITL, needs the Nexus 7 and webcam).** Install with `./gradlew :app:installDebug`, start the app, then run `adb logcat -s MainActivity:* USBMonitor:* UVCCamera:*` and check each of these:
  - Toggle **on** and Save. The screen flips to reverse landscape immediately, the preview is upright and mirrored like a selfie (raise your right hand and it appears on the right of the screen), and logcat shows **no** new `onCreate` and no camera disconnect/reconnect.
  - Take a photo and Keep it. The review image is upright. Pull the newest file from the pending dir and check it is upright and mirrored the same way as the review image.
  - Toggle **on**, then press Cancel. Nothing changes.
  - Toggle **on** with the S3 fields blank, then press Save. You still get the "incomplete" toast, and the flip is still applied and saved.
  - Leave it on and reboot (or unplug and replug the webcam to trigger the USB-attach launch). The booth comes up inverted with no admin action.
  - Toggle **off** and Save. Everything returns to today's behaviour, and a captured photo matches one taken before this change.

- [ ] **Step 7: Fallback, only if step 6 shows the Activity being recreated on toggle.** Add `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize"` to `MainActivity` in the manifest, then repeat step 6's first check. If the camera still reconnects but the Activity survives, accept it (decision 4) and note it in the commit message.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/ryanpudd/photobooth/MainActivity.kt app/src/main/res/layout/dialog_admin_settings.xml app/src/main/res/values/strings.xml GLOSSARY.md
git commit -m "feat: add an admin toggle for running the booth as an inverted mount"
```

(Add `app/src/main/AndroidManifest.xml` if step 7 was needed.)
