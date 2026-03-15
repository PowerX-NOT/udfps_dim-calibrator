# UDFPS Calibrator

Android app to help calibrate Under-Display Fingerprint Scanner (UDFPS) dimming behavior by controlling High Brightness Mode (HBM) and applying a fullscreen dim overlay whose alpha is derived from a brightness→alpha LUT.

## Download

- **APK (release)**: https://github.com/PowerX-NOT/udfps_dim-calibrator/releases/download/initial/app-debug.apk

## Requirements

- Root access (`su`) is required to read/write sysfs nodes.
- A device/kernel that exposes the required sysfs nodes (paths can be configured in Settings).

## What this app does

- Toggles HBM by writing `1`/`0` to the configured HBM sysfs node.
- Reads current brightness + max brightness from configured sysfs nodes.
- Creates a fullscreen black overlay (“dim layer”) when HBM is enabled.
- Computes the overlay alpha from a LUT:
  - You can **generate** the LUT from calibration inputs (nits with/without HBM + gamma).
  - Or **load** an existing framework table (`<item>brightness,alpha</item>...`).

## Sysfs nodes

These are configurable in **Settings**:

- **Current brightness node**
  - Default: `/sys/devices/platform/soc/ae00000.qcom,mdss_mdp/backlight/panel0-backlight/brightness`
- **Max brightness node**
  - Default: `/sys/devices/platform/soc/ae00000.qcom,mdss_mdp/backlight/panel0-backlight/max_brightness`
- **HBM node**
  - Default: `/sys/kernel/oplus_display/hbm`
  - Expected values: `1` = ON, `0` = OFF

## Calibration workflow (recommended)

### 1) Configure sysfs paths

- Open **Settings**.
- Set the three sysfs paths for your device (brightness/max brightness/HBM).
- Optionally set **Dim layer delay (ms)**.
  - This delay is used to sync the dim layer with HBM transitions.

### 2) Generate a LUT from panel calibration inputs

On the main screen:

- Enter:
  - `nits with HBM`
  - `nits without HBM`
  - `gamma factor`
- Tap **Apply calibration**.

This generates a brightness→alpha LUT internally and it will be used for dimming.

### 3) Enable HBM and verify dimming

- Toggle **HBM** ON.
- The app will:
  - Create/update the dim overlay
  - Enable HBM
  - Show status text like:
    - `HBM set to 1 (dim: generated (...))`

If you see `Failed to read current brightness` then the current brightness sysfs path is wrong or root is not granted.

## Using an existing framework table (Load Table)

If you already have a framework table, you can paste it directly:

Example format:

```xml
<integer-array name="config_udfpsDimmingBrightnessAlphaArray">
    <item>0,255</item>
    <item>3,240</item>
    <item>13,220</item>
</integer-array>
```

Steps:

- Paste the `<item>brightness,alpha</item>` lines into the **Paste framework table** field.
- Tap **Load Table**.
- Toggle HBM ON.

The app parses `<item>(\d+),(\d+)</item>` and uses those pairs as its LUT.

## How the dim layer alpha is computed from the table

- The app reads current panel brightness from the configured **current brightness node**.
- It searches the LUT for:
  - An exact match, or
  - The nearest lower/upper brightness entries.
- If needed, it linearly interpolates alpha between the two entries.
- The final alpha applied to the overlay is `alpha/255`.

## Building

```bash
./gradlew assembleDebug
```

APK output:

- `app/build/outputs/apk/debug/app-debug.apk`

## Installing

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Troubleshooting

- **Root not granted**
  - Make sure Magisk/Superuser is installed and the app is allowed.

- **HBM toggles but no dimming**
  - Make sure you pressed **Apply calibration** or **Load Table** before enabling HBM.

- **Failed to read current brightness**
  - Fix the current brightness sysfs node path in Settings.

- **HBM node write fails**
  - Fix the HBM node path in Settings and ensure it is writable with root.
