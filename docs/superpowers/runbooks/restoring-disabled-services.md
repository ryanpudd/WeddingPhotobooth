# Restoring Disabled Services

## Why these are disabled

During the 2026-09 power-drain investigation, Bluetooth, location, NFC, and
Play Store's background jobs were disabled on the booth tablet as candidate
power-saving levers. **Measured result: none of them made a measurable
difference to idle drain** (all landed within noise of the camera-attached
baseline, ~-200 to -240 on `current_now`). They were left off anyway since a
kiosk booth has no legitimate use for any of them — but if something breaks
later (an app update check that never runs, a location-based feature, a
Bluetooth accessory, NFC tap-to-share) and you need to rule these out or
just turn one back on, here's how.

All commands assume the same ADB setup used throughout this investigation:

```bash
export ANDROID_SERIAL="<tablet's adb serial or IP:port>"
```

## Bluetooth

Disabled with:
```bash
adb shell svc bluetooth disable
```

Re-enable:
```bash
adb shell svc bluetooth enable
adb shell settings get global bluetooth_on   # should read 1
```

## Location

Disabled by setting location mode to off (was `3` = HIGH_ACCURACY):
```bash
adb shell settings put secure location_mode 0
```

Re-enable (restores high-accuracy mode, matching the original state):
```bash
adb shell settings put secure location_mode 3
adb shell settings get secure location_mode   # should read 3
```

## NFC

Disabled with:
```bash
adb shell svc nfc disable
```

Re-enable:
```bash
adb shell svc nfc enable
adb shell dumpsys nfc | grep "^mState"   # should read mState=on
```

## Play Store (com.android.vending)

Disabled with:
```bash
adb shell pm disable-user --user 0 com.android.vending
```

Re-enable:
```bash
adb shell pm enable --user 0 com.android.vending
adb shell pm list packages -d | grep vending   # should print nothing if re-enabled
```

## Restoring everything at once

```bash
adb shell svc bluetooth enable
adb shell settings put secure location_mode 3
adb shell svc nfc enable
adb shell pm enable --user 0 com.android.vending
```

None of these require a reboot to take effect. None of them touch
`usbhost_charge_mode` or the ACA debugfs flag (`/sys/kernel/debug/msm_otg/aca`)
— those are unrelated to this set and are covered by the battery-drain
runbook and the kernel investigation notes instead.
