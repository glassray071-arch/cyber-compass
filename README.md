# ▓▒░ CYBER//COMPASS ░▒▓

A neon, tilt-compensated compass that runs in **Termux** on Android, using the
phone's real accelerometer and magnetometer via **Termux:API**.
Pure Python, no extra packages.

```
 ▓▒░ CYBER//COMPASS ░▒▓            03:24:04  LINK ●
         330             N             030
◀ ╵  │ ╵  ┃ ╵  │ ╵  │ ╵  ┃ ╵  │ ╵  │ ╵  ┃ ╵  │ ╵ ▶
                         ▲
                █▀█ █▀█  ▀█ ° N
                █ █ █ █   █
                ▀▀▀ ▀▀▀   ▀   MAG
                         ▼
                   ·•····◆· ···•
              •···       ┆      ····
          •·             N             ··•
       ···      330      ▲      30        ·
   ◆     W      ·        ◉        ·      E     ◆
  FIELD  48.5 µT ▮▮▮▮▮▯▯▯▯▯ CLEAN
  PITCH  -0.5° ROLL  +1.0° ◎ LEVEL
```


## 📱 Android app (APK) – no Termux needed
A native version lives in [`android/`](android). GitHub Actions builds it on every push.

**Install:** open **[Releases](https://github.com/glassray071-arch/cyber-compass/releases/latest)**
on your phone → download `CyberCompass.apk` → open it → allow installing from your browser.

**GLOBE mode (3D):** a holographic Earth, heading-up, showing where you are, your heading beam,
the great-circle path to **true north** (geographic pole + Earth's spin axis), the path to the
**magnetic north dip pole** (computed on-device from the World Magnetic Model), declination and distances.
Drag to orbit, double-tap to reset.

App extras: fused gyro-assisted heading, sensor calibration status, MARK / TRUE / CALIBRATE
buttons, and automatic magnetic declination for true north (uses your location once).

## 🧰 Also in this repo: CYBER//TOOLKIT
16 sensor tools in one app – Wi-Fi radar, level, metal detector, vibration meter, sound meter
with 50 Hz hum detection, sonar, seismograph, strobe tachometer and more.
See **[toolkit/](toolkit)** – APK under [Releases](https://github.com/glassray071-arch/cyber-compass/releases).

## Features
- Rotating neon dial with radar sweep, magenta north needle and fixed lubber line
- Scrolling heading tape + big segmented heading readout, 16-point cardinal
- **Tilt-compensated** heading (same maths as Android's `getRotationMatrix`)
- Magnetic field strength with interference warning (keep away from steel, magnets, speakers)
- Pitch / roll with level indicator
- **Mark** a bearing and get turn-left / turn-right guidance back to it
- True-north mode with your local magnetic declination
- `--demo` mode (no sensors) and `--plain` mode for scripting

## Install (Termux)
1. Install the **Termux:API** app from the *same place* you got Termux
   (F-Droid or GitHub — mixing Play Store and F-Droid builds won't work).
2. In Termux:
   ```bash
   pkg install git
   git clone https://github.com/glassray071-arch/cyber-compass
   cd cyber-compass
   bash install.sh
   cyber-compass
   ```
   Or run without installing: `python cyber_compass.py`

## Usage
```
cyber-compass                      # live sensors
cyber-compass --demo               # simulated sensors
cyber-compass --declination 25.5 --true   # true north (east +, west -)
cyber-compass --plain              # "274.3 W  pitch=... roll=..." lines
cyber-compass --smooth 0.1         # steadier needle (1 = raw)
cyber-compass --selftest           # run the built-in tests
```
Find your declination at NOAA's magnetic field calculator
(<https://www.ngdc.noaa.gov/geomag/calculators/magcalc.shtml>).

| Key | Action |
|-----|--------|
| `q` | quit |
| `m` | mark / clear current bearing |
| `t` | toggle true / magnetic north |
| `c` | show calibration tip |

**Hold the phone flat-ish, top edge pointing where you want the heading.**
If the field reads NOISY/INTERFERENCE or the heading drifts, wave the phone in a
figure-8 for ~10 s to recalibrate the magnetometer.

## Troubleshooting
- **Stuck on "WAITING FOR SENSORS"** – Termux:API app missing, or from a different
  source than Termux. Test with `termux-sensor -l`.
- **Sensors busy after a crash** – run `termux-sensor -c`.
- **Dial too small** – zoom out (pinch) in Termux or rotate to portrait.

## License
MIT
