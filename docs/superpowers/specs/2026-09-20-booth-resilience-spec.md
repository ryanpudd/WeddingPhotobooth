# Booth Resilience & Battery Alerting — Spec

**Date:** 2026-09-20
**Status:** Confirmed (derived from a grilling session, 2026-09-20)

## Problem

The photobooth runs from a USB battery pack that may not last the whole wedding.
The tablet never reports "charging" from the pack — it simply stops discharging —
so pack state cannot be read directly. The observable signal is that **the USB
webcam disconnects when the pack stops supplying power**, which is already
proven on the hardware (the existing `onDettach` Toast fires reliably).

When that happens the booth is dead and nobody knows.

## Goals

1. When the camera is lost, tell people — on the booth screen, and on their phones.
2. Make the whole chain testable before the wedding day.
3. Fix two existing annoyances that make the booth fragile: the repeating USB
   permission prompt, and an undiagnosed stall on camera start/reconnect.

## Hardware & environment (verified via `adb shell dumpsys usb`)

- Webcam: **Logitech BRIO**, `vendor_id=1133` (0x046D), `product_id=2142` (0x085E)
- Device class `239/2/1` (composite, IAD); video interfaces class `14` (UVC)
- `max_power=500` — the BRIO draws the full 500 mA
- Tablet reports `sink_power=true` with `usb_charging=false` — confirms the
  "powered but not charging" behaviour at framework level
- Another photobooth app is installed (`com.slrbooth.studio`) with USB filters
  covering specific vendor IDs plus classes 6 and 7. **None match the BRIO's
  class 14 or vendor 1133**, so a filter of ours will not cause a chooser dialog.
- Venue wifi assumed adequate; tablet is wifi-only

## Root cause: the permission prompt

`dumpsys` shows the device node walking `001/003 -> 005 -> 007 -> 009 -> 011 ->
014 -> 016` across 15 connects, while `permissions_manager` holds a grant for
exactly one node. Android's runtime USB grant is **bound to the device node
path**, and every replug mints a new node, orphaning the grant.

The fix is `device_filter.xml` plus a `USB_DEVICE_ATTACHED` intent-filter, which
surfaces the "use by default for this USB device" checkbox — the only grant
keyed to the device rather than the node.

## Requirements

### R1 — USB permission persistence
Adding a device filter and attach intent-filter so the permission survives
replugs and app restarts. May also resolve R7.

### R2 — Disconnect alerting
- Trigger: the existing `onDettach` / `onDisconnect` callbacks. No battery-state
  polling, no frame counting.
- On loss: **immediately** show a full-screen message —
  `Booth needs a battery swap, find someone to help`
- After a **30 second** debounce, post **one** message to Discord with `@everyone`.
  A reconnect inside the debounce window cancels it, so a cable wobble never pings.
- On reconnect after an alert has been sent: post an **all-clear**.
- No repeats. Fire-and-forget: if the send fails it fails, the screen carries it.

### R3 — Discord as the channel
Chosen because it is the app the recipients already have. A dedicated channel with
`@everyone`, since a plain webhook post produces no phone push.

### R4 — Diagnostics
A PIN-gated diagnostic button that dumps camera and USB state to Discord and to
screen. **Must be reachable from any `BoothState`** — the existing admin gear is
gated on `IDLE` (`MainActivity.kt:174-181`) and so is unreachable in exactly the
wedged state where it is needed.

### R5 — Demo mode
A PIN-gated toggle that auto-captures on a loop, with real uploads (under a
`demo/` key prefix) and real alerts (prefixed `[TEST]`). Doubles as the battery
drain test: **the Discord alert's timestamp is the pack runtime measurement.**
Pinned at 100% brightness with idle dimming suppressed, so the test is a true
worst case.

### R6 — Brightness
Pinned to 100% in normal use. Dims to 30% after 30 s idle; any touch restores
full brightness *and* starts the photo countdown. Window-level only
(`window.attributes.screenBrightness`) — no `WRITE_SETTINGS`, nothing persisted.

### R7 — Camera stall (diagnose only)
An undiagnosed stall on start/reconnect. This spec adds **diagnostics only**.
Automatic recovery is deliberately deferred until the diagnostics say what fails.

## Out of scope (explicit decisions)

| Excluded | Why |
|---|---|
| Cloud dead-man's switch | A black screen gets noticed in the room |
| Battery/power-state detection | `onDettach` is proven, free, already wired |
| Discord retry / queueing | A late battery alert is noise; the screen is primary |
| Auto-recovery ladder, camera-health watchdog | Deferred until diagnostics land |
| Idle preview shutdown / camera cooldown | Cycles the exact path that stalls, and converts a loud failure into a silent one. Revisit only if the drain test says runtime is marginal |
| Demo-mode auto-off guard | Setup happens on the day; it would be noticed |
| WhatsApp / SMS / Pushover | Discord is what recipients already have |

## Key design constraint

**A camera cooldown cannot ship before camera-health detection.** If the app
switches the camera off deliberately and it fails to wake, `onDettach` never
fires, so the booth sits looking healthy while being dead and nothing alerts
anyone. Recorded here so the reasoning is not lost when the drain test result
makes the cooldown tempting.
