# ▓▒░ CYBER//TOOLKIT ░▒▓

23 sensor & camera tools in one neon app – as a native **Android APK** and as a **Termux** script.
No ads, no internet permission, no tracking.

## 📱 Install the APK
Open **[Releases](https://github.com/glassray071-arch/cyber-compass/releases)** on your phone →
newest **Cyber Toolkit APK** → download `CyberToolkit.apk` → open it → allow installing.
Every push to `toolkit/` builds a new version that installs over the old one.

## Tools
| # | Tool | Sensor | What it's for |
|---|------|--------|---------------|
| 1 | Wi-Fi Radar | Wi-Fi | Nearby networks on a radar, distance estimated from signal |
| 2 | Compass | Mag + gyro | Tilt-compensated, true north from your location |
| 3 | Level / Angle | Accelerometer | Bubble level, edge slope in ° / % / 1-in-X, ZERO for relative angles |
| 4 | Metal Detector | Magnetometer | Screws, studs, rebar, magnets – Geiger beeps. **AC FIELD** mode traces cables *carrying current* via their 50 Hz field (never use to prove dead) |
| 5 | Vibration | Accelerometer | RMS, peak Hz → RPM, velocity mm/s with ISO 10816-style band, spectrum |
| 6 | Light Meter | Light | Lux min/max/avg + rough AS/NZS 1680 guide |
| 7 | Motion Alarm | Accelerometer | Tamper siren, voice, notification |
| 8 | Site Logger | GPS + camera | Location + note + photo → CSV, share, open in Maps |
| 9 | Sound Meter | Microphone | Approx dB (85 dB line), pitch/note, **50 Hz mains-hum detector** |
| 10 | Sonar | Speaker + mic | Experimental chirp echo rangefinder (0.5–6 m) |
| 11 | Seismograph | Accelerometer | 3-axis traces, STA/LTA event trigger, rough MMI |
| 12 | Barometer | Pressure | hPa, altitude, height from ZERO, floor counter |
| 13 | Gyro Tacho | Gyroscope | RPM + turn counter for reels, winches, wheels |
| 14 | Torch / Strobe | Camera flash | Torch, SOS, Morse messages, **strobe tachometer**, screen light |
| 15 | Measure Walk | Step detector | Pace out distances, calibrate stride over 10 m |
| 16 | Sensors | All | Every sensor in the phone with live values and specs |
| 17 | Leak Hunter | Microphone + rotation | Air/gas/vacuum leak hiss in the 8–22 kHz band vs background, Geiger feedback, **sweep heat map** (pocket acoustic camera) |
| 18 | Magnifier | Rear / front camera | Zoom to 30×, light, freeze, contrast/invert, **COLOUR ID** for cable cores, **IR CHECK** for remotes & CCTV IR |
| 19 | Light Flicker | Rear camera (rolling shutter) | % flicker of LED drivers / fluoro ballasts with IEEE 1789 risk band, frequency, stroboscopic warning |
| 20 | Height Meter | Rear camera + tilt | Distance and height of poles, walls, trees from your eye height |
| 21 | Camera Sentry | Rear camera | Motion detection that photographs intruders, notification + siren |
| 22 | Pulse | Rear camera + flash | Fingertip heart rate (fun demo, not medical) |
| 23 | S26 Sensor Map | Light, proximity, magnetometer | Where every sensor sits on the Galaxy S26, with live tests; mark your compass chip |

Tools your phone has no hardware for are greyed out.
Every tool shows a yellow **S26 ▸** line telling you which edge / face of a Galaxy S26 to point at the subject.

> Readings are indicative – not calibrated test equipment. AC FIELD only sees cables that are
> carrying load current; a live cable with nothing switched on is invisible. Always prove dead
> with an approved voltage indicator.

## 🐍 Termux version
`cyber_toolkit.py` has the first 9 tools in a terminal UI:
```bash
pkg install python termux-api
termux-setup-storage
python toolkit/cyber_toolkit.py          # or --demo on any PC
```

## Build
`toolkit/android` is a plain Android SDK project (Java, no libraries). GitHub Actions
(`.github/workflows/toolkit.yml`) builds and publishes the signed APK.
