# Battery Drain Test

## What this measures

How long the USB battery pack keeps the Logitech BRIO alive under a worst-case
load. The webcam dies when the pack dies, which fires the Discord alert — so
**the alert's timestamp minus the start time is the runtime.** No stopwatch,
no laptop, nobody watching.

## Why it is a worst case

- Demo mode captures once a minute all night; real use is bursty and lighter.
- Demo mode pins brightness to 100% and suppresses idle dimming.
- The BRIO requests the full 500 mA (`max_power=500` in `dumpsys usb`).

A result here is therefore a **floor**. Real-world runtime should beat it.

## Procedure

1. Write down the **target duration** in the Result table *before* anything else
   (ceremony -> last dance + 1 h). The bands below are read against that number.
2. Charge the battery pack fully. Record its capacity in Wh or mAh below.
3. Charge the tablet to 100%.
4. Connect: pack -> OTG splitter -> tablet + BRIO.
5. Launch the booth. Confirm the preview is live.
6. Admin (long-press, gear, PIN) -> **Send test alert**. Confirm it lands in
   Discord on every helper's phone. **Do not skip this** - it verifies the
   measuring instrument before the measurement.
7. Admin -> tick **Demo mode** -> Save. Confirm the red `DEMO MODE` banner.
8. **Record the start time.** Walk away. Leave it overnight if needed.
9. When the pack dies, the webcam drops and a `[TEST]`-prefixed alert arrives.
   **That message's timestamp is the end time.**
10. Record the result below.
11. Admin -> untick Demo mode -> Save. Delete all photos with `DEMO_` prefix
    from the pending directory, then delete the `demo/` folder from S3. (The
    test generates 300+ demo files over hours; clearing them frees ~100 minutes
    of upload capacity that would otherwise block guests' photos during the
    wedding.)

## Result

| Field | Value |
|---|---|
| Date of test | |
| Pack make / capacity | |
| **Target duration** (fill in *before* starting) | |
| Start time | |
| Alert timestamp | |
| **Measured runtime** | |
| Tablet battery % at alert | |

## What the result decides

**Write the target down before you start**, so the decision is not argued
afterwards. The target is how long the booth must actually run: ceremony ->
last dance, plus 1 h of setup and overrun.

> Target duration: ______ hours (also record it in the Result table above)

Then compare the measured runtime against that recorded target:

- **Comfortably clears** — measured runtime **>= the target** -> change nothing.
  Do not build the camera cooldown; it adds risk for no benefit. (This test is a
  floor, so meeting the target here means beating it in real use.)
- **Marginal** — **>= 75% of the target but under it** (a 6 h target met by
  4 h 30 m to 6 h) -> lower the *preview* resolution (captures stay full res).
  Do this before considering the camera cooldown.
- **Well short** — **under 75% of the target** -> a bigger or second pack is the
  cheapest fix. A spare pack is what makes the alert actionable in the first
  place.

> **Do not build the camera cooldown without camera-health detection first.**
> If the app switches the camera off deliberately and it fails to wake,
> `onDettach` never fires, so the booth sits looking healthy while being dead
> and nothing alerts anyone. See the spec's "Key design constraint".
