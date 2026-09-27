# Camera Idle Suspend — Design

**Date:** 2026-09-26
**Status:** Confirmed (design walkthrough, 2026-09-26)

## Problem / motivation

The battery-drain-test runbook flags camera power draw during idle periods as
the most promising lever for extending pack runtime, but gates it behind a
**key design constraint** carried over from the 2026-09-20 booth-resilience
spec: *"A camera cooldown cannot ship before camera-health detection. If the
app switches the camera off deliberately and it fails to wake, `onDettach`
never fires, so the booth sits looking healthy while being dead and nothing
alerts anyone."* That constraint is now satisfied — heartbeat-driven camera
fault recovery shipped in `9936cf4`.

Separately, an investigation into forcing ACA (Accessory Charger Adapter)
detection — to allow charging the pack while the camera stayed attached and
powered — was tested directly on hardware. It reproduced a known-unstable
kernel driver failure matching a 2013 bug report (Don Bowman) on the same
driver: enabling ACA detection did classify the cable correctly for the first
time, but within seconds of real host-mode activity (capture taps) the
driver's own periodic re-check decided the ID/GND pins were "no more" and tore
down the entire USB host session. It self-recovered at the USB level, but the
app's camera session did not reopen cleanly on its own — the Activity was
killed and relaunched, and even the fresh instance didn't get a preview,
needing a manual force-stop + relaunch. **Conclusion: don't re-enable that
flag.** `usbhost_charge_mode=Y` alone (no ACA) remains the stable baseline —
real charging, no camera drops.

This reprioritizes **camera-off-when-idle** as the most promising remaining
lever: it sidesteps the kernel instability entirely rather than fighting it.

## Goals

1. Reduce idle-period camera power draw, extending battery-pack runtime.
2. Never leave the booth in a state where the camera is down and nobody
   knows — reuse the existing fault-detection path rather than inventing a
   new one.
3. Never make a guest wait on a dead countdown: a wake-tap while the camera
   is still resuming must not start the capture countdown before a frame is
   available.

## Design

**Section 1 — Mechanism.** Not a host-mode teardown (that's the fragile path
just ruled out above). Instead, the app releases its own hold on the camera
via the `close()` / `stopPreview()` calls that already exist in
`takePhoto()`'s error paths, and lets the kernel's **standard USB
autosuspend** (already configured: `control=auto`,
`autosuspend_delay_ms=2000`, confirmed present on the BRIO) cut power a couple
of seconds later — entirely through the normal Linux USB runtime-PM path,
never touching the ID-pin/ACA state machine. To wake it, the app reopens the
camera the same way it already does elsewhere (the `onConnect` device-attach
path) — the kernel resumes the device transparently on the next I/O.

**Section 2 — Trigger conditions.** Reuses signals that already exist rather
than adding new tracking:

- Suspend when `currentState == IDLE` (never mid-capture/review — the camera
  must never go down while a guest is using it) **and**
  `msSinceLastTouch >= CAMERA_SUSPEND_AFTER_MS`, defaulting to **3 minutes**
  (tunable). This is the same `lastTouchElapsedMs` clock `BrightnessPolicy`
  already tracks, driven from the same 5s `heartbeatRunnable` tick that
  already runs continuously — no new timer, no new thread.
- Never suspends in demo mode, matching the existing pattern: demo mode is
  the worst-case stress test and pins brightness for the same reason — it
  should keep the camera live throughout, not exercise the suspend path.
- No admin dialog open — the same guard `demoCaptureRunnable` already uses.
- A guest's touch is the resume trigger — the same `dispatchTouchEvent` hook
  that already wakes the screen.

**Section 3 — Resume, health-check, and failure handling.** Reuses an
existing mechanism almost exactly as-is: `startupCameraCheckRunnable`
already does a one-shot "did the camera come back?" check after a cold
start, and on failure feeds `cameraAlertState.onLost()` — the same
fault-screen + debounced-Discord-alert path used for a real physical
disconnect.

For wake-from-suspend, apply the identical pattern: on the resume-triggering
touch, reopen the camera, then schedule a one-shot check after a grace
period. If `isPreviewing` is still false when it fires, treat it exactly
like a camera fault — same alert, same fault screen, no special-casing. If
it does come back, clear silently, no alert (matching the existing "cold
start, camera was already fine" no-op case).

Open question, deliberately left for implementation: the grace period. Cold
start uses 15s, which is conservative because it includes full USB
enumeration; resuming from a lightweight suspend should be much faster, but
there's no measured number yet. Start conservative (reuse 15s) and tighten
it once a real measurement exists during implementation, rather than
blocking the design on it.

**Section 4 — What the guest sees during the resume window.** A guest's tap
wakes the screen and, currently, immediately starts the 3-second
pre-capture countdown. If the camera is still resuming, starting the
countdown right away risks it finishing before a frame is available — the
same failure `takePhoto()`'s 3-second timeout already catches, so nothing
crashes, but the guest gets a "capture timed out" retry instead of a photo
on their first tap, which feels broken even though it self-recovers.

**Section 5 — Testable structure and the small new state needed.** Two
things round out the design:

- A new `pendingCaptureAfterResume` flag: when a wake-tap arrives while the
  camera is suspended, it resumes the camera and sets this flag instead of
  immediately starting the countdown. Wherever the code already detects
  "camera is previewing again" (the existing `onConnect` confirm block, and
  the heartbeat's safety-net check), if this flag is set, it starts the
  countdown *there* instead — reusing existing detection points rather than
  adding a new one. If the resume health-check fails and a fault is
  declared, the flag is simply cleared instead.
- The suspend-trigger decision (`currentState == IDLE`, idle long enough,
  not demo mode, no admin dialog open) is pure logic with no Android
  dependencies, so it should be extracted into a `CameraSuspendPolicy`
  object — the same pattern `BrightnessPolicy` already establishes:
  unit-testable on its own, same as everything else pure in this codebase.
  The actual `close()` / reopen calls stay as `MainActivity` glue, consistent
  with how `BrightnessPolicy` computes and `MainActivity` applies.

## Out of scope

| Excluded | Why |
|---|---|
| Host-mode teardown / forcing ACA detection | Reproduced a known-unstable kernel driver failure on this hardware; ruled out directly by test |
| A new suspend-specific timer or touch tracker | `BrightnessPolicy`'s existing idle clock and the 5s heartbeat already cover this |
| Exercising camera suspend during demo mode | Demo mode exists to stress-test the worst case continuously; suspending would undermine that |
| Precisely measuring the resume grace period before finalizing this design | Not decision-relevant to the design; measure during implementation and tune from the conservative 15s default |

## Key design constraint (carried forward, now satisfied)

The 2026-09-20 spec's constraint — *a camera cooldown cannot ship before
camera-health detection exists* — is satisfied by the heartbeat-driven fault
recovery already shipped (`9936cf4`). This design reuses that exact recovery
path for suspend/resume failures rather than introducing a parallel one, so
the constraint stays satisfied going forward rather than being satisfied
only at the moment of writing.
