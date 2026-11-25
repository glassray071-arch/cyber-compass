#!/usr/bin/env python3
"""
CYBER//TOOLKIT  v1.0
All-in-one neon sensor toolkit for Android phones (Termux + Termux:API).

Tools:
  1 Wi-Fi Radar       - nearby access points plotted by signal strength
  2 Compass           - tilt-compensated heading with scrolling tape
  3 Level / Angle     - bubble level, edge slope, zero-to-reference
  4 Metal Detector    - magnetometer field change with vibration alert
  5 Vibration Meter   - RMS, peak frequency, RPM and live spectrum
  6 Light Meter       - lux with min / max / average
  7 Motion Alarm      - armed tamper alarm with notification + vibrate
  8 Site Logger       - GPS + note + optional photo saved to CSV
  9 Sensor Explorer   - live view of every sensor in the phone

Setup (in Termux):
  1. Install the Termux:API app (same source as Termux, e.g. F-Droid)
  2. pkg install python termux-api
  3. termux-setup-storage          (lets Site Logger save to phone storage)
  4. python cyber_toolkit.py
Try it on any PC without sensors:  python cyber_toolkit.py --demo

Controls: number keys or tap to choose, q / Esc / tap bottom bar to go back.
"""
import codecs
import csv
import curses
import hashlib
import json
import locale
import math
import os
import random
import shutil
import subprocess
import sys
import threading
import time
from collections import deque
from datetime import datetime

DEMO = "--demo" in sys.argv
APP = "CYBER//TOOLKIT"
VERSION = "1.0"
G = 9.80665

# ════════════════════════════ BACKEND (Termux:API) ════════════════════════════


def run_json(cmd, timeout=20):
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout).stdout
        return json.loads(out) if out.strip() else None
    except (subprocess.TimeoutExpired, json.JSONDecodeError, OSError):
        return None


def fire(cmd):
    """Run a Termux command in the background without waiting."""
    if DEMO:
        return
    try:
        subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError:
        pass


def vibrate(ms=60):
    fire(["termux-vibrate", "-d", str(ms), "-f"])


def notify(title, text):
    fire(["termux-notification", "--title", title, "--content", text,
          "--priority", "high", "--id", "cybertoolkit"])


def speak(text):
    fire(["termux-tts-speak", text])


DEMO_SENSORS = ["DEMO Accelerometer", "DEMO Magnetic Field Sensor",
                "DEMO Light Sensor", "DEMO Gyroscope", "DEMO Proximity Sensor"]
_sensor_cache = None


def list_sensors():
    global _sensor_cache
    if _sensor_cache is None:
        if DEMO:
            _sensor_cache = list(DEMO_SENSORS)
        else:
            d = run_json(["termux-sensor", "-l"], 15)
            _sensor_cache = d.get("sensors", []) if isinstance(d, dict) else []
    return _sensor_cache


def find_sensor(keywords, exclude=()):
    """Pick the best-matching sensor name (prefers calibrated, non-wakeup)."""
    cands = []
    for n in list_sensors():
        low = n.lower()
        if any(k in low for k in keywords) and not any(e in low for e in exclude):
            score = 0
            if "uncalibrated" in low:
                score += 10
            if "wake" in low and "non" not in low:
                score += 5
            if "secondary" in low:
                score += 3
            cands.append((score, len(n), n))
    return min(cands)[2] if cands else None


def accel_name():
    return find_sensor(["accelerometer"], ["linear", "uncalibrated"]) or \
        find_sensor(["accelerometer"])


def mag_name():
    return find_sensor(["magnetic", "magnetometer"], ["rotation", "uncalibrated"]) or \
        find_sensor(["magnetic", "magnetometer"], ["rotation"])


def light_name():
    return find_sensor(["light"])


class SensorStream:
    """Streams termux-sensor JSON in a background thread."""

    def __init__(self, names, delay_ms=100, history=0):
        self.names = [n for n in names if n]
        self.delay = delay_ms
        self.latest = {}
        self.lock = threading.Lock()
        self.proc = None
        self.alive = True
        self.hist = deque(maxlen=history) if history else None
        if self.names:
            target = self._demo if DEMO else self._run
            threading.Thread(target=target, daemon=True).start()

    def _push(self, name, vals):
        t = time.monotonic()
        with self.lock:
            self.latest[name] = vals
            if self.hist is not None:
                self.hist.append((t, name, vals))

    def _run(self):
        try:
            self.proc = subprocess.Popen(
                ["termux-sensor", "-s", ",".join(self.names), "-d", str(self.delay)],
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        except OSError:
            return
        dec = json.JSONDecoder()
        utf = codecs.getincrementaldecoder("utf-8")("ignore")
        buf = ""
        fd = self.proc.stdout.fileno()
        while self.alive:
            try:
                chunk = os.read(fd, 8192)
            except OSError:
                break
            if not chunk:
                break
            buf += utf.decode(chunk)
            while True:
                buf = buf.lstrip()
                if not buf:
                    break
                if buf[0] != "{":
                    i = buf.find("{")
                    buf = buf[i:] if i >= 0 else ""
                    continue
                try:
                    obj, i = dec.raw_decode(buf)
                except json.JSONDecodeError:
                    break
                buf = buf[i:]
                if isinstance(obj, dict):
                    for k, v in obj.items():
                        if isinstance(v, dict) and isinstance(v.get("values"), list):
                            self._push(k, v["values"])

    def _demo(self):
        t0 = time.monotonic()
        while self.alive:
            t = time.monotonic() - t0
            for n in self.names:
                low = n.lower()
                if "accel" in low:
                    v = [0.9 * math.sin(t * 0.4) + 0.35 * math.sin(2 * math.pi * 24.5 * t)
                         + random.gauss(0, 0.03),
                         0.6 * math.cos(t * 0.3) + random.gauss(0, 0.03), 9.72]
                elif "magnet" in low:
                    bump = 35 * math.exp(-((t % 12) - 6) ** 2)
                    v = [22 * math.cos(t * 0.25) + bump, 22 * math.sin(t * 0.25), -40.0]
                elif "light" in low:
                    v = [320 + 180 * math.sin(t * 0.5) + random.gauss(0, 5)]
                else:
                    v = [math.sin(t), math.cos(t), math.sin(t * 0.5)]
                self._push(n, v)
            time.sleep(self.delay / 1000)

    def get(self, name):
        with self.lock:
            v = self.latest.get(name)
            return list(v) if v else None

    def history(self, name):
        with self.lock:
            return [(t, v) for t, n, v in self.hist if n == name] if self.hist else []

    def stop(self):
        self.alive = False
        if self.proc:
            try:
                self.proc.terminate()
                self.proc.wait(timeout=2)
            except Exception:
                pass
            subprocess.run(["termux-sensor", "-c"], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL)


def wifi_scan():
    if DEMO:
        base = [("HomeNet", "aa:bb:cc:00:00:01", -42, 2437), ("Moraia-Church", "aa:bb:cc:00:00:02", -58, 5180),
                ("Spark-5G-1F2A", "aa:bb:cc:00:00:03", -67, 5240), ("NEIGHBOUR_2.4", "aa:bb:cc:00:00:04", -74, 2412),
                ("PrinterDirect", "aa:bb:cc:00:00:05", -81, 2462), ("", "aa:bb:cc:00:00:06", -88, 2437)]
        return [{"ssid": s, "bssid": b, "rssi": r + random.randint(-3, 3), "frequency_mhz": f}
                for s, b, r, f in base]
    r = run_json(["termux-wifi-scaninfo"], 25)
    if isinstance(r, list):
        return r
    if isinstance(r, dict):
        return str(r.get("API_ERROR") or r)
    return None


def get_location():
    if DEMO:
        time.sleep(1)
        return {"latitude": -43.5225 + random.uniform(-1e-4, 1e-4),
                "longitude": 172.5960 + random.uniform(-1e-4, 1e-4), "accuracy": 6.0}
    for provider, to in (("gps", 60), ("network", 25)):
        d = run_json(["termux-location", "-p", provider, "-r", "once"], to)
        if isinstance(d, dict) and "latitude" in d:
            return d
    return None


def take_photo(path):
    if DEMO:
        return False
    try:
        subprocess.run(["termux-camera-photo", "-c", "0", path], timeout=30,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except (subprocess.TimeoutExpired, OSError):
        return False
    return os.path.exists(path)


def data_dir():
    shared = os.path.expanduser("~/storage/shared")
    base = os.path.join(shared, "CyberToolkit") if os.path.isdir(shared) and not DEMO \
        else os.path.expanduser("~/CyberToolkit")
    os.makedirs(base, exist_ok=True)
    return base

# ═══════════════════════════════════ UI HELPERS ═══════════════════════════════════


C_G, C_C, C_M, C_Y, C_R, C_B = range(1, 7)
SPARK = "▁▂▃▄▅▆▇█"


def init_colors():
    curses.start_color()
    try:
        curses.use_default_colors()
        bg = -1
    except curses.error:
        bg = curses.COLOR_BLACK
    for i, c in ((C_G, curses.COLOR_GREEN), (C_C, curses.COLOR_CYAN), (C_M, curses.COLOR_MAGENTA),
                 (C_Y, curses.COLOR_YELLOW), (C_R, curses.COLOR_RED), (C_B, curses.COLOR_BLUE)):
        curses.init_pair(i, c, bg)


def col(i, bold=False):
    return curses.color_pair(i) | (curses.A_BOLD if bold else 0)


def put(win, y, x, s, attr=0):
    h, w = win.getmaxyx()
    if y < 0 or y >= h or x >= w:
        return
    s = str(s)
    if x < 0:
        s, x = s[-x:], 0
    s = s[:max(0, w - x - (1 if y == h - 1 else 0))]
    if s:
        try:
            win.addstr(y, x, s, attr)
        except curses.error:
            pass


def center(win, y, s, attr=0):
    w = win.getmaxyx()[1]
    put(win, y, max(0, (w - len(s)) // 2), s, attr)


def header(win, title):
    w = win.getmaxyx()[1]
    put(win, 0, 0, (" ▓ " + APP + " ▸ " + title).ljust(w), col(C_M, True) | curses.A_REVERSE)


def footer(win, hint):
    h, w = win.getmaxyx()
    put(win, h - 1, 0, (" ◀ q back │ " + hint).ljust(w), col(C_C) | curses.A_REVERSE)


def bar(win, y, x, width, frac, color):
    frac = max(0.0, min(1.0, frac))
    n = int(round(frac * width))
    put(win, y, x, "█" * n, col(color, True))
    put(win, y, x + n, "░" * (width - n), col(C_B))


def sparkline(values, width, lo=None, hi=None):
    vals = list(values)[-width:]
    if not vals:
        return ""
    lo = min(vals) if lo is None else lo
    hi = max(vals) if hi is None else hi
    span = (hi - lo) or 1
    return "".join(SPARK[max(0, min(7, int((v - lo) / span * 7.999)))] for v in vals)


def mouse_row(k):
    if k != curses.KEY_MOUSE:
        return None
    try:
        _, mx, my, _, bstate = curses.getmouse()
        return my, mx
    except curses.error:
        return None


def run_screen(scr, draw, keys=None, interval=100):
    """Generic screen loop. draw() paints, keys(k) handles input."""
    scr.timeout(interval)
    while True:
        scr.erase()
        draw()
        scr.refresh()
        k = scr.getch()
        if k in (ord("q"), ord("Q"), 27):
            return
        pos = mouse_row(k)
        if pos and pos[0] == scr.getmaxyx()[0] - 1:
            return
        if keys and k != -1:
            keys(k)


def missing(scr, title, what):
    def draw():
        header(scr, title)
        center(scr, 3, f"No {what} found on this phone.", col(C_R, True))
        center(scr, 5, "Check Termux:API is installed and", col(C_C))
        center(scr, 6, "run:  termux-sensor -l", col(C_C))
        footer(scr, "")
    run_screen(scr, draw, interval=500)


def prompt(scr, y, label, maxlen=120):
    """Simple line input."""
    scr.timeout(-1)
    curses.echo()
    try:
        curses.curs_set(1)
    except curses.error:
        pass
    put(scr, y, 0, label, col(C_Y, True))
    scr.refresh()
    try:
        raw = scr.getstr(y, min(len(label), scr.getmaxyx()[1] - 2), maxlen)
        text = raw.decode("utf-8", "ignore").strip()
    except curses.error:
        text = ""
    curses.noecho()
    try:
        curses.curs_set(0)
    except curses.error:
        pass
    return text

# ═══════════════════════════════════ TOOLS ═══════════════════════════════════


def est_distance(rssi, freq):
    """Log-distance path loss estimate (metres). Rough: walls and antennas change it a lot."""
    ref = -40.0 if (freq or 2400) < 3000 else -47.0
    return 10 ** ((ref - rssi) / (10 * 2.7))


def tool_wifi(scr):
    st = {"aps": [], "err": None, "t": 0.0, "scanning": False}
    stop, wake = threading.Event(), threading.Event()

    def scanner():
        while not stop.is_set():
            st["scanning"] = True
            r = wifi_scan()
            if isinstance(r, list):
                st["aps"], st["err"], st["t"] = r, None, time.time()
            else:
                st["err"] = r or "No data: turn on Location and allow Termux:API location"
            st["scanning"] = False
            wake.wait(10)
            wake.clear()

    threading.Thread(target=scanner, daemon=True).start()
    labels = "123456789ABCDEFGHJKLMNPRSTUVWXYZ"

    def angle_of(bssid):
        return int(hashlib.md5(bssid.encode()).hexdigest()[:4], 16) % 360

    def draw():
        h, w = scr.getmaxyx()
        header(scr, "WI-FI RADAR")
        aps = sorted(st["aps"], key=lambda a: -a.get("rssi", -100))[:len(labels)]
        R = max(3, min((w - 2) // 4, (h - 6) // 3))
        cx, cy = w // 2, 1 + R + 1
        sweep = (time.monotonic() * 120) % 360
        for frac in (1 / 3, 2 / 3, 1.0):
            r = R * frac
            for a in range(0, 360, 4 if frac == 1.0 else 8):
                put(scr, cy + round(r * math.sin(math.radians(a))),
                    cx + round(2 * r * math.cos(math.radians(a))), "·", col(C_G))
        for k in range(1, R + 1):
            for trail, attr in ((0, col(C_G, True)), (8, col(C_G)), (16, col(C_B))):
                a = math.radians(sweep - trail)
                put(scr, cy + round(k * math.sin(a)), cx + round(2 * k * math.cos(a)),
                    "•" if trail == 0 else "∙", attr)
        put(scr, cy, cx, "◉", col(C_C, True))
        for i, ap in enumerate(aps):
            rssi = ap.get("rssi", -100)
            d = est_distance(rssi, ap.get("frequency_mhz"))
            frac = min(1.0, math.log10(1 + d) / math.log10(1 + 60))
            ang = angle_of(ap.get("bssid", str(i)))
            r = max(1.0, frac * R)
            color = C_G if rssi > -60 else C_Y if rssi > -75 else C_R
            lit = abs(((sweep - ang + 540) % 360) - 180) < 25
            put(scr, cy + round(r * math.sin(math.radians(ang))),
                cx + round(2 * r * math.cos(math.radians(ang))), labels[i],
                col(color, True) | (curses.A_REVERSE if lit else 0))
        y = cy + R + 2
        if st["err"]:
            put(scr, y, 1, st["err"][:w - 2], col(C_R, True))
            y += 1
        age = f"{time.time() - st['t']:.0f}s ago" if st["t"] else "waiting…"
        put(scr, y, 1, f"{len(st['aps'])} networks · scan {age}" +
            (" · scanning" if st["scanning"] else ""), col(C_C))
        y += 1
        nw = max(6, w - 24)
        for i, ap in enumerate(aps):
            if y >= h - 1:
                break
            rssi = ap.get("rssi", -100)
            f = ap.get("frequency_mhz") or 0
            band = "5G " if f >= 5000 else "2.4" if f else "?  "
            ssid = ap.get("ssid") or "<hidden>"
            color = C_G if rssi > -60 else C_Y if rssi > -75 else C_R
            put(scr, y, 1, f"{labels[i]} {ssid[:nw]:<{nw}} {band} {rssi:>4}dBm "
                f"≈{est_distance(rssi, f):>4.0f}m", col(color))
            y += 1
        footer(scr, "r rescan · distance ≈ · direction not measured")

    def keys(k):
        if k in (ord("r"), ord("R")):
            wake.set()

    try:
        run_screen(scr, draw, keys, interval=80)
    finally:
        stop.set()
        wake.set()


def compute_heading(a, m):
    ax, ay, az = a[:3]
    ex, ey, ez = m[:3]
    hx, hy, hz = ey * az - ez * ay, ez * ax - ex * az, ex * ay - ey * ax
    nh = math.sqrt(hx * hx + hy * hy + hz * hz)
    if nh < 0.1:
        return None
    hx, hy, hz = hx / nh, hy / nh, hz / nh
    na = math.sqrt(ax * ax + ay * ay + az * az) or 1
    ax, az = ax / na, az / na
    my = az * hx - ax * hz
    return (math.degrees(math.atan2(hy, my)) + 360) % 360


def tool_compass(scr):
    an, mn = accel_name(), mag_name()
    if not an or not mn:
        return missing(scr, "COMPASS", "accelerometer/magnetometer")
    s = SensorStream([an, mn], 60)
    sm = {"x": 0.0, "y": 0.0}
    names = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]

    def draw():
        h, w = scr.getmaxyx()
        header(scr, "COMPASS")
        a, m = s.get(an), s.get(mn)
        if not a or not m:
            center(scr, 3, "Waiting for sensors…", col(C_Y))
            footer(scr, "")
            return
        hd = compute_heading(a, m)
        if hd is None:
            footer(scr, "")
            return
        sm["x"] = 0.8 * sm["x"] + 0.2 * math.cos(math.radians(hd))
        sm["y"] = 0.8 * sm["y"] + 0.2 * math.sin(math.radians(hd))
        hd = (math.degrees(math.atan2(sm["y"], sm["x"])) + 360) % 360
        card = names[int((hd + 22.5) // 45) % 8]
        center(scr, 2, f"{hd:05.1f}°  {card}", col(C_G, True))
        mid = w // 2
        tape_y = 4
        for c in range(w):
            d = (hd + (c - mid) * 3) % 360
            if abs(((d + 7.5) % 15) - 7.5) < 1.5:
                put(scr, tape_y + 1, c, "|", col(C_C))
            else:
                put(scr, tape_y + 1, c, "·", col(C_B))
        for i, n in enumerate(names):
            off = ((i * 45 - hd + 540) % 360) - 180
            c = mid + int(off / 3)
            if 0 <= c < w:
                put(scr, tape_y, c - len(n) // 2, n, col(C_R if n == "N" else C_C, True))
        put(scr, tape_y + 2, mid, "▲", col(C_M, True))
        field = math.sqrt(sum(v * v for v in m[:3]))
        ok = 20 <= field <= 70
        put(scr, tape_y + 4, 2, f"Field {field:5.1f} µT ", col(C_C))
        put(scr, tape_y + 4, 18, "OK" if ok else "INTERFERENCE – move away from metal",
            col(C_G if ok else C_R, True))
        put(scr, tape_y + 6, 2, "Magnetic heading (NZ declination ≈ +23°E,", col(C_B))
        put(scr, tape_y + 7, 2, "so true = magnetic + ~23°). Calibrate: figure-8.", col(C_B))
        put(scr, tape_y + 9, 2, f"True ≈ {(hd + 23) % 360:05.1f}°", col(C_Y, True))
        footer(scr, "hold phone flat, top edge forward")

    try:
        run_screen(scr, draw, interval=80)
    finally:
        s.stop()


def tool_level(scr):
    an = accel_name()
    if not an:
        return missing(scr, "LEVEL", "accelerometer")
    s = SensorStream([an], 50)
    st = {"ref": None, "f": None}

    def readings():
        a = s.get(an)
        if not a:
            return None
        f = st["f"] or a[:3]
        f = [0.85 * f[i] + 0.15 * a[i] for i in range(3)]
        st["f"] = f
        ax, ay, az = f
        tx = math.degrees(math.atan2(ax, math.hypot(ay, az)))
        ty = math.degrees(math.atan2(ay, math.hypot(ax, az)))
        if abs(az) >= max(abs(ax), abs(ay)):
            mode, ang = "FLAT", math.hypot(tx, ty)
        elif abs(ax) > abs(ay):
            mode, ang = "LANDSCAPE EDGE", math.degrees(math.atan2(ay, abs(ax)))
        else:
            mode, ang = "PORTRAIT EDGE", math.degrees(math.atan2(ax, abs(ay)))
        return f, tx, ty, mode, ang

    def draw():
        h, w = scr.getmaxyx()
        header(scr, "LEVEL / ANGLE")
        r = readings()
        if not r:
            center(scr, 3, "Waiting for accelerometer…", col(C_Y))
            footer(scr, "")
            return
        (ax, ay, az), tx, ty, mode, ang = r
        rel = ang - st["ref"] if st["ref"] is not None else ang
        level = abs(rel) < 0.5
        put(scr, 2, 2, f"Mode: {mode}", col(C_C, True))
        center(scr, 4, f"{rel:+.1f}°" + ("  (relative)" if st["ref"] is not None else ""),
               col(C_G if level else C_Y, True))
        center(scr, 5, "◆ LEVEL ◆" if level else " ", col(C_G, True))
        if mode == "FLAT":
            put(scr, 6, 2, f"X tilt {tx:+5.1f}°   Y tilt {ty:+5.1f}°", col(C_C))
            bw = min(w - 4, 41) | 1
            bh = max(5, min(h - 11, 13)) | 1
            x0, y0 = (w - bw) // 2, 8
            put(scr, y0, x0, "┌" + "─" * (bw - 2) + "┐", col(C_B))
            for yy in range(1, bh - 1):
                put(scr, y0 + yy, x0, "│", col(C_B))
                put(scr, y0 + yy, x0 + bw - 1, "│", col(C_B))
            put(scr, y0 + bh - 1, x0, "└" + "─" * (bw - 2) + "┘", col(C_B))
            cxx, cyy = x0 + bw // 2, y0 + bh // 2
            put(scr, cyy, cxx - 2, "+   +", col(C_B))
            gx = max(-1, min(1, ax / (G * 0.25)))
            gy = max(-1, min(1, ay / (G * 0.25)))
            bx = cxx + int(round(gx * (bw // 2 - 2)))
            by = cyy - int(round(gy * (bh // 2 - 1)))
            put(scr, by, bx, "●", col(C_G if level else C_Y, True))
        else:
            bw = min(w - 4, 50)
            x0 = (w - bw) // 2
            put(scr, 8, x0, "─" * bw, col(C_B))
            pos = x0 + bw // 2 + int(max(-1, min(1, rel / 10)) * (bw // 2 - 1))
            put(scr, 8, x0 + bw // 2, "┼", col(C_C))
            put(scr, 8, pos, "●", col(C_G if level else C_Y, True))
            put(scr, 10, 2, "Stand phone on its edge on the pipe,", col(C_B))
            put(scr, 11, 2, "tray or surface. Scale = ±10°.", col(C_B))
            put(scr, 12, 2, f"Slope ≈ {math.tan(math.radians(rel)) * 100:+.1f}%  "
                f"(1 in {abs(1 / math.tan(math.radians(rel))):.0f})" if abs(rel) > 0.05
                else "Slope ≈ 0%", col(C_C))
        footer(scr, "z zero here · c clear zero")

    def keys(k):
        if k in (ord("z"), ord("Z")):
            r = readings()
            if r:
                st["ref"] = r[4]
        elif k in (ord("c"), ord("C")):
            st["ref"] = None

    try:
        run_screen(scr, draw, keys, interval=60)
    finally:
        s.stop()


def tool_metal(scr):
    mn = mag_name()
    if not mn:
        return missing(scr, "METAL DETECTOR", "magnetometer")
    s = SensorStream([mn], 40)
    st = {"base": None, "thr": 8.0, "hist": deque(maxlen=200), "last_buzz": 0.0,
          "t0": time.monotonic(), "acc": []}

    def draw():
        h, w = scr.getmaxyx()
        header(scr, "METAL DETECTOR")
        m = s.get(mn)
        if not m:
            center(scr, 3, "Waiting for magnetometer…", col(C_Y))
            footer(scr, "")
            return
        mag = math.sqrt(sum(v * v for v in m[:3]))
        if st["base"] is None:
            st["acc"].append(mag)
            center(scr, 3, "Calibrating – keep away from metal…", col(C_Y, True))
            if time.monotonic() - st["t0"] > 1.5 and st["acc"]:
                st["base"] = sum(st["acc"]) / len(st["acc"])
            footer(scr, "")
            return
        delta = abs(mag - st["base"])
        st["hist"].append(delta)
        hit = delta > st["thr"]
        now = time.monotonic()
        if hit and now - st["last_buzz"] > max(0.12, 0.8 - delta / 80):
            vibrate(50)
            st["last_buzz"] = now
        center(scr, 2, f"{delta:6.1f} µT", col(C_R if hit else C_G, True))
        center(scr, 3, "▲ METAL / MAGNET DETECTED ▲" if hit else "scanning…",
               col(C_R if hit else C_C, True))
        bar(scr, 5, 2, w - 4, delta / max(st["thr"] * 4, 1), C_R if hit else C_G)
        put(scr, 7, 2, f"Field {mag:6.1f} µT   baseline {st['base']:6.1f}   "
            f"alert > {st['thr']:.0f} µT", col(C_C))
        put(scr, 9, 2, sparkline(st["hist"], w - 4, 0, max(st["thr"] * 3, max(st["hist"]))),
            col(C_M))
        put(scr, 11, 2, "Sensor is usually near the top of the phone.", col(C_B))
        put(scr, 12, 2, "Finds steel: screws, studs, rebar near surface.", col(C_B))
        put(scr, 13, 2, "Does NOT detect live wires – use a proper tester.", col(C_Y))
        footer(scr, "c recalibrate · +/- sensitivity")

    def keys(k):
        if k in (ord("c"), ord("C")):
            st["base"], st["acc"], st["t0"] = None, [], time.monotonic()
        elif k in (ord("+"), ord("=")):
            st["thr"] = max(1.0, st["thr"] - 1)
        elif k in (ord("-"), ord("_")):
            st["thr"] = min(60.0, st["thr"] + 1)

    try:
        run_screen(scr, draw, keys, interval=50)
    finally:
        s.stop()


_dft_cache = {}


def spectrum(samples):
    """Hann-windowed DFT; returns amplitude per bin 1..n/2-1."""
    n = len(samples)
    if n not in _dft_cache:
        win = [0.5 - 0.5 * math.cos(2 * math.pi * i / (n - 1)) for i in range(n)]
        cs = [math.cos(2 * math.pi * i / n) for i in range(n)]
        sn = [math.sin(2 * math.pi * i / n) for i in range(n)]
        _dft_cache[n] = (win, cs, sn, sum(win))
    win, cs, sn, wsum = _dft_cache[n]
    mean = sum(samples) / n
    x = [(v - mean) * win[i] for i, v in enumerate(samples)]
    out = []
    for k in range(1, n // 2):
        re = im = 0.0
        for i, v in enumerate(x):
            j = (k * i) % n
            re += v * cs[j]
            im -= v * sn[j]
        out.append(2 * math.sqrt(re * re + im * im) / wsum)
    return out


def tool_vibration(scr):
    an = accel_name()
    if not an:
        return missing(scr, "VIBRATION", "accelerometer")
    s = SensorStream([an], 5, history=600)
    st = {"hold": False, "last": None}

    def analyse():
        hist = s.history(an)
        if len(hist) < 64:
            return None
        n = 256 if len(hist) >= 256 else 128 if len(hist) >= 128 else 64
        seg = hist[-n:]
        dur = seg[-1][0] - seg[0][0]
        if dur <= 0:
            return None
        fs = (n - 1) / dur
        mags = [math.sqrt(sum(c * c for c in v[:3])) for _, v in seg]
        mean = sum(mags) / n
        rms = math.sqrt(sum((m - mean) ** 2 for m in mags) / n)
        p2p = max(mags) - min(mags)
        spec = spectrum(mags)
        k = max(range(len(spec)), key=lambda i: spec[i])
        return {"fs": fs, "n": n, "rms": rms, "p2p": p2p, "spec": spec,
                "pk_f": (k + 1) * fs / n, "pk_a": spec[k]}

    def draw():
        h, w = scr.getmaxyx()
        header(scr, "VIBRATION METER" + ("  [HOLD]" if st["hold"] else ""))
        r = st["last"] if st["hold"] else analyse()
        if not st["hold"]:
            st["last"] = r
        if not r:
            center(scr, 3, "Collecting samples… place phone on machine", col(C_Y))
            footer(scr, "")
            return
        sev = C_G if r["rms"] < 0.3 else C_Y if r["rms"] < 1.0 else C_R
        put(scr, 2, 2, f"RMS  {r['rms']:6.3f} m/s²  ({r['rms'] / G * 1000:5.1f} mg)",
            col(sev, True))
        put(scr, 3, 2, f"P-P  {r['p2p']:6.3f} m/s²", col(C_C))
        put(scr, 4, 2, f"Peak {r['pk_f']:5.1f} Hz  = {r['pk_f'] * 60:6.0f} RPM", col(C_M, True))
        put(scr, 5, 2, f"Rate {r['fs']:5.1f} Hz · max readable ≈ {r['fs'] / 2:4.1f} Hz",
            col(C_B))
        spec = r["spec"]
        gh = max(4, h - 10)
        gw = min(len(spec), w - 4)
        step = len(spec) / gw
        cols = [max(spec[int(i * step):max(int((i + 1) * step), int(i * step) + 1)])
                for i in range(gw)]
        top = max(cols) or 1
        for i, v in enumerate(cols):
            hgt = v / top * gh
            for j in range(int(hgt)):
                put(scr, 7 + gh - 1 - j, 2 + i, "█", col(C_G if j < gh * 0.5 else C_Y))
            frac = hgt - int(hgt)
            if frac > 0.1 and int(hgt) < gh:
                put(scr, 7 + gh - 1 - int(hgt), 2 + i, SPARK[int(frac * 7)], col(C_G))
        put(scr, 7 + gh, 2, "0 Hz", col(C_B))
        lbl = f"{r['fs'] / 2:.0f} Hz"
        put(scr, 7 + gh, 2 + gw - len(lbl), lbl, col(C_B))
        footer(scr, "h hold · indicative only")

    def keys(k):
        if k in (ord("h"), ord("H"), ord(" ")):
            st["hold"] = not st["hold"]

    try:
        run_screen(scr, draw, keys, interval=150)
    finally:
        s.stop()


def tool_light(scr):
    ln = light_name()
    if not ln:
        return missing(scr, "LIGHT METER", "light sensor")
    s = SensorStream([ln], 200)
    st = {"min": None, "max": None, "sum": 0.0, "n": 0, "hist": deque(maxlen=300)}
    guide = [("Corridor / storage", 100), ("General office", 320),
             ("Workshop bench", 500), ("Fine detail work", 800)]

    def draw():
        h, w = scr.getmaxyx()
        header(scr, "LIGHT METER")
        v = s.get(ln)
        if not v:
            center(scr, 3, "Waiting for light sensor…", col(C_Y))
            footer(scr, "")
            return
        lux = float(v[0])
        st["hist"].append(lux)
        st["min"] = lux if st["min"] is None else min(st["min"], lux)
        st["max"] = lux if st["max"] is None else max(st["max"], lux)
        st["sum"] += lux
        st["n"] += 1
        center(scr, 2, f"{lux:,.0f} lux", col(C_G, True))
        bar(scr, 4, 2, w - 4, math.log10(1 + lux) / 5, C_G)
        put(scr, 6, 2, f"min {st['min']:,.0f}   max {st['max']:,.0f}   "
            f"avg {st['sum'] / st['n']:,.0f}", col(C_C))
        put(scr, 8, 2, sparkline(st["hist"], w - 4), col(C_M))
        put(scr, 10, 2, "Rough guide (check AS/NZS 1680):", col(C_B))
        for i, (name, req) in enumerate(guide):
            ok = lux >= req
            put(scr, 11 + i, 4, f"{'✓' if ok else '·'} {name:<20} ~{req} lux",
                col(C_G if ok else C_B))
        put(scr, 16, 2, "Sensor is beside the front camera.", col(C_B))
        footer(scr, "r reset stats · phone readings ±20% or more")

    def keys(k):
        if k in (ord("r"), ord("R")):
            st.update({"min": None, "max": None, "sum": 0.0, "n": 0})
            st["hist"].clear()

    try:
        run_screen(scr, draw, keys, interval=200)
    finally:
        s.stop()


def tool_alarm(scr):
    an = accel_name()
    if not an:
        return missing(scr, "MOTION ALARM", "accelerometer")
    s = SensorStream([an], 50)
    st = {"state": "IDLE", "t": 0.0, "base": None, "acc": [], "thr": 0.6,
          "events": deque(maxlen=8), "last": 0.0, "voice": True, "peak": 0.0}

    def draw():
        h, w = scr.getmaxyx()
        header(scr, "MOTION ALARM")
        a = s.get(an)
        now = time.monotonic()
        state = st["state"]
        if state == "COUNTDOWN":
            left = 5 - (now - st["t"])
            if left <= 0:
                st["state"], st["t"], st["acc"] = "SETTLING", now, []
            center(scr, 3, f"Arming in {max(0, left):.0f}s – put the phone down", col(C_Y, True))
        elif state == "SETTLING":
            if a:
                st["acc"].append(a[:3])
            center(scr, 3, "Learning resting position…", col(C_Y, True))
            if now - st["t"] > 1.0 and st["acc"]:
                n = len(st["acc"])
                st["base"] = [sum(v[i] for v in st["acc"]) / n for i in range(3)]
                st["state"] = "ARMED"
        elif state == "ARMED" and a and st["base"]:
            d = math.sqrt(sum((a[i] - st["base"][i]) ** 2 for i in range(3)))
            st["peak"] = max(st["peak"] * 0.95, d)
            center(scr, 3, "● ARMED ●", col(C_R, True))
            bar(scr, 5, 2, w - 4, d / (st["thr"] * 2), C_R if d > st["thr"] else C_G)
            if d > st["thr"] and now - st["last"] > 5:
                st["last"] = now
                stamp = datetime.now().strftime("%H:%M:%S")
                st["events"].appendleft(f"{stamp}  movement {d:.2f} m/s²")
                vibrate(800)
                notify("⚠ CYBER//TOOLKIT", f"Motion detected at {stamp}")
                if st["voice"]:
                    speak("Motion detected")
        else:
            center(scr, 3, "DISARMED", col(C_C, True))
        put(scr, 7, 2, f"Sensitivity: trigger > {st['thr']:.2f} m/s²", col(C_C))
        put(scr, 8, 2, f"Voice alert: {'ON' if st['voice'] else 'OFF'}", col(C_C))
        put(scr, 10, 2, "Events:", col(C_M, True))
        for i, e in enumerate(st["events"]):
            put(scr, 11 + i, 4, e, col(C_Y))
        if not st["events"]:
            put(scr, 11, 4, "none", col(C_B))
        footer(scr, "a arm/disarm · +/- sensitivity · v voice")

    def keys(k):
        if k in (ord("a"), ord("A")):
            if st["state"] == "IDLE":
                st["state"], st["t"] = "COUNTDOWN", time.monotonic()
                fire(["termux-wake-lock"])
            else:
                st["state"] = "IDLE"
                fire(["termux-wake-unlock"])
        elif k in (ord("+"), ord("=")):
            st["thr"] = max(0.1, st["thr"] - 0.1)
        elif k in (ord("-"), ord("_")):
            st["thr"] = min(5.0, st["thr"] + 0.1)
        elif k in (ord("v"), ord("V")):
            st["voice"] = not st["voice"]

    try:
        run_screen(scr, draw, keys, interval=50)
    finally:
        s.stop()
        fire(["termux-wake-unlock"])


def tool_logger(scr):
    folder = data_dir()
    path = os.path.join(folder, "site_log.csv")
    cols = ["timestamp", "latitude", "longitude", "accuracy_m", "note", "photo"]

    def load():
        if not os.path.exists(path):
            return []
        with open(path, newline="", encoding="utf-8") as f:
            return list(csv.DictReader(f))

    st = {"rows": load(), "msg": ""}

    def draw():
        h, w = scr.getmaxyx()
        header(scr, "SITE LOGGER")
        put(scr, 2, 2, f"Saving to: {path}"[:w - 3], col(C_B))
        put(scr, 3, 2, f"{len(st['rows'])} entries", col(C_C))
        if st["msg"]:
            put(scr, 4, 2, st["msg"][:w - 3], col(C_G, True))
        y = 6
        for r in reversed(st["rows"][-(h - 8):]):
            if y >= h - 1:
                break
            put(scr, y, 2, f"{r['timestamp'][5:16]} {r['note']}"[:w - 3], col(C_C))
            y += 1
        footer(scr, "n new entry (GPS + note + photo)")

    def new_entry():
        h, w = scr.getmaxyx()
        scr.erase()
        header(scr, "SITE LOGGER ▸ NEW")
        put(scr, 2, 2, "Getting location fix (up to 60s)…", col(C_Y, True))
        scr.refresh()
        loc = get_location() or {}
        lat, lon = loc.get("latitude", ""), loc.get("longitude", "")
        acc = loc.get("accuracy", "")
        put(scr, 2, 2, (f"Location: {lat:.6f}, {lon:.6f} ±{acc:.0f} m" if lat != ""
                        else "No location fix – saving without GPS").ljust(w - 4),
            col(C_G if lat != "" else C_R))
        note = prompt(scr, 4, "Note: ")
        put(scr, 6, 2, "Take photo? (y/n) ", col(C_Y, True))
        scr.refresh()
        scr.timeout(-1)
        photo = ""
        if scr.getch() in (ord("y"), ord("Y")):
            stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            p = os.path.join(folder, f"site_{stamp}.jpg")
            put(scr, 7, 2, "Capturing…", col(C_C))
            scr.refresh()
            photo = p if take_photo(p) else ""
        row = {"timestamp": datetime.now().isoformat(timespec="seconds"),
               "latitude": lat, "longitude": lon, "accuracy_m": acc,
               "note": note, "photo": os.path.basename(photo)}
        new_file = not os.path.exists(path)
        with open(path, "a", newline="", encoding="utf-8") as f:
            wr = csv.DictWriter(f, fieldnames=cols)
            if new_file:
                wr.writeheader()
            wr.writerow(row)
        st["rows"].append(row)
        st["msg"] = "Saved ✓" + (" with photo" if photo else "")
        scr.timeout(200)

    def keys(k):
        if k in (ord("n"), ord("N")):
            new_entry()

    run_screen(scr, draw, keys, interval=200)


def tool_explorer(scr):
    sensors = list_sensors()
    if not sensors:
        return missing(scr, "SENSOR EXPLORER", "sensors")
    st = {"sel": 0, "top": 0}

    def view(name):
        s = SensorStream([name], 100)
        hist = deque(maxlen=200)

        def draw():
            h, w = scr.getmaxyx()
            header(scr, "LIVE")
            put(scr, 2, 2, name[:w - 3], col(C_M, True))
            v = s.get(name)
            if not v:
                put(scr, 4, 2, "Waiting for data… (some sensors only report on change)",
                    col(C_Y))
            else:
                mag = math.sqrt(sum(x * x for x in v[:3]))
                hist.append(mag)
                for i, x in enumerate(v[:10]):
                    lbl = "xyz"[i] if i < 3 else str(i)
                    put(scr, 4 + i, 2, f"{lbl}: {x:12.4f}", col(C_G))
                put(scr, 15, 2, f"|v| {mag:.4f}", col(C_C))
                put(scr, 16, 2, sparkline(hist, w - 4), col(C_M))
            footer(scr, "")
        try:
            run_screen(scr, draw, interval=100)
        finally:
            s.stop()

    def draw():
        h, w = scr.getmaxyx()
        header(scr, f"SENSORS ({len(sensors)})")
        rows = h - 3
        if st["sel"] < st["top"]:
            st["top"] = st["sel"]
        if st["sel"] >= st["top"] + rows:
            st["top"] = st["sel"] - rows + 1
        for i in range(rows):
            idx = st["top"] + i
            if idx >= len(sensors):
                break
            sel = idx == st["sel"]
            put(scr, 2 + i, 1, (("▶ " if sel else "  ") + sensors[idx])[:w - 2],
                col(C_G, True) | (curses.A_REVERSE if sel else 0) if sel else col(C_C))
        footer(scr, "↑↓ select · Enter / tap open")

    def keys(k):
        if k == curses.KEY_UP:
            st["sel"] = max(0, st["sel"] - 1)
        elif k == curses.KEY_DOWN:
            st["sel"] = min(len(sensors) - 1, st["sel"] + 1)
        elif k in (10, 13, curses.KEY_ENTER):
            view(sensors[st["sel"]])
        else:
            pos = mouse_row(k)
            if pos and pos[0] >= 2:
                idx = st["top"] + pos[0] - 2
                if idx < len(sensors):
                    st["sel"] = idx
                    view(sensors[idx])

    run_screen(scr, draw, keys, interval=300)

# ═══════════════════════════════════ MAIN MENU ═══════════════════════════════════


TOOLS = [
    ("Wi-Fi Radar", "networks around you by signal", tool_wifi),
    ("Compass", "tilt-compensated heading", tool_compass),
    ("Level / Angle", "bubble, edge slope, zero", tool_level),
    ("Metal Detector", "steel, screws, studs", tool_metal),
    ("Vibration Meter", "RMS, Hz, RPM, spectrum", tool_vibration),
    ("Light Meter", "lux with min/max/avg", tool_light),
    ("Motion Alarm", "tamper alert + notify", tool_alarm),
    ("Site Logger", "GPS + note + photo → CSV", tool_logger),
    ("Sensor Explorer", "every sensor, live", tool_explorer),
]

LOGO = ["╔═╗╦ ╦╔╗ ╔═╗╦═╗", "║  ╚╦╝╠╩╗║╣ ╠╦╝", "╚═╝ ╩ ╚═╝╚═╝╩╚═"]


def main(scr):
    curses.curs_set(0)
    init_colors()
    curses.mousemask(curses.ALL_MOUSE_EVENTS | getattr(curses, "REPORT_MOUSE_POSITION", 0))
    scr.keypad(True)
    sel = 0
    first_row = 7
    while True:
        h, w = scr.getmaxyx()
        scr.erase()
        t = time.monotonic()
        for i, line in enumerate(LOGO):
            center(scr, i + 1, line, col(C_M if (int(t * 2) + i) % 3 else C_C, True))
        center(scr, 4, "// T O O L K I T  v" + VERSION + (" · DEMO" if DEMO else ""),
               col(C_G, True))
        center(scr, 5, "─" * min(w - 2, 40), col(C_B))
        for i, (name, desc, _) in enumerate(TOOLS):
            y = first_row + i
            s = i == sel
            put(scr, y, 1, f"{'▶' if s else ' '} {i + 1} {name:<16}",
                col(C_G, True) | (curses.A_REVERSE if s else 0))
            put(scr, y, 22, desc[:max(0, w - 23)], col(C_C if s else C_B))
        put(scr, h - 1, 0, " 1-9 / tap / ↑↓ Enter · q quit ".ljust(w),
            col(C_C) | curses.A_REVERSE)
        scr.refresh()
        scr.timeout(250)
        k = scr.getch()
        choice = None
        if k in (ord("q"), ord("Q"), 27):
            return
        if k == curses.KEY_UP:
            sel = (sel - 1) % len(TOOLS)
        elif k == curses.KEY_DOWN:
            sel = (sel + 1) % len(TOOLS)
        elif k in (10, 13, curses.KEY_ENTER):
            choice = sel
        elif ord("1") <= k <= ord("9"):
            choice = k - ord("1")
        else:
            pos = mouse_row(k)
            if pos and first_row <= pos[0] < first_row + len(TOOLS):
                choice = pos[0] - first_row
        if choice is not None and choice < len(TOOLS):
            sel = choice
            TOOLS[choice][2](scr)
            scr.timeout(250)


if __name__ == "__main__":
    if not DEMO and not shutil.which("termux-sensor"):
        print("\n  CYBER//TOOLKIT needs Termux:API.\n"
              "  1. Install the Termux:API app (same store as Termux, e.g. F-Droid)\n"
              "  2. pkg install python termux-api\n"
              "  3. python cyber_toolkit.py\n\n"
              "  Or preview without sensors:  python cyber_toolkit.py --demo\n")
        sys.exit(1)
    locale.setlocale(locale.LC_ALL, "")
    try:
        curses.wrapper(main)
    except KeyboardInterrupt:
        pass
    if not DEMO:
        subprocess.run(["termux-sensor", "-c"], stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL)
    print("CYBER//TOOLKIT closed.")
