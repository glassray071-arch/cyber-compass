#!/usr/bin/env python3
"""
CYBER//COMPASS - a neon, tilt-compensated terminal compass for Android.

Runs in Termux and reads the phone's accelerometer + magnetometer through
Termux:API (`termux-sensor`). No third-party Python packages needed.

    python cyber_compass.py            # live sensors
    python cyber_compass.py --demo     # simulated sensors (no phone needed)
    python cyber_compass.py --help

Keys:  q quit   m mark/clear bearing   t true/magnetic   c calibration tip
"""
import argparse
import json
import math
import os
import random
import select
import shutil
import subprocess
import sys
import threading
import time

VERSION = "1.0.0"

# --------------------------------------------------------------------------
# Colours (256-colour ANSI)
# --------------------------------------------------------------------------
PAL = {
    "cyan": 51, "aqua": 44, "teal": 37, "deep": 24, "faint": 237,
    "mag": 201, "pink": 213, "green": 46, "dgreen": 22, "yel": 226,
    "red": 196, "orange": 208, "grey": 244, "white": 255, "blue": 33,
    "sky": 87,
}
USE_COLOR = True


def fg(name):
    if not USE_COLOR or name is None:
        return ""
    return "\033[38;5;%dm" % PAL[name]


RESET = "\033[0m"

# --------------------------------------------------------------------------
# Maths: same method as Android SensorManager.getRotationMatrix/getOrientation
# --------------------------------------------------------------------------


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0])


def mag3(v):
    return math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])


def unit(v):
    n = mag3(v)
    if n < 1e-9:
        return None
    return (v[0] / n, v[1] / n, v[2] / n)


def orientation(acc, mag):
    """Return (heading_deg, pitch_deg, roll_deg) or None.

    Heading is the direction the TOP edge of the phone points, 0..360,
    measured clockwise from magnetic north. Tilt-compensated.
    """
    if acc is None or mag is None:
        return None
    A = unit(acc)
    H = unit(cross(mag, acc))           # East
    if A is None or H is None:
        return None
    M = cross(A, H)                     # North
    az = math.atan2(H[1], M[1])
    pitch = math.asin(max(-1.0, min(1.0, -A[1])))
    roll = math.atan2(-A[0], A[2])
    return (math.degrees(az) % 360.0, math.degrees(pitch), math.degrees(roll))


def angdiff(a, b):
    """Signed smallest difference a-b in degrees, -180..180."""
    return (a - b + 540.0) % 360.0 - 180.0


POINTS16 = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"]


def cardinal(deg):
    return POINTS16[int((deg % 360) / 22.5 + 0.5) % 16]


# --------------------------------------------------------------------------
# Sensor feeds
# --------------------------------------------------------------------------


class SensorFeed(threading.Thread):
    """Streams JSON from `termux-sensor` and keeps low-pass filtered vectors."""

    def __init__(self, delay_ms=60, alpha=0.18):
        super().__init__(daemon=True)
        self.delay_ms = delay_ms
        self.alpha = alpha
        self.lock = threading.Lock()
        self.acc = None
        self.mag = None
        self.names = {}
        self.samples = 0
        self.last = 0.0
        self.error = None
        self.proc = None
        self.stopping = False

    def _lp(self, old, new):
        if old is None:
            return tuple(new)
        a = self.alpha
        return tuple(o + a * (n - o) for o, n in zip(old, new))

    def _handle(self, obj):
        if not isinstance(obj, dict):
            return
        for name, data in obj.items():
            vals = data.get("values") if isinstance(data, dict) else None
            if not vals or len(vals) < 3:
                continue
            low = name.lower()
            if "linear" in low or "gravity" in low:
                continue
            if "accel" in low:
                kind = "acc"
            elif "magnet" in low:
                kind = "mag"
            else:
                continue
            # prefer calibrated sensors over "uncalibrated" ones
            cur = self.names.get(kind)
            if "uncalib" in low and cur and "uncalib" not in cur.lower():
                continue
            if cur and "uncalib" in cur.lower() and "uncalib" not in low:
                setattr(self, kind, None)   # switch to the calibrated one
            v = (float(vals[0]), float(vals[1]), float(vals[2]))
            with self.lock:
                self.names[kind] = name
                setattr(self, kind, self._lp(getattr(self, kind), v))
                self.samples += 1
                self.last = time.time()

    def run(self):
        cmd = ["termux-sensor", "-s", "accel,magnet", "-d", str(self.delay_ms)]
        try:
            self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE,
                                         stderr=subprocess.PIPE, text=True,
                                         bufsize=1)
        except FileNotFoundError:
            self.error = ("termux-sensor not found. Run: pkg install termux-api "
                          "and install the Termux:API app.")
            return
        dec = json.JSONDecoder()
        buf = ""
        for line in self.proc.stdout:
            buf += line
            while True:
                s = buf.lstrip()
                if not s:
                    buf = ""
                    break
                try:
                    obj, end = dec.raw_decode(s)
                except json.JSONDecodeError:
                    buf = s
                    break
                buf = s[end:]
                self._handle(obj)
        if not self.stopping:
            err = ""
            try:
                err = self.proc.stderr.read().strip()
            except Exception:
                pass
            self.error = "sensor stream ended" + (": " + err[:120] if err else "")

    def snapshot(self):
        with self.lock:
            return dict(acc=self.acc, mag=self.mag, names=dict(self.names),
                        samples=self.samples, last=self.last, error=self.error)

    def stop(self):
        self.stopping = True
        if self.proc and self.proc.poll() is None:
            try:
                self.proc.terminate()
                self.proc.wait(timeout=2)
            except Exception:
                try:
                    self.proc.kill()
                except Exception:
                    pass
        # release the sensors (termux-sensor can otherwise keep them busy)
        try:
            subprocess.run(["termux-sensor", "-c"], timeout=4,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception:
            pass


def synth_vectors(heading, pitch=0.0, roll=0.0, bh=22.0, bv=52.0):
    """Device-frame (acc, mag) for a phone at the given attitude.

    World frame is East-North-Up; field points north with vertical part bv.
    """
    h, p, r = map(math.radians, (heading, pitch, roll))
    # device axes in world frame: start flat (x=E, y=N, z=U), yaw by heading
    # (clockwise), then pitch about device x, then roll about device y.
    def rot(v, axis, ang):
        x, y, z = axis
        c, s = math.cos(ang), math.sin(ang)
        d = v[0] * x + v[1] * y + v[2] * z
        cr = cross(axis, v)
        return tuple(v[i] * c + cr[i] * s + axis[i] * d * (1 - c) for i in range(3))
    up = (0.0, 0.0, 1.0)
    X = rot((1.0, 0.0, 0.0), up, -h)
    Y = rot((0.0, 1.0, 0.0), up, -h)
    Z = up
    Y, Z = rot(Y, X, p), rot(Z, X, p)
    X, Z = rot(X, Y, r), rot(Z, Y, r)
    g = (0.0, 0.0, 9.81)          # accelerometer reads +g "up" at rest
    m = (0.0, bh, bv)
    dot = lambda a, b: a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    return ((dot(g, X), dot(g, Y), dot(g, Z)), (dot(m, X), dot(m, Y), dot(m, Z)))


class DemoFeed:
    """Fake sensors: a slow, wandering rotation with a little tilt and noise."""

    def __init__(self):
        self.t0 = time.time()
        self.samples = 0

    def start(self):
        pass

    def stop(self):
        pass

    def snapshot(self):
        t = time.time() - self.t0
        hdg = (t * 18 + 40 * math.sin(t * 0.45)) % 360
        pitch = 8 * math.sin(t * 0.6)
        roll = 6 * math.sin(t * 0.37 + 1)
        acc, mag = synth_vectors(hdg, pitch, roll)
        n = lambda v, k: tuple(x + random.uniform(-k, k) for x in v)
        self.samples += 1
        return dict(acc=n(acc, 0.03), mag=n(mag, 0.3),
                    names={"acc": "demo accelerometer", "mag": "demo magnetometer"},
                    samples=self.samples, last=time.time(), error=None)


# --------------------------------------------------------------------------
# Terminal handling
# --------------------------------------------------------------------------


class Terminal:
    def __init__(self, interactive=True):
        self.interactive = interactive and sys.stdin.isatty() and sys.stdout.isatty()
        self.fd = None
        self.old = None

    def __enter__(self):
        if self.interactive:
            import termios
            import tty
            self.fd = sys.stdin.fileno()
            self.old = termios.tcgetattr(self.fd)
            tty.setcbreak(self.fd)
            sys.stdout.write("\033[?1049h\033[?25l\033[2J")
            sys.stdout.flush()
        return self

    def __exit__(self, *exc):
        if self.interactive:
            import termios
            termios.tcsetattr(self.fd, termios.TCSADRAIN, self.old)
            sys.stdout.write(RESET + "\033[?25h\033[?1049l")
            sys.stdout.flush()

    def key(self):
        if not self.interactive:
            return None
        r, _, _ = select.select([sys.stdin], [], [], 0)
        if r:
            ch = os.read(self.fd, 1)
            return ch.decode(errors="ignore").lower()
        return None


# --------------------------------------------------------------------------
# Rendering
# --------------------------------------------------------------------------

BIG = {
    "0": ["█▀█", "█ █", "▀▀▀"], "1": [" ▀█", "  █", "  ▀"],
    "2": ["▀▀█", "█▀▀", "▀▀▀"], "3": ["▀▀█", " ▀█", "▀▀▀"],
    "4": ["█ █", "▀▀█", "  ▀"], "5": ["█▀▀", "▀▀█", "▀▀▀"],
    "6": ["█▀▀", "█▀█", "▀▀▀"], "7": ["▀▀█", "  █", "  ▀"],
    "8": ["█▀█", "█▀█", "▀▀▀"], "9": ["█▀█", "▀▀█", "▀▀▀"],
}


class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.ch = [[" "] * w for _ in range(h)]
        self.col = [[None] * w for _ in range(h)]

    def put(self, x, y, s, color=None):
        x, y = int(round(x)), int(round(y))
        if y < 0 or y >= self.h:
            return
        for i, c in enumerate(s):
            xi = x + i
            if 0 <= xi < self.w:
                self.ch[y][xi] = c
                self.col[y][xi] = color

    def center(self, y, s, color=None):
        self.put((self.w - len(s)) // 2, y, s, color)

    def segs(self, x, y, parts):
        """Write a list of (text, color) pieces starting at x."""
        for text, color in parts:
            self.put(x, y, text, color)
            x += len(text)

    def render(self):
        out = []
        for y in range(self.h):
            line, cur = [], "__none__"
            for x in range(self.w):
                c = self.col[y][x]
                if c != cur and USE_COLOR:
                    line.append(RESET if c is None else fg(c))
                    cur = c
                line.append(self.ch[y][x])
            if USE_COLOR:
                line.append(RESET)
            out.append("".join(line))
        return "\n".join(out)


def needle_char(screen_deg):
    a = screen_deg % 180
    if a < 22.5 or a >= 157.5:
        return "│"
    if a < 67.5:
        return "╱"
    if a < 112.5:
        return "─"
    return "╲"


def draw_dial(cv, cx, cy, rx, ry, hdg, mark, t):
    def pt(bearing, r):
        a = math.radians(angdiff(bearing, hdg))
        return cx + rx * r * math.sin(a), cy - ry * r * math.cos(a)

    def spt(screen_deg, r):
        a = math.radians(screen_deg)
        return cx + rx * r * math.sin(a), cy - ry * r * math.cos(a)

    # radar sweep
    sweep = (t * 140) % 360
    for i in range(1, 20):
        x, y = spt(sweep, i / 20 * 0.95)
        cv.put(x, y, "·", "dgreen")
    # inner ring
    for d in range(0, 360, 6):
        x, y = spt(d, 0.42)
        cv.put(x, y, "·", "faint")
    # outer ring + ticks
    step = max(2, int(360 / (2 * math.pi * rx) * 1.2))
    for d in range(0, 360, step):
        x, y = pt(d, 1.0)
        cv.put(x, y, "·", "deep")
    for d in range(0, 360, 15):
        x, y = pt(d, 1.0)
        if d % 90 == 0:
            cv.put(x, y, "◆", "red" if d == 0 else "cyan")
        else:
            cv.put(x, y, "•", "aqua" if d % 45 == 0 else "teal")
    # heading line (fixed, straight up)
    for i in range(2, int(ry * 0.88) + 1):
        cv.put(cx, cy - i, "┆", "sky")
    # labels
    for d in range(0, 360, 30):
        x, y = pt(d, 0.74)
        if d % 90 == 0:
            lbl = "NESW"[d // 90]
            cv.put(x, y, lbl, "red" if d == 0 else "cyan")
        elif ry >= 7:
            lbl = str(d)
            cv.put(x - len(lbl) // 2, y, lbl, "grey")
    # north needle (magenta) and tail
    n_screen = angdiff(0, hdg)
    ch = needle_char(n_screen)
    steps = max(6, int(ry * 1.4))
    for i in range(1, steps + 1):
        x, y = spt(n_screen, 0.60 * i / steps)
        cv.put(x, y, ch, "mag")
    for i in range(1, steps // 2 + 1):
        x, y = spt(n_screen + 180, 0.32 * i / (steps // 2))
        cv.put(x, y, ch, "grey")
    x, y = spt(n_screen, 0.64)
    cv.put(x, y, "▲" if abs(n_screen) < 45 else "●", "pink")
    # mark
    if mark is not None:
        x, y = pt(mark, 1.0)
        cv.put(x, y, "✦", "yel")
    # lubber line + centre
    cv.put(cx, cy - ry - 1, "▼", "yel")
    cv.put(cx, cy, "◉", "white")


def draw_tape(cv, y, hdg):
    w = cv.w
    mid = w // 2
    for L in range(0, 360, 5):
        d = angdiff(L, hdg)
        col = mid + int(round(d / 2.0))
        if col < 1 or col >= w - 1:
            continue
        if L % 30 == 0:
            cv.put(col, y + 1, "┃", "cyan")
            lbl = "NESW"[L // 90] if L % 90 == 0 else "%03d" % L
            cv.put(col - len(lbl) // 2, y, lbl,
                   "red" if L == 0 else ("cyan" if L % 90 == 0 else "grey"))
        elif L % 10 == 0:
            cv.put(col, y + 1, "│", "teal")
        else:
            cv.put(col, y + 1, "╵", "deep")
    cv.put(0, y + 1, "◀", "deep")
    cv.put(w - 1, y + 1, "▶", "deep")
    cv.put(mid, y + 2, "▲", "yel")


def draw_big(cv, y, hdg, ref):
    s = "%03d" % (int(round(hdg)) % 360)
    colors = ["cyan", "sky", "pink"]
    label = cardinal(hdg)
    width = 3 * 4 + 2 + max(4, len(label) + 1)
    x0 = (cv.w - width) // 2
    for i, d in enumerate(s):
        for r in range(3):
            cv.put(x0 + i * 4, y + r, BIG[d][r], colors[i])
    x = x0 + 12
    cv.put(x, y, "°", "white")
    cv.put(x + 2, y, label, "yel")
    cv.put(x + 2, y + 2, ref, "mag" if ref == "TRUE" else "teal")


def bar(frac, n=10):
    k = max(0, min(n, int(round(frac * n))))
    return "▮" * k + "▯" * (n - k)


def draw_panel(cv, y, st):
    x = 2
    f = st["field"]
    if f is None:
        cv.segs(x, y, [("FIELD ", "grey"), ("--.- µT", "deep")])
    else:
        if f < 20 or f > 75:
            q, qc = "INTERFERENCE", "red"
        elif f < 25 or f > 65:
            q, qc = "NOISY", "orange"
        else:
            q, qc = "CLEAN", "green"
        cv.segs(x, y, [("FIELD ", "grey"), ("%5.1f µT " % f, "white"),
                       (bar(min(f, 100) / 100.0), qc), (" " + q, qc)])
    p, r = st["pitch"], st["roll"]
    if p is not None:
        lvl = abs(p) < 4 and abs(r) < 4
        cv.segs(x, y + 1, [("PITCH ", "grey"), ("%+5.1f° " % p, "white"),
                           ("ROLL ", "grey"), ("%+5.1f° " % r, "white"),
                           ("◎ LEVEL" if lvl else "◌ TILT", "green" if lvl else "orange")])
    if st["mark"] is None:
        cv.segs(x, y + 2, [("MARK  ", "grey"), ("—   press [m] to lock a bearing", "deep")])
    else:
        d = angdiff(st["mark"], st["hdg"])
        turn = "on target" if abs(d) < 3 else ("turn right ↻" if d > 0 else "turn left ↺")
        cv.segs(x, y + 2, [("MARK  ", "grey"), ("%03d° " % st["mark"], "yel"),
                           ("Δ%+4d° " % int(round(d)), "white"), (turn, "pink")])
    msg = st.get("msg")
    if msg:
        cv.segs(x, y + 3, [msg])
    else:
        src = st["src"]
        cv.segs(x, y + 3, [("SRC   ", "grey"), (src[: cv.w - 10], "deep")])
    cv.segs(x, y + 4, [("[q]", "cyan"), ("uit ", "grey"), ("[m]", "cyan"), ("ark ", "grey"),
                       ("[t]", "cyan"), ("rue/mag ", "grey"), ("[c]", "cyan"), ("alibrate", "grey")])


HEADER = "▓▒░ CYBER//COMPASS ░▒▓"
GLITCH = "▓▒░#@$%&*?!<>/\\"


def draw_header(cv, live, demo):
    text = list(HEADER)
    colors = ["mag"] * 3 + ["cyan"] * (len(text) - 6) + ["mag"] * 3
    if random.random() < 0.10:
        for _ in range(random.randint(1, 2)):
            i = random.randrange(4, len(text) - 4)
            text[i] = random.choice(GLITCH)
            colors[i] = "pink"
    for i, (c, col) in enumerate(zip(text, colors)):
        cv.put(1 + i, 0, c, col)
    clock = time.strftime("%H:%M:%S")
    if demo:
        status = [("DEMO ", "orange"), ("●", "orange")]
    else:
        status = [("LINK ", "grey"), ("●", "green" if live else "red")]
    right = [(clock + "  ", "grey")] + status
    width = sum(len(t) for t, _ in right)
    cv.segs(cv.w - width - 1, 0, right)


def frame(w, h, st, t):
    cv = Canvas(w, h)
    draw_header(cv, st["live"], st["demo"])
    if st["hdg"] is None:
        lines = st["wait"]
        for i, (txt, col) in enumerate(lines):
            cv.center(h // 2 - len(lines) // 2 + i, txt[: w - 2], col)
        return cv.render()
    ry = (h - 16) // 2
    rx = min(ry * 2, (w - 4) // 2)
    ry = min(ry, rx // 2)
    rx = ry * 2
    if ry < 4 or w < 34:
        cv.center(h // 2 - 1, "%03d° %s" % (int(round(st["hdg"])) % 360, cardinal(st["hdg"])), "cyan")
        cv.center(h // 2 + 1, "enlarge terminal for the dial", "grey")
        return cv.render()
    draw_tape(cv, 1, st["hdg"])
    draw_big(cv, 4, st["hdg"], st["ref"])
    cy = 9 + ry
    draw_dial(cv, w // 2, cy, rx, ry, st["hdg"], st["mark"], t)
    draw_panel(cv, cy + ry + 2, st)
    return cv.render()


# --------------------------------------------------------------------------
# Self test (used by CI)
# --------------------------------------------------------------------------


def selftest():
    worst = 0.0
    for hdg in range(0, 360, 7):
        for p in (-30, -10, 0, 15, 35):
            for r in (-25, 0, 20):
                acc, mag = synth_vectors(hdg, p, r)
                o = orientation(acc, mag)
                worst = max(worst, abs(angdiff(o[0], hdg)))
    assert worst < 0.01, "tilt-compensated heading error %.4f°" % worst
    assert cardinal(0) == "N" and cardinal(359) == "N" and cardinal(91) == "E"
    assert cardinal(202) == "SSW" and angdiff(10, 350) == 20
    st = dict(hdg=123.4, pitch=1.0, roll=-2.0, field=48.0, mark=200, ref="MAG",
              src="test", live=True, demo=True, msg=None, wait=[])
    for w, h in ((40, 30), (60, 45), (120, 30), (30, 12)):
        out = frame(w, h, st, 1.0)
        assert out.count("\n") == h - 1
    print("selftest OK  (max heading error %.6f°)" % worst)


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------


def parse_args():
    ap = argparse.ArgumentParser(description="Neon terminal compass for Termux.")
    ap.add_argument("--demo", action="store_true", help="simulated sensors")
    ap.add_argument("--declination", type=float, default=0.0,
                    help="magnetic declination in degrees (east +, west -) for true north")
    ap.add_argument("--true", action="store_true", help="start in true-north mode")
    ap.add_argument("--rate", type=int, default=60, help="sensor delay in ms (default 60)")
    ap.add_argument("--fps", type=float, default=15.0, help="screen refresh rate")
    ap.add_argument("--smooth", type=float, default=0.25,
                    help="heading smoothing 0.05 (smooth) .. 1 (raw), default 0.25")
    ap.add_argument("--plain", action="store_true",
                    help="print 'heading cardinal' lines instead of the UI")
    ap.add_argument("--no-color", action="store_true", help="disable colours")
    ap.add_argument("--frames", type=int, default=0, help="exit after N frames (testing)")
    ap.add_argument("--selftest", action="store_true", help="run built-in tests and exit")
    ap.add_argument("--version", action="version", version="cyber-compass " + VERSION)
    return ap.parse_args()


def main():
    global USE_COLOR
    args = parse_args()
    if args.no_color or os.environ.get("NO_COLOR"):
        USE_COLOR = False
    if args.selftest:
        selftest()
        return 0

    feed = DemoFeed() if args.demo else SensorFeed(args.rate)
    feed.start()
    true_mode = args.true
    mark = None
    msg_until, msg = 0.0, None
    sx = sy = None
    t0 = time.time()
    frames = 0
    k = max(0.01, min(1.0, args.smooth))

    with Terminal(interactive=not args.plain) as term:
        try:
            while True:
                key = term.key()
                now = time.time()
                if key == "q" or key == "\x1b":
                    break
                if key == "t":
                    true_mode = not true_mode
                    if true_mode and args.declination == 0.0:
                        msg, msg_until = ([("TRUE mode needs --declination (e.g. --declination 25.5)", "orange")], now + 4)
                snap = feed.snapshot()
                o = orientation(snap["acc"], snap["mag"])
                hdg = pitch = roll = None
                if o:
                    h_rad = math.radians(o[0])
                    if sx is None:
                        sx, sy = math.cos(h_rad), math.sin(h_rad)
                    else:
                        sx += k * (math.cos(h_rad) - sx)
                        sy += k * (math.sin(h_rad) - sy)
                    hdg = math.degrees(math.atan2(sy, sx)) % 360
                    if true_mode:
                        hdg = (hdg + args.declination) % 360
                    pitch, roll = o[1], o[2]
                if key == "m" and hdg is not None:
                    mark = None if mark is not None else int(round(hdg)) % 360
                if key == "c":
                    msg, msg_until = ([("CALIBRATE ", "yel"),
                                       ("wave the phone in a figure-8 ∞ for ~10 s", "white")], now + 8)
                live = (now - snap["last"]) < 1.5 if snap["last"] else False

                if args.plain:
                    if hdg is not None:
                        print("%6.1f %-3s pitch=%+.1f roll=%+.1f" % (hdg, cardinal(hdg), pitch, roll),
                              flush=True)
                    elif snap["error"]:
                        print("error: " + snap["error"], file=sys.stderr)
                        return 1
                else:
                    wait = [("WAITING FOR SENSORS", "cyan"), ("", None)]
                    if snap["error"]:
                        wait = [("SENSOR ERROR", "red"), ("", None), (snap["error"], "white")]
                    elif now - t0 > 4:
                        wait += [("No data yet. Check that:", "grey"),
                                 ("• the Termux:API app is installed", "grey"),
                                 ("  (same source as Termux, e.g. F-Droid)", "grey"),
                                 ("• pkg install termux-api was run", "grey"),
                                 ("Try --demo to see the UI.", "mag")]
                    names = snap["names"]
                    src = " + ".join(names.get(x, "?") for x in ("acc", "mag"))
                    st = dict(hdg=hdg, pitch=pitch, roll=roll,
                              field=mag3(snap["mag"]) if snap["mag"] else None,
                              mark=mark, ref="TRUE" if true_mode else "MAG",
                              src=src, live=live, demo=args.demo, wait=wait,
                              msg=msg if now < msg_until else None)
                    w, h = shutil.get_terminal_size((60, 40))
                    out = frame(w, h, st, now - t0)
                    sys.stdout.write("\033[H" + out)
                    sys.stdout.flush()
                frames += 1
                if args.frames and frames >= args.frames:
                    break
                time.sleep(1.0 / max(1.0, args.fps))
        except KeyboardInterrupt:
            pass
        finally:
            feed.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
