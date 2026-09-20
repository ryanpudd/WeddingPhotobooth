# Pre-Wedding Verification Checklist

Everything on this branch that a machine could check has been checked: 54 unit tests pass,
`assembleDebug` succeeds, and `lintDebug` has no new errors. But **every unit test on this
branch covers a pure function.** Each item below is Android-bound — USB grants, the webhook,
the overlay, the brightness pin, the demo loop — and is verified by reasoning alone. None of
it has run on the tablet.

Work top-down. Items 1-3 gate the event; items 4-9 are the feature verification; item 10 is
the measurement that decides the deferred questions.

Install first: `./gradlew :app:installDebug`

---

## 1. USB permission persistence — the whole point of Task 1

Nothing in code can tell you whether the "use by default" box was ticked, and the fix is
worthless until it is.

1. Uninstall completely: `adb uninstall com.ryanpudd.photobooth.debug`
2. Install, then unplug and replug the webcam.
3. **Expect:** a dialog offering to open Photobooth, **with a checkbox reading "Use by
   default for this USB device"**. Tick it and confirm.
4. Unplug and replug **three more times**. **Expect:** no further prompts; the preview
   returns on its own each time.
5. **Expect:** no "which app?" chooser — the other installed booth app
   (`com.slrbooth.studio`) filters on USB classes 6 and 7, not the BRIO's 14/239.
6. Reboot the tablet and replug once more.

## 2. Admin gear reachable during a fault — never run on hardware

The gear is revealed by a long-press that now also has to get past the full-screen fault
overlay. `bringToFront()` is the part that has never executed.

1. With the fault screen up: a **single tap** must do nothing — no countdown, no "3… 2… 1".
2. A **long press** must reveal the gear, and that gear must be **tappable** and open the PIN
   dialog. Confirm it is drawn *above* the dark overlay, not behind it.
3. On the normal idle screen: long-press → gear → PIN → settings still works, and the gear
   still auto-hides after 8 seconds.
4. Confirm the gear stays pinned top-right after the reveal and nothing else on screen shifts.

## 3. Discord webhook actually reaches phones

1. Create a channel and a webhook (Channel Settings → Integrations → Webhooks → New Webhook →
   Copy Webhook URL).
2. Long-press → gear → PIN `1234` → paste the URL → **Save**.
3. Reopen admin → **Send test alert**. **Expect:** a `Test alert queued` toast, and within
   seconds an `@everyone` message that **pushes to a phone**, not just appears in the channel.
4. Reopen admin. **Expect:** the webhook field shows the `(unchanged)` hint, not the URL.
5. Enter a non-Discord URL and Save. **Expect:** `Not a valid Discord webhook URL`, dialog
   stays open.

---

## 4. The full alert chain, against a stopwatch

1. Camera connected, booth idle. **Unplug.**
2. **Immediately:** dark screen reading *"Booth needs a battery swap, find someone to help"*,
   and taps do nothing.
3. Replug **within 20 seconds**. **Expect:** overlay clears, preview returns, and **no Discord
   message was ever sent.** This is the wobble case, and it is the one the spec is most
   explicit about.
4. Unplug again and **wait 40 seconds.** **Expect:** exactly one `@everyone` message.
5. Replug. **Expect:** overlay clears and an all-clear arrives with no mention.
6. Unplug once more. **Expect:** it alerts again — the machine re-arms after recovery.

## 5. Reconnect timing — the known tuning risk

Unplug, wait past the 30-second debounce, replug. The overlay must clear and the all-clear
must arrive about **1 second** after reconnect.

If the BRIO needs longer than `CAMERA_CALIBRATION_DELAY_MS + RECONNECT_CONFIRM_MARGIN_MS`
(1000 ms total) to report `isPreviewing`, the one-shot confirmation misses — but the heartbeat
safety net clears the fault within 5 seconds regardless. If you see a consistent ~5 second
delay rather than ~1 second, raise `RECONNECT_CONFIRM_MARGIN_MS`.

## 6. Cold start with no camera — new behaviour, never run

1. Launch with the BRIO **unplugged**. **Expect:** fault screen at ~15 s, then exactly **one**
   `@everyone` alert 30 s later. Plug in → all-clear, overlay clears.
2. Launch with the camera **attached** and watch for a full minute. **Expect:** nothing on
   screen, nothing in Discord.

   If the BRIO routinely takes longer than 15 s to reach `isPreviewing` on this tablet,
   `STARTUP_CAMERA_CHECK_DELAY_MS` needs raising. **This is the single most likely tuning miss.**

## 7. Countdown interruption

1. Start a countdown, then unplug mid-countdown. **Expect:** fault screen immediately, no
   capture, no flash, no review screen while the camera is out.
2. Replug. **Expect:** a clean idle screen, not a stale review screen for a photo never taken.
3. Long-press during an active countdown. **Expect:** the countdown stops and the gear appears.
4. Dismiss admin (Cancel, or wrong PIN then back). **Expect:** the booth sits at idle, not
   showing a captured photo or advanced to review/upload.

## 8. Demo mode

1. Admin → tick **Demo mode** → Save. **Expect:** a red `DEMO MODE` banner, top-left.
2. Leave three minutes. **Expect:** it captures roughly once a minute on its own.
3. Check S3. **Expect:** objects under `<prefix>/demo/DEMO_IMG_*.jpg`, none mixed in with real
   photos.
4. Unplug, wait 40 s. **Expect:** the alert arrives **prefixed `[TEST]`**. Replug → `[TEST]`
   all-clear.
5. Untick → Save. **Expect:** banner gone, auto-capture stopped.
6. Clear one AWS field, tick Demo mode, Save. **Expect:** the "incomplete" toast **and** the
   banner turns on — the demo toggle now persists independently of the credentials.

## 9. Brightness

1. Idle 30 seconds. **Expect:** the screen dims noticeably but stays clearly readable — never
   black.
2. Tap once. **Expect:** full brightness **and** the countdown starts. One tap, not two.
3. Let a countdown and review run past 30 s untouched. **Expect:** no dimming mid-photo.
4. With the **fault screen** up, leave it untouched past 30 s. **Expect:** it stays at full
   brightness — the fault message is the primary alert channel and must not dim itself.
5. Demo mode for five minutes. **Expect:** it never dims.
6. Exit to the launcher. **Expect:** system brightness unchanged — the override is
   window-level only.

   Note: because it is window-level, anything that puts another app in front (an OTA dialog,
   the other booth app) silently lapses the pin. That is the intended trade for avoiding
   `WRITE_SETTINGS`.

---

## 10. The battery drain test

Run `battery-drain-test.md` in this directory. It doubles as the soak test for everything
above and is the only thing that tells you whether the pack lasts the evening.

Fill in the target duration **before** starting, and clear the demo backlog afterwards —
both are steps in that runbook.
