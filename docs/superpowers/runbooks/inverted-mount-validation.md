# Inverted mount: on-device validation

The "Booth mounted upside down" toggle (branch `feat/inverted-mount`, plan
`docs/superpowers/plans/2026-10-31-inverted-mount.md`) is built, unit-tested and
reviewed.

**Status: validated on the Nexus 7 and merged to `main`.** The combined build
(inverted mount plus the Halloween border) was tested on the tablet, and the
toggle worked and everything looked right. The individual checks below weren't
recorded one by one; the boxes are ticked on that overall result. No fallback was
needed, and the startup flicker wasn't noticeable. Re-run this list if the camera,
ROM or kiosk setup changes.

The debug build of `51d28d3` is already installed on the tablet
(`com.ryanpudd.photobooth.debug`). If anything has changed since, reinstall on the
tablet only: `./gradlew :app:installDebug` installs on *every* connected device,
so use `adb -s <tablet-serial> install -r app/build/outputs/apk/debug/app-debug.apk`.

## Before you start

- [x] Tablet charged enough to run with the webcam attached
- [x] Webcam plugged into the OTG port and the live preview showing
- [x] Logcat running so a restart or reconnect is visible:
      `adb -s <tablet-serial> logcat -s MainActivity:* USBMonitor:* UVCCamera:*`

## Checks

- [x] **Live toggle:** tick "Booth mounted upside down" and tap Save. The screen
      flips immediately; logcat shows no new `onCreate` and no camera
      disconnect/reconnect.
- [x] **Preview orientation:** turn the whole rig upside down. The preview is
      upright, and it is mirrored like a selfie (raise your right hand and it
      appears on the right of the screen).
- [x] **Saved photo:** take a photo and Keep it. The review image is upright. Pull
      the newest file from the pending dir and check it is upright and mirrored
      the same way as the review image.
- [x] **Cancel:** with the toggle on, open settings, untick it, and press Cancel.
      Nothing changes.
- [x] **Incomplete S3 settings:** with the S3 fields blank, change the toggle and
      tap Save. You still get the "incomplete" toast, and the flip is still
      applied and saved.
- [x] **Cold start:** with the toggle on, reboot (or unplug and replug the webcam
      to trigger the USB-attach launch). The booth comes up inverted with no admin
      action. (A brief flash the normal way round on startup is expected.)
- [x] **Back to normal:** untick and Save. Everything behaves as before, and a
      captured photo matches one taken before this change.

## If a check fails

- **The Activity restarts or the camera reconnects on Save:** add
  `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize"`
  to `MainActivity` in `AndroidManifest.xml` and repeat the live toggle check. A
  single camera reconnect at that moment is acceptable if the Activity itself
  survives.
- **The preview is upside down when inverted:** the webcam is probably flipping its
  own image. Change the inverted transform in `InvertedMountPolicy.mirrorTransform`
  from `(1f, -1f)` to `(-1f, 1f)` and update the matching test.

## Optional follow-ups the reviewer raised

- [x] The "secret key wrong length… Nothing saved" toast is now misleading: the
      toggles save and the screen flips anyway.
- [x] Add the toggle to the "Send diagnostics" report.
- [x] Add a test that records why the inverted transform is a vertical flip (a 180°
      turn combined with the selfie mirror).

## When it all passes

Finish the branch (merge `feat/inverted-mount` into `main`), and delete the
git-ignored `.superpowers/sdd/2026-10-31-inverted-mount/` workspace.
