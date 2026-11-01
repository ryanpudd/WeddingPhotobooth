# OTG Host-Mode Full-Rate Charging Kernel Patch — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the wedding-photobooth tablet (Nexus 7 2013 `flo`, LineageOS 20) charge at the full 1200mA input limit while its OTG adapter is in host mode (switch position 2, webcam attached), instead of the ~500mA it gets today.

**Architecture:** A two-line-of-logic patch to `drivers/usb/otg/msm_otg.c` in followmsi's LineageOS 20 kernel. With `usbhost_charge_mode` on, host-mode charger detection always classifies the external supply as ACA, so the ASUS SMB345 charger driver applies its 1200mA input limit, and `usbhost_charge_mode` now defaults to on so it survives reboots. The kernel is cross-compiled in WSL2 Ubuntu (or any Linux box), swapped into the tablet's existing Magisk-patched boot image with the tablet's own `magiskboot`, tested non-persistently with `fastboot boot`, and only then flashed.

**Tech Stack:** Linux 3.4.113 (followmsi `android_kernel_google_msm`, branch `followmsi-13`, pinned commit `4658f2f7189b1889ed4704f4f211680e9915fb41`), `followmsi_defconfig`, GCC 4.9 `arm-linux-androideabi` (LineageOS prebuilt, branch `lineage-19.1`), Magisk 27.0 `magiskboot`, Windows `adb.exe`/`fastboot.exe` (platform-tools 37) called from WSL.

**Spec:** No separate spec document. The **Background** section below is the evidence base (gathered in the wedding-photobooth repo session on 2026-10-06), so rulings made during execution are provisional and should be explained to the user.

---

## How to start the execution session

This plan lives in the wedding-photobooth repo, but the work happens in a **new workspace: a kernel clone inside WSL's Linux filesystem**. A fresh Claude Code session has none of the original conversation's context; this document is everything it needs.

1. Open WSL Ubuntu on the Windows PC (`wsl -d Ubuntu`). The distro already exists; it is just stopped.
2. If Claude Code isn't installed inside WSL, install it there. Running Claude Code inside WSL is the supported path. Driving a WSL path from the Windows install works but makes every shell call slower and more error-prone.
3. Create the workspace directory and launch Claude Code from it:
   ```bash
   mkdir -p ~/src && cd ~/src && claude
   ```
4. First prompt: *"Execute the plan at `/mnt/c/workspace/repos/github.com/ryanpudd/wedding-photobooth/docs/superpowers/plans/2026-10-06-otg-host-charging-kernel.md`."*

A native Linux box works too: substitute its own `adb`/`fastboot` for the Windows `.exe` paths, and a local artifacts directory for `/mnt/c/workspace/flo-otg-artifacts`.

---

## Background (why this patch, with evidence)

**Hardware chain:** Anker PowerCore 24K pack → OTG Y-splitter with a 3-position switch → tablet micro-USB, plus a Logitech BRIO webcam on the splitter. The splitter powers the BRIO directly from the pack, and the BRIO draws up to 500mA (`max_power=500`).
- **Position 1** is charge-only: no ID-pin ground and no USB device visible.
- **Position 2** is the normal booth mode: OTG host with pack power.
- **Position 3** is another OTG-plus-power variant.

**Measured on 2026-10-06** (10 minutes each, booth app idle):

| Config | Net battery current | Rate |
|---|---|---|
| Position 1 (charge only) | 497–698 mA | ~23.5 %/hr |
| Position 2 (OTG host) | ~0 to +204 mA | ~0–7 %/hr |
| Position 3 | ~0 mA | ~0 %/hr |

**Units trap:** on this kernel, `/sys/class/power_supply/battery/current_now` is in **mA, not µA**. The bq27541 driver skips the ×1000 it applies to other fields (`drivers/power/bq27541_battery.c:596`). Positive means charging.

**What the kernel does at position 2** (live `dmesg`, `usbhost_charge_mode=Y`, which is already set at runtime on the tablet but resets on reboot):

```
id_pin_irq_work_function: APQ_OTG_ID_PIN is low : Host mode
[usbhost_charge_mode]: Do not supply power in host mode
smb345_charger: [smb345_otg_status] otg function: off
msm_otg msm_otg: chg_type = USB_SDP_CHARGER
smb345_charger: [usb_cable_type_detect] Cable: SDP        <- no "Set ICL=1200" follows
```

**What it does at position 1** (from a saved logcat):

```
APQ_OTG_ID_PIN is high : leave Host mode
msm_otg msm_otg: chg_type = USB_DCP_CHARGER
smb345_charger: [usb_cable_type_detect] Cable: DCP
smb345_charger: [smb345_set_InputCurrentlimit] Set ICL=1200 retval =21 setting=24
```

**Root cause in source:**
- **The SMB345 driver only raises the limit for full-current cable types.** In `drivers/power/smb345-charger.c`, `usb_cable_type_detect()` (≈line 843) calls `smb345_set_InputCurrentlimit(client, 1200)` only for CDP, DCP, OTHER or ACA. For **SDP** it never sets a limit, so the charger stays at its ~500mA USB default.
- **Host-mode detection falls back to SDP unpredictably.** `drivers/usb/otg/msm_otg.c` `msm_chg_detect_work()` (≈line 2278) classifies the supply in host mode from the D+/D- line state at the instant detection runs. If the lines happen to be active it reports `USB_ACA_A_CHARGER`; otherwise it reports `USB_SDP_CHARGER`. The timing at position 2 lands on SDP.
- **The Qualcomm notify path is a red herring.** flo has no `CONFIG_PM8921_CHARGER`, so `msm_otg`'s own `msm_otg_notify_charger()` path always fails harmlessly ("Failed notifying N charger type to PMIC", "Power Supply doesn't support USB charger"). The **only** path that sets the real charge limit is `asus_chg_set_chg_mode()` → `usb_cable_type_detect()`.

**Why forcing ACA is safe for the camera session:**
- In the OTG state machine, `chg_type` only feeds charger notifications. Host-session teardown (`OTG_STATE_A_HOST`, ≈line 2815) is driven purely by the `ID`, `ID_A`, `A_BUS_DROP` and `A_VBUS_VLD` input bits.
- This kernel's own `usbhost_charge_mode` code already assigns `USB_ACA_A_CHARGER` in host mode whenever line state is active. The patch makes that deterministic.
- The instability seen in an earlier investigation (and Don Bowman's 2013 reports) came from enabling `CONFIG_USB_MSM_ACA`, which changes ID-pin *resistor* evaluation. This plan does not touch that.

**References:** Don Bowman, "USB OTG Charging, Nexus 7 Flo (2013)" and "...some success!" (blog.donbowman.ca, Aug 2013). XDA "Nexus 7 2013 Timur's Kernel USB OTG + Charging" thread 2469140.

**Device facts:**

| Fact | Value |
|---|---|
| Running kernel | `3.4.113-followmsi+ #1 SMP PREEMPT Wed Aug 30 09:18:55 CEST 2023` |
| Defconfig / toolchain (device tree `followmsi/android_device_asus_flo` `los-20.0-test`) | `TARGET_KERNEL_CONFIG := followmsi_defconfig`, `TARGET_KERNEL_CLANG_COMPILE := false`, `BOARD_KERNEL_IMAGE_NAME = zImage` |
| Config facts | `CONFIG_KERNEL_XZ=y`, `CONFIG_IKCONFIG=y` (config embedded in the image; no `/proc/config.gz`), `# CONFIG_MODULES is not set`, `# CONFIG_USB_MSM_ACA is not set` |
| Boot partition | `/dev/block/platform/msm_sdcc.1/by-name/boot` → `/dev/block/mmcblk0p14` |
| Magisk | 27.0 (27000); `magiskboot` at `/data/adb/magisk/magiskboot` |
| Bootloader | `FLO-04.08` (unlocked, custom ROM) |
| USB serial | `0b02e35b` |
| Wireless adb | Wireless-debugging address changes per session (last seen `192.168.0.12:40767`); find it with `adb.exe mdns services` |
| Booth app | `com.ryanpudd.photobooth.debug/com.ryanpudd.photobooth.MainActivity`; the capture tap point is `960 576`; photos land in `/sdcard/Pictures/WeddingPhotobooth/` |
| Windows tools | `C:\Users\ryanp\AppData\Local\Android\Sdk\platform-tools\{adb,fastboot}.exe` |

---

## Global Constraints

- **Kernel tree location.** The kernel tree must live on a case-sensitive Linux filesystem with LF line endings (`~/src/flo-kernel` inside WSL). **Never** use `/mnt/c`: the tree has case-colliding filenames, and this PC's git has `core.autocrlf=true`, which corrupts the source.
- **Pinned source.** Use followmsi `android_kernel_google_msm` commit `4658f2f7189b1889ed4704f4f211680e9915fb41` (head of `followmsi-13`), config `followmsi_defconfig`, compiler GCC 4.9 `arm-linux-androideabi-` from LineageOS `android_prebuilts_gcc_linux-x86_arm_arm-linux-androideabi-4.9` branch `lineage-19.1`. Do not use clang or a distro cross-GCC: a 3.4 kernel won't build with modern GCC.
- **Scope of kernel changes.** The only kernel source change is `drivers/usb/otg/msm_otg.c`, via the provided patch (sha256 after LF normalisation: `b31294b79201a6e344b6a17065e16ec3167be46bc8c8019cb64e87940d8b2475`). Helper scripts go in `otg-charge/` at the kernel repo root.
- **No other knobs.** Do not enable `CONFIG_USB_MSM_ACA` or `CONFIG_USB_MSM_STANDARD_ACA`, do not raise the input limit above 1200mA, and do not change any other config option.
- **Back up first.** Back up the current boot image and record its sha256 **before** any `fastboot` command. Keep it in `/mnt/c/workspace/flo-otg-artifacts/`.
- **Test before flashing.** Always `fastboot boot` (non-persistent) and pass Task 4 before any `fastboot flash`. **`fastboot flash` requires the user's explicit approval in the conversation** (Task 5).
- **Human-in-the-loop steps.** Moving the OTG switch, swapping the tablet's single USB port between the PC cable and the OTG rig, removing or reattaching the battery pack, and toggling demo mode in the booth admin UI all need the user physically. Stop and ask; never assume they were done.
- **Real Discord alerts.** Any step that makes the booth lose its camera (pack removal) will fire a **real** Discord `@everyone` alert unless demo mode is on. Ask the user to enable demo mode first, and to disable it afterwards.
- **No pushes.** Do not push anywhere (GitHub fork etc.) without the user's approval.
- **OTA warning.** Installing a LineageOS OTA would replace this boot image and revert the fix. Tell the user this in the final summary.

## Review Focus

1. **Plug order.** Pack attached before vs after moving the switch to position 2, and tablet booted with the rig already attached. Each must end in `Cable: ACA` (or DCP/OTHER/CDP) plus `Set ICL=1200`, never `Cable: SDP`. Tested in Task 4 Step 6 and Task 5 Step 6.
2. **Pack removed mid-session, then reattached.** The kernel must log `INOK=H` / `CHARGER_NONE` without crashing (uptime keeps increasing). On reattach it must reclassify to a 1200mA cable type, and the booth app must show its camera-fault screen and then recover. Tested in Task 4 Step 7.
3. **Position 1 regression.** Charge-only mode must still give `Cable: DCP` plus `Set ICL=1200`, exactly as on the stock kernel. Tested in Task 4 Step 8.
4. **Camera stability while charging at full current.** 40 photos at position 2 must produce no `USB disconnect`, no `APQ_OTG_ID_PIN is high`, and about 40 new files. Tested in Task 4 Step 9.
5. **Pack supply sag under the higher draw.** The pack must now feed about 1200mA to the tablet plus up to 500mA to the BRIO. During the charge test and photo loop, `Status Reg E` low nibble should stay `4` (AICL settled at 1200), and no `INOK=H` should appear. Lower nibbles mean the pack can't sustain it: that's a hardware limit to report, not a patch bug. Tested in Task 4 Step 10.

---

## File Structure (kernel workspace `~/src/flo-kernel`, branch `otg-host-charge`)

| Path | Responsibility |
|---|---|
| `drivers/usb/otg/msm_otg.c` | The patch: default `usbhost_charge_mode=true`; force ACA in host mode. |
| `otg-charge/build.sh` | Reproducible cross-compile of `zImage` with the pinned toolchain and config. |
| `otg-charge/repack-boot.sh` | Pushes `zImage` to the tablet, swaps it into the backed-up boot image with on-device `magiskboot`, and pulls the result. |
| `otg-charge/verify-otg-charge.sh` | The acceptance test: kernel log classification plus a sampled net charge current (exit 0 PASS, 1 FAIL, 2 INCONCLUSIVE). |
| `otg-charge/photo-loop.sh` | Taps the booth's capture button every 15s, to drain the battery or stress the camera. |
| `otg-charge/0001-force-full-input-current-in-otg-host-mode.patch` | Copy of the patch, for provenance. |

Artifacts (Windows-visible, shared by WSL and `.exe` tools) live in `/mnt/c/workspace/flo-otg-artifacts/` (`C:\workspace\flo-otg-artifacts\`).

Every script uses the same shared environment (repeated in each script so they stand alone):

```bash
ADB="${ADB:-/mnt/c/Users/ryanp/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
FASTBOOT="${FASTBOOT:-/mnt/c/Users/ryanp/AppData/Local/Android/Sdk/platform-tools/fastboot.exe}"
ART=/mnt/c/workspace/flo-otg-artifacts
```

---

### Task 1: Workspace, toolchain, and a verified baseline build

**Files:**
- Create: `otg-charge/build.sh`
- Create (artifacts): `$ART/boot-original.img`, `$ART/boot-original.img.sha256`, `$ART/kernel-original.zImage`, `$ART/config-running.txt`

**Interfaces:**
- Produces: `otg-charge/build.sh` (no args) → `out/arch/arm/boot/zImage`, exit 0. `$ART/boot-original.img` is the untouched current boot image, used by Task 3 and as the rollback.

- [ ] **Step 1: Install host build dependencies (WSL)**

```bash
sudo apt-get update && sudo apt-get install -y build-essential bc bison flex libssl-dev xz-utils git perl python3 cpio lzop
```
Expected: exits 0.

- [ ] **Step 2: Clone the pinned kernel and the toolchain onto the Linux filesystem**

```bash
cd ~/src
git clone --branch followmsi-13 --single-branch https://github.com/followmsi/android_kernel_google_msm flo-kernel
git -C flo-kernel checkout -b otg-host-charge 4658f2f7189b1889ed4704f4f211680e9915fb41
mkdir -p toolchains
git clone --depth 1 --branch lineage-19.1 https://github.com/LineageOS/android_prebuilts_gcc_linux-x86_arm_arm-linux-androideabi-4.9 toolchains/arm-linux-androideabi-4.9
~/src/toolchains/arm-linux-androideabi-4.9/bin/arm-linux-androideabi-gcc --version | head -1
git -C flo-kernel config core.autocrlf
```
Expected: the gcc line contains `4.9`; `core.autocrlf` prints nothing or `false`. Confirm `pwd` is under `/home`, not `/mnt/c`.

- [ ] **Step 3: Write `otg-charge/build.sh`**

```bash
#!/usr/bin/env bash
# Cross-compiles the flo kernel exactly as the LineageOS 20 device tree does.
set -euo pipefail
cd "$(dirname "$0")/.."
export ARCH=arm SUBARCH=arm
export CROSS_COMPILE="$HOME/src/toolchains/arm-linux-androideabi-4.9/bin/arm-linux-androideabi-"
make O=out followmsi_defconfig
make O=out -j"$(nproc)" zImage
ls -l out/arch/arm/boot/zImage
```

```bash
chmod +x otg-charge/build.sh
```

- [ ] **Step 4: Build the unmodified kernel**

Run: `cd ~/src/flo-kernel && otg-charge/build.sh 2>&1 | tail -20`

Expected: ends with `out/arch/arm/boot/zImage` listed (roughly 6–8 MB).

If the build fails with `multiple definition of 'yylloc'`, change line 568 of `scripts/dtc/dtc-lexer.lex.c_shipped` from `YYLTYPE yylloc;` to `extern YYLTYPE yylloc;`. This should not happen, because `followmsi_defconfig` doesn't build dtc. Any other failure: stop and report the error to the user. Do not "fix" the toolchain choice.

- [ ] **Step 5: Back up the tablet's current boot image (ask the user for the current wireless adb address first)**

```bash
ADB=/mnt/c/Users/ryanp/AppData/Local/Android/Sdk/platform-tools/adb.exe; ART=/mnt/c/workspace/flo-otg-artifacts
"$ADB" mdns services            # pick the _adb-tls-connect address
S=192.168.0.12:40767            # <- replace with the address shown above
"$ADB" connect "$S"
mkdir -p "$ART"
"$ADB" -s "$S" shell "su -c 'dd if=/dev/block/platform/msm_sdcc.1/by-name/boot of=/sdcard/boot-original.img'"
"$ADB" -s "$S" pull /sdcard/boot-original.img "$(wslpath -w "$ART")\\boot-original.img"
( cd "$ART" && sha256sum boot-original.img | tee boot-original.img.sha256 )
"$ADB" -s "$S" shell "su -c 'sha256sum /sdcard/boot-original.img'"
```
Expected: both sha256 values match. `adb.exe` is a Windows program, so it needs a Windows path, hence `wslpath -w`.

- [ ] **Step 6: Extract the running kernel's embedded config and diff it against the build config**

```bash
"$ADB" -s "$S" shell "su -c 'mkdir -p /data/local/tmp/kpatch && cd /data/local/tmp/kpatch && cp /sdcard/boot-original.img . && /data/adb/magisk/magiskboot unpack boot-original.img && cp kernel /sdcard/kernel-original.zImage'"
"$ADB" -s "$S" pull /sdcard/kernel-original.zImage "$(wslpath -w "$ART")\\kernel-original.zImage"
cd ~/src/flo-kernel && scripts/extract-ikconfig "$ART/kernel-original.zImage" > "$ART/config-running.txt"
diff <(grep -v '^#' "$ART/config-running.txt" | grep -v '^$' | sort) <(grep -v '^#' out/.config | grep -v '^$' | sort) && echo "CONFIG MATCHES"
```
Expected: `magiskboot unpack` reports a `KERNEL_FMT` and writes `kernel`, then `CONFIG MATCHES`.
- If only `CONFIG_LOCALVERSION`-adjacent lines differ, that's acceptable. Note it.
- If any other options differ, **stop and show the user the diff**. The plan's assumption that `followmsi_defconfig` is what's running would be wrong.

- [ ] **Step 7: Commit**

```bash
cd ~/src/flo-kernel && git add otg-charge/build.sh && git commit -m "otg-charge: add reproducible flo kernel build script"
```

---

### Task 2: Acceptance test written first, proven to fail on the stock kernel

**Files:**
- Create: `otg-charge/verify-otg-charge.sh`, `otg-charge/photo-loop.sh`

**Interfaces:**
- Produces: `otg-charge/verify-otg-charge.sh <adb-serial> [minutes=10]` → exit 0 PASS, 1 FAIL, 2 INCONCLUSIVE. `otg-charge/photo-loop.sh <adb-serial> <stop-at-capacity-%> [max-photos]` → exit 0.

- [ ] **Step 1: Write `otg-charge/verify-otg-charge.sh`**

```bash
#!/usr/bin/env bash
# PASS = the tablet, in USB host (OTG) mode, is charging at the full 1200mA input limit.
# Usage: otg-charge/verify-otg-charge.sh <adb-serial> [minutes]   Exit: 0 PASS, 1 FAIL, 2 INCONCLUSIVE
set -u
SERIAL="${1:?usage: verify-otg-charge.sh <adb-serial> [minutes]}"
MINUTES="${2:-10}"
ADB="${ADB:-/mnt/c/Users/ryanp/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
MIN_MEAN_MA=400         # charge-only position measured ~690mA net; stock host mode ~0-200mA
MAX_START_CAPACITY=85   # above this the charger tapers, so a low reading proves nothing

root() { "$ADB" -s "$SERIAL" shell "su -c '$1'" 2>/dev/null | tr -d '\r'; }

result=0
echo "kernel:  $(root 'uname -v')"
mode=$(root 'cat /sys/module/msm_otg/parameters/usbhost_charge_mode')
echo "usbhost_charge_mode=$mode"
[ "$mode" = "Y" ] || { echo "FAIL: usbhost_charge_mode is '$mode', expected Y"; result=1; }

dm=$(root 'dmesg')
last_id=$(printf '%s\n' "$dm" | grep -E 'APQ_OTG_ID_PIN is (low|high)' | tail -1)
if ! printf '%s' "$last_id" | grep -q 'is low : Host mode'; then
  echo "INCONCLUSIVE: tablet is not in USB host mode (OTG switch at position 2 with camera attached?)"
  exit 2
fi
since_host=$(printf '%s\n' "$dm" | awk '/APQ_OTG_ID_PIN is low : Host mode/{buf=""} {buf=buf $0 "\n"} END{printf "%s", buf}')
cable=$(printf '%s' "$since_host" | grep -E '\[usb_cable_type_detect\] Cable: ' | tail -1)
echo "cable:   ${cable:-<none since host-mode entry>}"
printf '%s' "$cable" | grep -qE 'Cable: (ACA|DCP|CDP|OTHER)' || { echo "FAIL: supply not classified as a full-current charger"; result=1; }
printf '%s' "$since_host" | grep -q 'Set ICL=1200' || { echo "FAIL: no 'Set ICL=1200' since host-mode entry"; result=1; }
if printf '%s' "$since_host" | grep -qF '[usbhost_charge_mode]: host mode, forcing ACA'; then
  echo "marker:  patched detection path ran"
else
  echo "WARN:    patch marker absent (stock kernel, or detection took the DCP/CDP branch)"
fi

cap0=$(root 'cat /sys/class/power_supply/battery/capacity')
echo "capacity at start: ${cap0}%"
if ! [[ "$cap0" =~ ^[0-9]+$ ]]; then echo "INCONCLUSIVE: cannot read capacity"; exit 2; fi
if [ "$cap0" -gt "$MAX_START_CAPACITY" ]; then
  echo "INCONCLUSIVE: battery above ${MAX_START_CAPACITY}% (charger tapers); drain with otg-charge/photo-loop.sh first"
  [ "$result" -eq 0 ] && exit 2 || exit 1
fi

sum=0; n=0
for _ in $(seq 1 $(( MINUTES * 2 ))); do
  sleep 30
  ma=$(root 'cat /sys/class/power_supply/battery/current_now')   # mA on this driver; positive = charging
  [[ "$ma" =~ ^-?[0-9]+$ ]] || continue
  sum=$(( sum + ma )); n=$(( n + 1 ))
  echo "$(date +%H:%M:%S) current_now=${ma}mA"
done
cap1=$(root 'cat /sys/class/power_supply/battery/capacity')
[ "$n" -gt 0 ] || { echo "INCONCLUSIVE: no current samples read"; exit 2; }
mean=$(( sum / n ))
echo "mean net current: ${mean}mA over ${n} samples; capacity ${cap0}% -> ${cap1}%"
[ "$mean" -ge "$MIN_MEAN_MA" ] || { echo "FAIL: mean ${mean}mA < ${MIN_MEAN_MA}mA"; result=1; }
[ "$result" -eq 0 ] && echo "PASS" || echo "FAIL"
exit "$result"
```

- [ ] **Step 2: Write `otg-charge/photo-loop.sh`**

```bash
#!/usr/bin/env bash
# Taps the booth's capture button every 15s; stops at a battery % or after N photos.
# Usage: otg-charge/photo-loop.sh <adb-serial> <stop-at-capacity-%> [max-photos]
set -u
SERIAL="${1:?serial}"; TARGET="${2:?stop-at capacity %}"; MAX="${3:-100000}"
ADB="${ADB:-/mnt/c/Users/ryanp/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
root() { "$ADB" -s "$SERIAL" shell "su -c '$1'" 2>/dev/null | tr -d '\r'; }
for i in $(seq 1 "$MAX"); do
  "$ADB" -s "$SERIAL" shell input tap 960 576 >/dev/null 2>&1
  sleep 15
  if [ $(( i % 20 )) -eq 0 ]; then
    cap=$(root 'cat /sys/class/power_supply/battery/capacity')
    echo "$(date +%H:%M:%S) photos=$i capacity=${cap}%"
    if [[ "$cap" =~ ^[0-9]+$ ]] && [ "$cap" -le "$TARGET" ]; then echo "target reached"; exit 0; fi
  fi
done
echo "max photos reached ($MAX)"
```

```bash
chmod +x otg-charge/verify-otg-charge.sh otg-charge/photo-loop.sh
```

- [ ] **Step 3: Run the test against the stock kernel and watch it fail**

Ask the user to confirm the OTG switch is at **position 2**, with the camera and pack attached and the booth app on its idle screen.

Run: `otg-charge/verify-otg-charge.sh "$S" 10`

Expected: **FAIL**. The output shows `cable: ... Cable: SDP`, `FAIL: supply not classified as a full-current charger`, `FAIL: no 'Set ICL=1200'`, the WARN about the absent marker, and a mean well under 400mA.
- If it exits 2 because the battery is above 85%, run `otg-charge/photo-loop.sh "$S" 70` first. Stock host mode doesn't charge meaningfully, so photos drain it.
- If the stock kernel unexpectedly **passes**, stop and report to the user: the premise of this plan would be wrong.

- [ ] **Step 4: Make sure the battery is ≤ 75% before leaving the stock kernel**

On the patched kernel the battery will charge fast, so drain it now if needed: `otg-charge/photo-loop.sh "$S" 75`.

- [ ] **Step 5: Commit**

```bash
git add otg-charge/verify-otg-charge.sh otg-charge/photo-loop.sh && git commit -m "otg-charge: add host-mode charging acceptance test and photo loop"
```

---

### Task 3: Apply the patch, build, and repack the boot image

**Files:**
- Modify: `drivers/usb/otg/msm_otg.c` (≈lines 102 and 2278, via the patch)
- Create: `otg-charge/0001-force-full-input-current-in-otg-host-mode.patch`, `otg-charge/repack-boot.sh`
- Create (artifacts): `$ART/boot-otgcharge.img`, `$ART/boot-otgcharge.img.sha256`

**Interfaces:**
- Consumes: `otg-charge/build.sh`, `$ART/boot-original.img` (Task 1).
- Produces: `$ART/boot-otgcharge.img`, the patched boot image for Task 4 and Task 5. `otg-charge/repack-boot.sh <adb-serial>` → exit 0.

- [ ] **Step 1: Bring in the patch and verify its integrity**

```bash
cd ~/src/flo-kernel
cp /mnt/c/workspace/repos/github.com/ryanpudd/wedding-photobooth/docs/superpowers/plans/2026-10-06-otg-host-charging-kernel/0001-force-full-input-current-in-otg-host-mode.patch otg-charge/
sed -i 's/\r$//' otg-charge/0001-force-full-input-current-in-otg-host-mode.patch
sha256sum otg-charge/0001-force-full-input-current-in-otg-host-mode.patch
```
Expected: `b31294b79201a6e344b6a17065e16ec3167be46bc8c8019cb64e87940d8b2475`. If it differs, stop.

The patch's exact content (note: the line `//otg+charge` that follows has trailing tabs in the original source, so apply the file rather than hand-editing):

```diff
@@ -99,7 +99,7 @@ static int global_vbus_suspend_status;
 //otg+charge: sysfs entry
-static bool usbhost_charge_mode = false;
+static bool usbhost_charge_mode = true;
 module_param(usbhost_charge_mode, bool, 0755);
@@ -2275,7 +2275,11 @@ static void msm_chg_detect_work(struct work_struct *w)
-			if (line_state) { /* DP > VLGC or/and DM > VLGC */
+			/* otg+charge: data lines belong to the peripheral; SDP would cap input at 500mA */
+			if (usbhost_charge_mode && !test_bit(ID, &motg->inputs)) {
+				pr_info("[usbhost_charge_mode]: host mode, forcing ACA for full input current\n");
+				motg->chg_type = USB_ACA_A_CHARGER;
+			} else if (line_state) { /* DP > VLGC or/and DM > VLGC */
```

In host mode, `id_pin_irq_work_function()` clears the `ID` input bit, so `!test_bit(ID, ...)` is the existing "OTG adapter grounded" signal. The DCP/CDP/PROPRIETARY branch above this one is untouched; all of those already get 1200mA.

- [ ] **Step 2: Apply and inspect**

```bash
git apply --check otg-charge/0001-force-full-input-current-in-otg-host-mode.patch && git apply otg-charge/0001-force-full-input-current-in-otg-host-mode.patch
git diff --stat
grep -n "usbhost_charge_mode = true\|forcing ACA for full input current" drivers/usb/otg/msm_otg.c
```
Expected: `1 file changed, 6 insertions(+), 2 deletions(-)` and two grep hits.

- [ ] **Step 3: Build**

Run: `otg-charge/build.sh 2>&1 | tail -5` and confirm the patched code compiled: `grep -c "forcing ACA for full input current" out/drivers/usb/otg/msm_otg.o`.

Expected: `zImage` listed; grep count ≥ 1.

- [ ] **Step 4: Write `otg-charge/repack-boot.sh`**

```bash
#!/usr/bin/env bash
# Swaps out/arch/arm/boot/zImage into the backed-up Magisk boot image using the tablet's own magiskboot.
# Usage: otg-charge/repack-boot.sh <adb-serial>
set -euo pipefail
SERIAL="${1:?serial}"
ADB="${ADB:-/mnt/c/Users/ryanp/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
ART=/mnt/c/workspace/flo-otg-artifacts
cd "$(dirname "$0")/.."
cp out/arch/arm/boot/zImage "$ART/zImage-otgcharge"
"$ADB" -s "$SERIAL" push "$(wslpath -w "$ART")\\zImage-otgcharge" /sdcard/zImage-otgcharge
"$ADB" -s "$SERIAL" shell "su -c 'rm -rf /data/local/tmp/kpatch && mkdir -p /data/local/tmp/kpatch && cd /data/local/tmp/kpatch && cp /sdcard/boot-original.img . && /data/adb/magisk/magiskboot unpack boot-original.img && cp /sdcard/zImage-otgcharge kernel && /data/adb/magisk/magiskboot repack boot-original.img boot-otgcharge.img && cp boot-otgcharge.img /sdcard/boot-otgcharge.img'"
"$ADB" -s "$SERIAL" pull /sdcard/boot-otgcharge.img "$(wslpath -w "$ART")\\boot-otgcharge.img"
( cd "$ART" && sha256sum boot-otgcharge.img | tee boot-otgcharge.img.sha256 && ls -l boot-original.img boot-otgcharge.img )
```

```bash
chmod +x otg-charge/repack-boot.sh && otg-charge/repack-boot.sh "$S"
```
Expected: `magiskboot repack` succeeds and `boot-otgcharge.img` exists. It should be similar in size to `boot-original.img` and below the boot partition size (check with `"$ADB" -s "$S" shell "su -c 'blockdev --getsize64 /dev/block/mmcblk0p14'"`).

- [ ] **Step 5: Commit**

```bash
git add drivers/usb/otg/msm_otg.c otg-charge/0001-force-full-input-current-in-otg-host-mode.patch otg-charge/repack-boot.sh
git commit -m "msm_otg: charge at full 1200mA input limit in OTG host mode

With usbhost_charge_mode on, host-mode charger detection fell through to
SDP whenever the D+/D- lines were idle at detection time, leaving the
SMB345 at its ~500mA USB default. Always classify the external supply
as ACA in host mode so usb_cable_type_detect() applies 1200mA, and
default usbhost_charge_mode to on so it survives reboots."
```

---

### Task 4: Non-persistent test boot and acceptance (fastboot boot)

**Files:** none changed (verification only). Record results in `$ART/test-results.md`.

**Interfaces:**
- Consumes: `$ART/boot-otgcharge.img`, `otg-charge/verify-otg-charge.sh`, `otg-charge/photo-loop.sh`.
- Produces: a PASS record that gates Task 5.

- [ ] **Step 1: Reboot to the bootloader and swap cables (ASK USER)**

```bash
"$ADB" -s "$S" reboot bootloader
```
Ask the user to unplug the OTG rig and connect the tablet to the PC with a plain USB data cable.

- [ ] **Step 2: Confirm fastboot sees the tablet**

```bash
FASTBOOT=/mnt/c/Users/ryanp/AppData/Local/Android/Sdk/platform-tools/fastboot.exe
"$FASTBOOT" devices
```
Expected: `0b02e35b	fastboot`. If nothing is listed, Windows lacks the bootloader driver. Ask the user to install **Google USB Driver** (Android Studio → SDK Manager → SDK Tools), then in Device Manager point the "Android"/"Nexus 7" device at that driver. Re-run.

- [ ] **Step 3: Boot the patched image without flashing**

```bash
"$FASTBOOT" boot "$(wslpath -w /mnt/c/workspace/flo-otg-artifacts/boot-otgcharge.img)"
```
Expected: `Sending 'boot.img' ... OKAY`, `Booting OKAY`; the tablet boots LineageOS. A normal reboot at any point returns to the stock kernel.

- [ ] **Step 4: Re-establish adb over the network (while still on the USB cable)**

```bash
"$ADB" devices                                 # expect 0b02e35b  device
"$ADB" -s 0b02e35b tcpip 5555
S=192.168.0.12:5555; "$ADB" connect "$S"
"$ADB" -s "$S" shell "su -c 'uname -v; cat /sys/module/msm_otg/parameters/usbhost_charge_mode'"
```
Expected: `uname -v` shows **today's build date** (not `Wed Aug 30 ... 2023`), and the parameter shows `Y` without anyone setting it.
- If `tcpip` is refused, ask the user to re-enable Wireless debugging and use the `adb.exe mdns services` address instead.
- Then ask the user to unplug the PC cable, reattach the OTG rig at **position 2** with the pack, and open the booth app.

- [ ] **Step 5: Main acceptance test**

Run: `otg-charge/verify-otg-charge.sh "$S" 10`

Expected: **PASS**, with `Cable: ACA`, `Set ICL=1200`, `marker: patched detection path ran`, and a mean of at least 400mA.

- [ ] **Step 6: Review Focus 1 — plug order**

Ask the user to remove the pack, move the switch to 1, then:
- **(a)** attach the pack, then move the switch to 2;
- **(b)** after the first check, remove the pack, move the switch to 2, then attach the pack.

After each, run `otg-charge/verify-otg-charge.sh "$S" 1` and check the classification lines.

Expected: both orders show `Cable: ACA` (or DCP/OTHER/CDP) plus `Set ICL=1200`, never `Cable: SDP`. If one order yields SDP, record it and stop. The fix would need `otg_host_on` in the condition, which is a change to discuss with the user.

- [ ] **Step 7: Review Focus 2 — pack removed mid-session (ASK USER to enable demo mode first)**

Ask the user to enable demo mode in the booth admin UI, so the camera-lost Discord alert is `[TEST]`-prefixed. Then:

```bash
up0=$("$ADB" -s "$S" shell cat /proc/uptime | cut -d. -f1)
```
Ask the user to remove the pack for 60 seconds, then reattach it.
```bash
"$ADB" -s "$S" shell "su -c 'dmesg'" | grep -E "INOK=|CHARGER_NONE|Cable: |Set ICL" | tail -8
up1=$("$ADB" -s "$S" shell cat /proc/uptime | cut -d. -f1); [ "$up1" -gt "$up0" ] && echo "no reboot"
```
Expected: `INOK=H` and `CHARGER_NONE` on removal; `Cable: ACA` and `Set ICL=1200` after reattach; `no reboot`. The user should also confirm the booth showed its camera-fault screen and then recovered. Ask the user to disable demo mode again.

- [ ] **Step 8: Review Focus 3 — position 1 regression**

Ask the user to move the switch to **position 1**.
```bash
"$ADB" -s "$S" shell "su -c 'dmesg'" | grep -E "leave Host mode|chg_type =|Cable: |Set ICL" | tail -4
```
Expected: `leave Host mode`, `chg_type = USB_DCP_CHARGER`, `Cable: DCP`, `Set ICL=1200`, the same as the stock kernel. Ask the user to move it back to **position 2**.

- [ ] **Step 9: Review Focus 4 — camera stability while charging**

```bash
n0=$("$ADB" -s "$S" shell "ls /sdcard/Pictures/WeddingPhotobooth | wc -l" | tr -d '\r')
l0=$("$ADB" -s "$S" shell "su -c 'dmesg'" | wc -l)
otg-charge/photo-loop.sh "$S" 0 40
n1=$("$ADB" -s "$S" shell "ls /sdcard/Pictures/WeddingPhotobooth | wc -l" | tr -d '\r'); echo "new photos: $(( n1 - n0 ))"
"$ADB" -s "$S" shell "su -c 'dmesg'" | tail -n +$(( l0 + 1 )) | grep -cE "USB disconnect|APQ_OTG_ID_PIN is high"
```
(Don't clear the kernel log with `dmesg -c`: Step 10 needs the log from Steps 5–9.)
Expected: new photos ≈ 40 (at least 36 allows for a tap landing during review); the disconnect grep count is `0`.

- [ ] **Step 10: Review Focus 5 — pack supply sag**

```bash
"$ADB" -s "$S" shell "su -c 'dmesg'" | grep -E "Status Reg E|INOK=H" | tail -15
```
Expected: `Status Reg E=0x?4` lines (low nibble `4` = AICL settled at 1200mA) and no `INOK=H` during Steps 5 and 9. If the low nibble is 2 or 3 or `INOK=H` appears, record it: the pack can't sustain 1200mA plus the camera. That's a hardware limit to report to the user, not a patch failure.

- [ ] **Step 11: Write up results**

Write `$ART/test-results.md` with each step's PASS/FAIL and the key log lines. Then tell the user the outcome and ask whether to proceed to Task 5. A plain reboot now returns the tablet to the stock kernel.

---

### Task 5: Permanent flash (ONLY with the user's explicit approval)

**Files:** none changed. Artifacts: `$ART/boot-original.img` stays as the rollback.

**Interfaces:**
- Consumes: a Task 4 PASS and explicit user approval in the conversation.

- [ ] **Step 1: Get explicit approval**

Ask: *"Task 4 passed. Flash `boot-otgcharge.img` to the boot partition permanently? Rollback is `fastboot flash boot boot-original.img`."* Do not proceed without a yes.

- [ ] **Step 2: Reboot to bootloader with the PC cable attached (ASK USER to swap cables)**

```bash
"$ADB" -s "$S" reboot bootloader && "$FASTBOOT" devices
```
Expected: `0b02e35b	fastboot`.

- [ ] **Step 3: Flash and reboot**

```bash
"$FASTBOOT" flash boot "$(wslpath -w /mnt/c/workspace/flo-otg-artifacts/boot-otgcharge.img)"
"$FASTBOOT" reboot
```
Expected: `Writing 'boot' OKAY`, then a normal boot.

- [ ] **Step 4: Verify the flashed image matches and root survived**

```bash
SZ=$(stat -c %s /mnt/c/workspace/flo-otg-artifacts/boot-otgcharge.img)
"$ADB" -s 0b02e35b shell "su -c 'head -c $SZ /dev/block/platform/msm_sdcc.1/by-name/boot | sha256sum'"
cat /mnt/c/workspace/flo-otg-artifacts/boot-otgcharge.img.sha256
"$ADB" -s 0b02e35b shell "su -c 'uname -v; cat /sys/module/msm_otg/parameters/usbhost_charge_mode'"
```
Expected: the two sha256 values match; `uname -v` shows the new build date; the parameter shows `Y`; `su` works, so Magisk root survived.

- [ ] **Step 5: Restore network adb and the rig (ASK USER)**

```bash
"$ADB" -s 0b02e35b tcpip 5555 && S=192.168.0.12:5555 && "$ADB" connect "$S"
```
Ask the user to unplug the PC cable and reattach the OTG rig at position 2 with the pack.

- [ ] **Step 6: Review Focus 1(c) — boot with the rig already attached**

Ask the user to reboot the tablet **with the rig attached at position 2**. Wait for boot, then run `otg-charge/verify-otg-charge.sh "$S" 10`.

Expected: **PASS**. This proves the default-on parameter and the fix both survive a reboot with no manual step.

- [ ] **Step 7: Final summary to the user**

Report the measured mean current and rate (%/hr) for stock vs patched, and the rollback command (`fastboot flash boot C:\workspace\flo-otg-artifacts\boot-original.img`). Include two warnings:
- **OTA updates revert the fix.** A LineageOS OTA would replace the boot image, so the patch would need re-applying.
- **Plain OTG peripherals won't be powered.** With `usbhost_charge_mode` defaulting on, the tablet no longer powers OTG peripherals from its own battery, so a USB stick on a plain OTG adapter without external power won't work. That's fine for the booth, which always has the pack.
