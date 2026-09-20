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

1. Charge the battery pack fully. Record its capacity in Wh or mAh below.
2. Charge the tablet to 100%.
3. Connect: pack -> OTG splitter -> tablet + BRIO.
4. Launch the booth. Confirm the preview is live.
5. Admin (long-press, gear, PIN) -> **Send test alert**. Confirm it lands in
   Discord on every helper's phone. **Do not skip this** - it verifies the
   measuring instrument before the measurement.
6. Admin -> tick **Demo mode** -> Save. Confirm the red `DEMO MODE` banner.
7. **Record the start time.** Walk away. Leave it overnight if needed.
8. When the pack dies, the webcam drops and a `[TEST]`-prefixed alert arrives.
   **That message's timestamp is the end time.**
9. Record the result below.
10. Admin -> untick Demo mode -> Save. Delete all photos with `DEMO_` prefix
    from the pending directory, then delete the `demo/` folder from S3. (The
    test generates 300+ demo files over hours; clearing them frees ~100 minutes
    of upload capacity that would otherwise block guests' photos during the
    wedding.)

## Result

| Field | Value |
|---|---|
| Date of test | |
| Pack make / capacity | |
| Start time | |
| Alert timestamp | |
| **Measured runtime** | |
| Tablet battery % at alert | |

## What the result decides

- **Comfortably clears the evening** -> change nothing. Do not build the camera
  cooldown; it adds risk for no benefit.
- **Marginal** -> lower the *preview* resolution (captures stay full res). Do
  this before considering the camera cooldown.
- **Well short** -> a bigger or second pack is the cheapest fix. A spare pack
  is what makes the alert actionable in the first place.

> **Do not build the camera cooldown without camera-health detection first.**
> If the app switches the camera off deliberately and it fails to wake,
> `onDettach` never fires, so the booth sits looking healthy while being dead
> and nothing alerts anyone. See the spec's "Key design constraint".
