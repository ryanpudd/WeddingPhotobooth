# Inverted mount: on-device validation

The "Booth mounted upside down" toggle (branch `feat/inverted-mount`, plan
`docs/superpowers/plans/2026-10-31-inverted-mount.md`) is built, unit-tested and
reviewed, but has not been run on the Nexus 7 yet. Work through this before
merging.

The debug build of `51d28d3` is already installed on the tablet
(`com.ryanpudd.photobooth.debug`). If anything has changed since, reinstall on the
tablet only: `./gradlew :app:installDebug` installs on *every* connected device,
so use `adb -s <tablet-serial> install -r app/build/outputs/apk/debug/app-debug.apk`.

## Before you start

- [ ] Tablet charged enough to run with the webcam attached
- [ ] Webcam plugged into the OTG port and the live preview showing
- [ ] Logcat running so a restart or reconnect is visible:
      `adb -s <tablet-serial> logcat -s MainActivity:* USBMonitor:* UVCCamera:*`

## Checks

- [ ] **Live toggle:** tick "Booth mounted upside down" and tap Save. The screen
      flips immediately; logcat shows no new `onCreate` and no camera
      disconnect/reconnect.
- [ ] **Preview orientation:** turn the whole rig upside down. The preview is
      upright, and it is mirrored like a selfie (raise your right hand and it
      appears on the right of the screen).
- [ ] **Saved photo:** take a photo and Keep it. The review image is upright. Pull
      the newest file from the pending dir and check it is upright and mirrored
      the same way as the review image.
- [ ] **Cancel:** with the toggle on, open settings, untick it, and press Cancel.
      Nothing changes.
- [ ] **Incomplete S3 settings:** with the S3 fields blank, change the toggle and
      tap Save. You still get the "incomplete" toast, and the flip is still
      applied and saved.
- [ ] **Cold start:** with the toggle on, reboot (or unplug and replug the webcam
      to trigger the USB-attach launch). The booth comes up inverted with no admin
      action. (A brief flash the normal way round on startup is expected.)
- [ ] **Back to normal:** untick and Save. Everything behaves as before, and a
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

- [ ] The "secret key wrong length… Nothing saved" toast is now misleading: the
      toggles save and the screen flips anyway.
- [ ] Add the toggle to the "Send diagnostics" report.
- [ ] Add a test that records why the inverted transform is a vertical flip (a 180°
      turn combined with the selfie mirror).

## When it all passes

Finish the branch (merge `feat/inverted-mount` into `main`), and delete the
git-ignored `.superpowers/sdd/2026-10-31-inverted-mount/` workspace.
