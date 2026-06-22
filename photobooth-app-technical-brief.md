# Wedding Photobooth App — Technical Brief

## Purpose of this document

This briefs an AI coding assistant (or a human developer) on building a custom Android
photobooth application to replace **SLR Studio** on a specific, fixed piece of hardware.
Every decision below was made deliberately, with reasoning — read the "why" notes, not
just the "what," before changing anything. This is a one-shot deployment for a wedding
with a fixed, immovable date. Reliability and simplicity are valued over feature
completeness or elegance.

---

## 1. Hardware & platform context (why this project exists)

- **Device:** Nexus 7 (2013), codename `flo`/`deb`. 32-bit ARMv7 (Krait CPU), no arm64.
- **OS:** LineageOS 20 (Android 13 / API 33), unofficial community build.
- **Camera input:** external USB webcam via OTG, **not** the tablet's built-in camera.
- **Root cause this project exists at all:** Android's Camera2 API is broken at the
  platform/HAL level on this specific ROM — confirmed system-wide (crashes in both the
  built-in camera and any Camera2-based USB capture, e.g. `Open Camera` with Camera2
  API enabled crashes immediately; SLR Studio's WebView/Chromium-based capture pipeline
  hits a `BadParcelableException` in `ICameraDeviceUser.createStream`). This is a
  longstanding, unresolved issue across multiple LineageOS branches/years for this
  device, not a quick fix. **Do not attempt to debug or work around Camera2 — this
  app must avoid it entirely.**
- **The fix:** bypass Camera2 completely. Talk to the USB webcam directly via Android's
  USB Host API using the UVC (USB Video Class) protocol, with no dependency on the
  broken camera framework layer at all.
- **Physical constraint:** the tablet has exactly **one USB OTG port**, already
  committed to the webcam. Any feature requiring a second USB device (printer, wired
  button) needs a powered hub and is treated as added risk — avoided in v1.

---

## 2. Foundation library

**Use `saki4510t/UVCCamera`, built from source, not a third-party prebuilt AAR
(e.g. JitPack wrappers like `jiangdongguo/AndroidUSBCamera`).**

Why: prebuilt artifacts increasingly ship `arm64-v8a`/`x86_64` only, since almost no
current device is 32-bit ARM. This device *is* 32-bit ARM (`armeabi-v7a`) only. A
prebuilt artifact that's silently dropped that ABI installs fine and fails (or
crashes) the first time native camera code is touched — a bad failure mode to
discover late. Building from source guarantees control over this.

- Native build system: **`ndk-build` (Android.mk/Application.mk)** — this is how the
  library's JNI modules (libusb, libuvc, libjpeg-turbo) are structured. Do not port to
  CMake; no functional benefit, real effort.
- `armeabi-v7a` is still a fully supported, non-deprecated NDK target as of current
  NDK releases — no need to source an old/historical NDK version. Use whatever ships
  with current stable Android Studio.
- Explicitly set `abiFilters 'armeabi-v7a'` in `defaultConfig` as a second guarantee
  against an accidental arm64-only build.
- Pin the exact NDK version via `android.ndkVersion` in `build.gradle` for build
  reproducibility (not for compatibility reasons — just so the build doesn't silently
  shift under us).

---

## 3. SDK levels (hard platform constraint — do not change)

| | Value | Why |
|---|---|---|
| `minSdkVersion` | **27** | Pinned equal to target; this app only ever runs on one device, so no reason to support a range. |
| `targetSdkVersion` | **27** | **Hard ceiling, not a preference.** Android 9+ requires `CAMERA` permission for USB webcam access; Android 10+ additionally refuses to grant USB permission for UVC devices to apps targeting API 28+. Raising this value breaks USB camera access entirely. |
| `compileSdkVersion` | **33** | Matches the device's actual Android 13 base. Safe to set higher than `targetSdk` — `compileSdk` only affects which API stubs/tooling we compile against, not runtime behavior gating (that's `targetSdk`'s job). |

**Manifest implication:** declare and request `CAMERA` permission even though Camera2
is never used — it's required for UVC access as a platform quirk on API 9+.

---

## 4. Language & architecture

- **Kotlin.** The library is plain Java but interops cleanly; Kotlin is the current
  Android-idiomatic default and what most current tooling/AI assistance defaults to.
- **Single Activity, plain View-based UI — no Jetpack Compose, no DI framework.**
  - No Compose specifically *for the camera surface*: the library expects a raw
    `TextureView`/`SurfaceView` with direct lifecycle callbacks
    (`onSurfaceCreated`/`onSurfaceDestroy`) wired straight to the camera connection.
    Wrapping that in Compose's `AndroidView` interop adds a second lifecycle to
    reconcile against a library that's already sensitive to exact timing.
  - One Activity, not several: opening the UVC connection has real, sometimes-flaky
    cost. **Open the camera once at app launch and hold the connection alive all
    night.** Move between internal UI states, never recreate the Activity or
    reconnect the camera per-guest.
  - No DI/multi-module structure — single-purpose kiosk app, deliberately minimal
    surface area for something to break unexpectedly on the day.
- **UI states (state machine within the single Activity):**
  `IDLE → COUNTDOWN_PRECAPTURE → REVIEW → (back to IDLE)`

---

## 5. Capture pipeline

- **Format negotiation:** query the connected webcam's supported formats at startup.
  **Prefer MJPEG. Fall back to YUYV only if MJPEG isn't offered.** Don't hardcode an
  assumption — check at runtime.
- **The MJPEG passthrough trick:** an MJPEG frame *is* already a complete JPEG. On
  capture, grab the raw bytes of the next incoming frame and write them directly to
  file — no decode/re-encode pass. Higher quality (no second lossy JPEG generation)
  and cheaper on this CPU. *(If the device falls back to YUYV, a real
  decode→Bitmap→`compress()` step is unavoidable — flag this during webcam testing.)*
- **Resolution: fixed at 1280×720 for both live preview and the saved photo.** No
  resolution switch between preview and still-capture modes — renegotiating USB
  streaming parameters mid-session is a known source of UVC flakiness (some webcam
  firmware hangs on stream stop/restart). 720p was chosen over the camera's native
  1080p (if offered) to keep comfortable real-time software JPEG decode headroom
  (libjpeg-turbo, software-decoded) on this dual-core Krait CPU for the live preview.
- **Rotation: EXIF orientation tag only — no pixel decode/rotate/re-encode.**
  A single config constant, set once during physical setup, not exposed to guests:

  ```
  PHOTO_ROTATION_DEGREES = 0   // default; set after testing physical webcam mount
  ```

  | `PHOTO_ROTATION_DEGREES` | EXIF `TAG_ORIENTATION` value |
  |---|---|
  | 0 | 1 (Normal) |
  | 90 | 6 (Rotate 90° CW) |
  | 180 | 3 (Rotate 180°) |
  | 270 | 8 (Rotate 270° CW) |

  Apply via `ExifInterface.setAttribute(TAG_ORIENTATION, ...)` +
  `saveAttributes()` on the already-written file — patches metadata only, the raw
  JPEG passthrough bytes are never touched. The same constant also drives a display
  transform on the live preview `View` so preview and saved-photo orientation always
  agree. **Verify CW vs CCW against the real physical mount during setup — easy to
  get backwards on paper.**
  - *Note: this approach was deliberately chosen over real pixel rotation, and over
    in-app template/frame compositing, to keep the capture pipeline maximally simple
    and fast for v1. See Roadmap (§8) — a future template feature will require
    revisiting this and switching to real pixel rotation, since compositing requires
    decoding to pixels anyway.*

---

## 6. UI flow detail

1. **Idle/attract screen.** Branding (names/date), "Tap to take your photo!" — pure
   UI, no interaction with the capture pipeline.
2. **Tap to start →** pre-capture countdown, **3 seconds**, full-screen, dramatic
   ("3...2...1...Smile!"). Standard photobooth convention.
3. **Capture** — grab next MJPEG frame's raw bytes as described in §5.
4. **Review screen** — shows the captured photo with **Keep** / **Retake** buttons,
   **plus a visible 8–10 second auto-advance countdown** ("Saving in 5...4...3...").
   - Tapping **Keep** (or anywhere) short-circuits immediately to idle.
   - Tapping **Retake** resets the timer and returns to step 2.
   - **If neither is tapped, the timer expires and the photo auto-saves**, returning
     to idle automatically.
   - *Why:* a guest can walk away mid-review. Given the explicit requirement that
     this booth must run completely unattended with zero manual intervention all
     night, the booth must be structurally incapable of hanging on human indecision.
     An unwatched photo auto-saving is a harmless failure mode; a frozen booth for
     the rest of the night is not.
5. **Orientation:** landscape, full-frame 16:9 preview matching the webcam's native
   output — no cropping/letterboxing logic needed in v1.

---

## 7. Storage & file management

Because `targetSdkVersion = 27`, Scoped Storage (enforced for apps targeting API 29+)
**does not apply** — legacy full external storage access via
`WRITE_EXTERNAL_STORAGE` runtime permission, no `MediaStore`/SAF ceremony needed.

- **Location:** `Pictures/WeddingPhotobooth/`
- **Filename:** `IMG_yyyyMMdd_HHmmss_NNN.jpg` (timestamp + zero-padded counter as a
  collision guard)
- **Write safety:** write to a temp file, then atomically rename to the final filename
  only once the write fully succeeds. (This stack has already demonstrated it can
  crash unpredictably — don't leave a corrupt half-written JPEG under a "valid"
  filename if that happens mid-write.)
- **Low-storage guard:** check free space before each capture; show a clear on-screen
  message rather than a silent crash if space runs low.

---

## 8. Roadmap / version scope

| Version | Scope |
|---|---|
| **v1 (wedding night)** | Everything above. Local save only. On-screen touch trigger. 720p MJPEG passthrough. EXIF-only rotation. Full Device Owner kiosk lockdown. Auto-advancing review. |
| **v2** | Sharing — QR code linking to a download, or cloud upload guests can access after the fact. Decoupled from the live capture loop; can be built/tested independently, even after the wedding. |
| **v3** | Bluetooth shutter remote as an alternate capture trigger (generic BT-HID remote sending a keycode, e.g. volume-up) — additive, doesn't replace the touch button. |
| **v4** | Template/frame overlay composited onto the saved photo. **Reopens §5's rotation approach** — compositing requires decoding to real pixels anyway, so this version should switch from EXIF-only rotation to genuine pixel rotation (decode → rotate → composite template → re-encode), all still hidden inside the existing review-screen countdown window. |
| **Not on the roadmap** | Photo printing (was considered and explicitly deferred — would require a powered USB hub sharing the one OTG port with the webcam, plus printer SDK integration; treated as separate risk not worth taking for v1). |

---

## 9. Permissions & manifest requirements

- `CAMERA` — required for UVC access despite Camera2 never being used (§3).
- `WRITE_EXTERNAL_STORAGE` — legacy storage write (§7).
- **`USB_DEVICE_ATTACHED` intent-filter + `device_filter.xml`** (matching the
  webcam's vendor/product ID — *fill in once the specific webcam model is
  confirmed*). This is the standard mechanism for unattended USB kiosk apps: when a
  matching device attaches, Android auto-launches the app **and** auto-grants USB
  permission with no popup — essential since there's no human present to tap
  "Allow."
- **`BOOT_COMPLETED` receiver** — re-launches the app if the tablet is power-cycled
  (manual power-on by a person is an accepted recovery step; the app does not need
  to survive an unattended reboot on its own — confirmed acceptable for this
  deployment).
- **Runtime USB detach/reattach listener** — shows a clear "camera disconnected"
  on-screen message and recovers automatically if a cable is bumped mid-reception,
  rather than crashing.

---

## 10. Kiosk lockdown & crash recovery

**Full Device Owner enrollment, not standard Screen Pinning.**

This was a deliberate escalation from the initial recommendation, driven by an
explicit requirement: the person running this booth must have **zero possibility**
of being pulled away from their own wedding to fix it, and the tablet has no life
after this event (reflashing afterward is fine).

- Enroll via `adb shell dpm set-device-owner` — **must be done during setup/testing,
  before the final night**, and requires no accounts signed in on the device.
- Full lockdown: Home, Recents, notification shade, and status bar all disabled.
  **No escape gesture at all** — unlike Screen Pinning, there's no "official" way
  out for anyone, including curious guests.
- **Crash recovery: self-rescheduling `AlarmManager` heartbeat (~15s interval),
  independent of the app's own process.** Native camera code can crash in ways a
  Java try/catch can never observe (segfault kills the process outright) — the only
  mechanism that survives that category of failure lives outside the app process
  entirely. The heartbeat doesn't need to know *why* the app died (Java exception,
  native crash, OOM kill) — it just always tries to bring the main Activity back to
  the foreground.

---

## 11. Build flavors & test strategy

- **`debug` build flavor:** skips Device Owner enrollment entirely — iterating
  against a fully locked-down kiosk build during development would be miserable
  (fighting your own lockdown every test cycle).
- **`release` build flavor:** full Device Owner lockdown, enrolled only on the final
  build, the night before the wedding.
- **Development/deployment over wireless ADB only** — already proven to work on
  this device. The physical OTG port stays dedicated to the webcam throughout
  development; never needs to be shared with a USB debugging cable.
- **No mock camera needed** — a second/spare UVC webcam is available for
  development, so all testing happens against real hardware throughout.
- **Required before the wedding: a full "dress rehearsal" soak test** — the actual
  release build, real physical mount, real lighting, run unattended for several
  hours. This is the only way to catch failure modes that only surface over time
  (thermal throttling, USB dropout, whether the AlarmManager watchdog actually
  recovers cleanly in practice). Schedule this deliberately in advance, not as a
  last-minute check.

---

## 12. Open items to resolve during build (not yet decided / hardware-dependent)

- Confirm the specific webcam's actual supported UVC formats (MJPEG availability,
  native resolutions) — §5 assumes MJPEG-first with a YUYV fallback path that may
  never actually get exercised, but the format query must still be implemented.
- Fill in the webcam's USB vendor/product ID into `device_filter.xml` once
  confirmed.
- Determine `PHOTO_ROTATION_DEGREES` and verify CW/CCW direction against the actual
  physical mount, once the webcam orientation is finalized on-site.
- Pin the exact NDK version once the project is initialized (reproducibility only,
  not a compatibility requirement — see §2).
