package nz.eloni.cybercompass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/** Draws the whole neon compass UI on a Canvas. */
public class CompassView extends View {

    public interface Callbacks {
        void onTrueModeChanged(boolean on);

        void onGlobeModeChanged(boolean on);
    }

    // ---- palette
    static final int BG = 0xFF020409, CYAN = 0xFF00F0FF, SKY = 0xFF7FF7FF, PINK = 0xFFFF6BEA,
            MAGENTA = 0xFFFF1FD1, YEL = 0xFFFFE600, RED = 0xFFFF2A3D, GREEN = 0xFF39FF14,
            ORANGE = 0xFFFF9900, GREY = 0xFF8A93A6, TEAL = 0xFF14B8C8, DEEP = 0xFF0E6E80,
            FAINT = 0xFF14222E, WHITE = 0xFFFFFFFF, NEEDLE_TAIL = 0xFF5A6478;

    static final String[] POINTS16 = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
    static final String TITLE = "▓▒░ CYBER//COMPASS ░▒▓";
    static final String GLITCH = "▓▒░#@$%&*?!<>/\\";

    // ---- state written by MainActivity
    volatile float field = Float.NaN;
    volatile int accuracy = -1;
    boolean trueMode;
    float declination = Float.NaN;
    String source = "";
    String error;
    boolean globeMode;
    volatile float lat = Float.NaN, lon = Float.NaN;
    volatile float magPoleLat = Float.NaN, magPoleLon = Float.NaN;

    private volatile float pitch, roll;
    private volatile long lastSample;
    private double sx = Double.NaN, sy;
    private volatile float heading = Float.NaN;
    private Integer mark;
    private String toast;
    private long toastUntil;
    private Callbacks callbacks;

    // ---- drawing helpers
    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Matrix matrix = new Matrix();
    private final Random rnd = new Random();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final Typeface mono = Typeface.MONOSPACE;
    private final Typeface monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
    private final RectF bMark = new RectF(), bTrue = new RectF(), bGlobe = new RectF(), bCal = new RectF();
    private final Globe globe = new Globe();
    private final Path gFront = new Path(), gBack = new Path(), gFront2 = new Path(), gBack2 = new Path();
    private float yawOff = 0f, tilt = 30f;
    private float globeAreaBottom;
    private float downX, downY, lastX, lastY;
    private boolean dragging;
    private long lastTap;
    private final long t0 = SystemClock.uptimeMillis();
    private SweepGradient sweep;
    private float sweepCx = -1, sweepCy = -1;

    public CompassView(Context ctx) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        text.setTypeface(mono);
        setKeepScreenOn(true);
    }

    public void setCallbacks(Callbacks c) {
        callbacks = c;
    }

    /** Called from the sensor thread (main looper) with raw orientation in degrees. */
    public void update(float azimuth, float pitchDeg, float rollDeg) {
        double r = Math.toRadians(azimuth);
        double k = 0.18;
        if (Double.isNaN(sx)) {
            sx = Math.cos(r);
            sy = Math.sin(r);
        } else {
            sx += k * (Math.cos(r) - sx);
            sy += k * (Math.sin(r) - sy);
        }
        heading = (float) ((Math.toDegrees(Math.atan2(sy, sx)) + 360.0) % 360.0);
        pitch = pitchDeg;
        roll = rollDeg;
        lastSample = SystemClock.uptimeMillis();
    }

    public void showToast(String msg, long ms) {
        toast = msg;
        toastUntil = SystemClock.uptimeMillis() + ms;
    }

    private float displayHeading() {
        float h = heading;
        if (trueMode && !Float.isNaN(declination)) h = (h + declination + 360f) % 360f;
        return h;
    }

    static float angDiff(float a, float b) {
        return ((a - b + 540f) % 360f) - 180f;
    }

    static String cardinal(float deg) {
        return POINTS16[(int) (((deg % 360f) + 360f) % 360f / 22.5f + 0.5f) % 16];
    }

    // ================================================================ drawing

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        final float w = getWidth(), h = getHeight();
        final long now = SystemClock.uptimeMillis();
        final float t = (now - t0) / 1000f;
        c.drawColor(BG);
        drawScanlines(c, w, h);

        float pad = 14 * dp;
        float top = 30 * dp;                       // clear of punch-hole cameras
        boolean live = now - lastSample < 1500;
        drawHeader(c, w, top, pad, live);

        float hd = displayHeading();
        float tapeTop = top + 26 * dp;
        float tapeH = 48 * dp;
        float bigBase = tapeTop + tapeH + 78 * dp;

        float panelLine = 22 * dp;
        float buttonsH = 52 * dp;
        float panelH = panelLine * 4 + buttonsH + pad * 2;
        float dialTop = bigBase + 34 * dp;
        float dialBottom = h - panelH;
        float cx = w / 2f;
        float R = Math.min((dialBottom - dialTop) / 2f - 8 * dp, w / 2f - pad - 6 * dp);
        R = Math.max(R, 60 * dp);
        float cy = dialTop + (dialBottom - dialTop) / 2f;

        if (globeMode) {
            globeAreaBottom = h - panelH;
            drawGlobe(c, w, top + 12 * dp, h - panelH, t);
            drawGlobePanel(c, w, h - panelH + pad, pad, panelLine, buttonsH, now);
        } else {
            if (Float.isNaN(hd)) {
                drawWaiting(c, w, cx, cy, R, t);
            } else {
                drawTape(c, w, tapeTop, tapeH, hd);
                drawBigHeading(c, cx, bigBase, hd);
                drawDial(c, cx, cy, R, hd, t);
            }
            drawPanel(c, w, h - panelH + pad, pad, panelLine, buttonsH, hd, now);
        }

        postInvalidateOnAnimation();
    }

    private void drawScanlines(Canvas c, float w, float h) {
        fill.setColor(0x0800F0FF);
        float step = 4 * dp;
        for (float y = 0; y < h; y += step) c.drawRect(0, y, w, y + dp, fill);
    }

    private void drawHeader(Canvas c, float w, float y, float pad, boolean live) {
        text.setTextSize(15 * dp);
        text.setTypeface(monoBold);
        text.setTextAlign(Paint.Align.LEFT);
        char[] chars = TITLE.toCharArray();
        int g1 = -1, g2 = -1;
        if (rnd.nextFloat() < 0.04f) {
            g1 = 4 + rnd.nextInt(chars.length - 8);
            chars[g1] = GLITCH.charAt(rnd.nextInt(GLITCH.length()));
            if (rnd.nextBoolean()) {
                g2 = 4 + rnd.nextInt(chars.length - 8);
                chars[g2] = GLITCH.charAt(rnd.nextInt(GLITCH.length()));
            }
        }
        float x = pad;
        for (int i = 0; i < chars.length; i++) {
            String s = String.valueOf(chars[i]);
            int col = (i < 3 || i >= chars.length - 3) ? MAGENTA : CYAN;
            if (i == g1 || i == g2) col = PINK;
            text.setColor(col);
            c.drawText(s, x, y, text);
            x += text.measureText(s);
        }
        float titleEnd = x;
        text.setTypeface(mono);
        text.setTextSize(12 * dp);
        text.setTextAlign(Paint.Align.RIGHT);
        float dotR = 4 * dp;
        fill.setColor(live ? GREEN : RED);
        c.drawCircle(w - pad - dotR, y - 5 * dp, dotR, fill);
        text.setColor(GREY);
        float right = w - pad - dotR * 2 - 6 * dp;
        String clk = clock.format(new Date());
        String full = clk + " LINK";
        if (right - text.measureText(full) > titleEnd + 6 * dp) c.drawText(full, right, y, text);
        else if (right - text.measureText(clk) > titleEnd + 6 * dp) c.drawText(clk, right, y, text);
    }

    private void drawTape(Canvas c, float w, float top, float hgt, float hd) {
        float cx = w / 2f;
        float ppd = w / 120f;                     // ±60° visible
        float base = top + hgt - 8 * dp;
        stroke.setStrokeWidth(1 * dp);
        stroke.setColor(DEEP);
        c.drawLine(0, base, w, base, stroke);
        text.setTextAlign(Paint.Align.CENTER);
        for (int L = 0; L < 360; L += 5) {
            float d = angDiff(L, hd);
            if (Math.abs(d) > 62) continue;
            float x = cx + d * ppd;
            float len;
            int col;
            if (L % 30 == 0) {
                len = 18 * dp;
                col = CYAN;
                stroke.setStrokeWidth(2.5f * dp);
                String lbl = (L % 90 == 0) ? "NESW".substring(L / 90, L / 90 + 1) : String.format(Locale.US, "%03d", L);
                text.setTextSize((L % 90 == 0 ? 16 : 13) * dp);
                text.setTypeface(L % 90 == 0 ? monoBold : mono);
                text.setColor(L == 0 ? RED : (L % 90 == 0 ? CYAN : GREY));
                c.drawText(lbl, x, base - len - 6 * dp, text);
            } else if (L % 10 == 0) {
                len = 11 * dp;
                col = TEAL;
                stroke.setStrokeWidth(1.5f * dp);
            } else {
                len = 6 * dp;
                col = DEEP;
                stroke.setStrokeWidth(1.2f * dp);
            }
            stroke.setColor(col);
            c.drawLine(x, base, x, base - len, stroke);
        }
        // fade the ends
        fill.setShader(new LinearGradient(0, 0, w * 0.18f, 0, BG, 0x00020409, Shader.TileMode.CLAMP));
        c.drawRect(0, top - 4 * dp, w * 0.18f, base + 2 * dp, fill);
        fill.setShader(new LinearGradient(w, 0, w * 0.82f, 0, BG, 0x00020409, Shader.TileMode.CLAMP));
        c.drawRect(w * 0.82f, top - 4 * dp, w, base + 2 * dp, fill);
        fill.setShader(null);
        // centre pointer
        fill.setColor(YEL);
        path.reset();
        path.moveTo(cx, base + 1 * dp);
        path.lineTo(cx - 7 * dp, base + 11 * dp);
        path.lineTo(cx + 7 * dp, base + 11 * dp);
        path.close();
        c.drawPath(path, fill);
        text.setTypeface(mono);
    }

    private void drawBigHeading(Canvas c, float cx, float base, float hd) {
        String s = String.format(Locale.US, "%03d", Math.round(hd) % 360);
        int[] cols = {CYAN, SKY, PINK};
        text.setTypeface(monoBold);
        text.setTextSize(76 * dp);
        text.setTextAlign(Paint.Align.LEFT);
        float cw = text.measureText("0");
        float total = cw * 3;
        float x0 = cx - total / 2f - 26 * dp;
        for (int i = 0; i < 3; i++) {
            String d = s.substring(i, i + 1);
            // glow pass
            text.setStyle(Paint.Style.STROKE);
            text.setStrokeWidth(6 * dp);
            text.setColor((cols[i] & 0x00FFFFFF) | 0x33000000);
            c.drawText(d, x0 + i * cw, base, text);
            text.setStyle(Paint.Style.FILL);
            text.setColor(cols[i]);
            c.drawText(d, x0 + i * cw, base, text);
        }
        float xr = x0 + total + 6 * dp;
        text.setTextSize(28 * dp);
        text.setColor(WHITE);
        c.drawText("°", xr, base - 44 * dp, text);
        text.setColor(YEL);
        text.setTextSize(26 * dp);
        c.drawText(cardinal(hd), xr + 18 * dp, base - 36 * dp, text);
        text.setTypeface(mono);
        text.setTextSize(15 * dp);
        boolean t = trueMode && !Float.isNaN(declination);
        text.setColor(t ? MAGENTA : TEAL);
        c.drawText(t ? "TRUE" : (trueMode ? "TRUE?" : "MAG"), xr + 18 * dp, base - 4 * dp, text);
    }

    private void drawDial(Canvas c, float cx, float cy, float R, float hd, float t) {
        // radar sweep
        if (sweep == null || sweepCx != cx || sweepCy != cy) {
            sweep = new SweepGradient(cx, cy, new int[]{0x0039FF14, 0x0039FF14, 0x4039FF14},
                    new float[]{0f, 0.82f, 1f});
            sweepCx = cx;
            sweepCy = cy;
        }
        matrix.setRotate((t * 120f) % 360f, cx, cy);
        sweep.setLocalMatrix(matrix);
        fill.setShader(sweep);
        c.drawCircle(cx, cy, R * 0.96f, fill);
        fill.setShader(null);

        // static rings + crosshair
        stroke.setStrokeWidth(1 * dp);
        stroke.setColor(FAINT);
        c.drawCircle(cx, cy, R * 0.42f, stroke);
        c.drawLine(cx - R * 0.96f, cy, cx + R * 0.96f, cy, stroke);
        c.drawLine(cx, cy - R * 0.96f, cx, cy + R * 0.96f, stroke);
        glow.setStrokeWidth(8 * dp);
        glow.setColor(0x2200F0FF);
        c.drawCircle(cx, cy, R, glow);
        stroke.setStrokeWidth(2 * dp);
        stroke.setColor(DEEP);
        c.drawCircle(cx, cy, R, stroke);

        // rotating card
        c.save();
        c.rotate(-hd, cx, cy);
        for (int b = 0; b < 360; b += 5) {
            float len;
            int col;
            if (b % 90 == 0) {
                len = 18 * dp;
                col = (b == 0) ? RED : CYAN;
                stroke.setStrokeWidth(3 * dp);
            } else if (b % 30 == 0) {
                len = 14 * dp;
                col = CYAN;
                stroke.setStrokeWidth(2 * dp);
            } else if (b % 10 == 0) {
                len = 10 * dp;
                col = TEAL;
                stroke.setStrokeWidth(1.5f * dp);
            } else {
                len = 6 * dp;
                col = DEEP;
                stroke.setStrokeWidth(1.2f * dp);
            }
            double a = Math.toRadians(b);
            float sn = (float) Math.sin(a), cs = (float) Math.cos(a);
            stroke.setColor(col);
            c.drawLine(cx + (R - 2 * dp) * sn, cy - (R - 2 * dp) * cs,
                    cx + (R - 2 * dp - len) * sn, cy - (R - 2 * dp - len) * cs, stroke);
        }
        text.setTextAlign(Paint.Align.CENTER);
        for (int b = 0; b < 360; b += 30) {
            c.save();
            c.rotate(b, cx, cy);
            if (b % 90 == 0) {
                text.setTypeface(monoBold);
                text.setTextSize(24 * dp);
                text.setColor(b == 0 ? RED : CYAN);
                c.drawText("NESW".substring(b / 90, b / 90 + 1), cx, cy - R + 46 * dp, text);
            } else {
                text.setTypeface(mono);
                text.setTextSize(13 * dp);
                text.setColor(GREY);
                c.drawText(String.valueOf(b), cx, cy - R + 38 * dp, text);
            }
            c.restore();
        }
        for (int b = 45; b < 360; b += 90) {
            c.save();
            c.rotate(b, cx, cy);
            text.setTypeface(mono);
            text.setTextSize(12 * dp);
            text.setColor(DEEP);
            c.drawText(POINTS16[b / 45 * 2], cx, cy - R + 64 * dp, text);
            c.restore();
        }
        // mark
        if (mark != null) {
            c.save();
            c.rotate(mark, cx, cy);
            fill.setColor(YEL);
            path.reset();
            float my = cy - R + 3 * dp;
            path.moveTo(cx, my - 7 * dp);
            path.lineTo(cx + 6 * dp, my);
            path.lineTo(cx, my + 7 * dp);
            path.lineTo(cx - 6 * dp, my);
            path.close();
            c.drawPath(path, fill);
            c.restore();
        }
        // north needle (points to bearing 0 in the card frame)
        float tip = R * 0.62f, tail = R * 0.34f, half = 9 * dp;
        path.reset();
        path.moveTo(cx, cy - tip);
        path.lineTo(cx + half, cy);
        path.lineTo(cx - half, cy);
        path.close();
        glow.setStrokeWidth(10 * dp);
        glow.setColor(0x33FF1FD1);
        c.drawPath(path, glow);
        fill.setColor(MAGENTA);
        c.drawPath(path, fill);
        path.reset();
        path.moveTo(cx, cy + tail);
        path.lineTo(cx + half, cy);
        path.lineTo(cx - half, cy);
        path.close();
        fill.setColor(NEEDLE_TAIL);
        c.drawPath(path, fill);
        fill.setColor(PINK);
        c.drawCircle(cx, cy - tip, 4 * dp, fill);
        c.restore();

        // fixed heading line + lubber
        stroke.setStrokeWidth(1.5f * dp);
        stroke.setColor(SKY);
        stroke.setPathEffect(new DashPathEffect(new float[]{6 * dp, 5 * dp}, 0));
        c.drawLine(cx, cy - 14 * dp, cx, cy - R * 0.9f, stroke);
        stroke.setPathEffect(null);
        fill.setColor(YEL);
        path.reset();
        path.moveTo(cx, cy - R + 4 * dp);
        path.lineTo(cx - 9 * dp, cy - R - 12 * dp);
        path.lineTo(cx + 9 * dp, cy - R - 12 * dp);
        path.close();
        c.drawPath(path, fill);

        // hub
        fill.setColor(BG);
        c.drawCircle(cx, cy, 8 * dp, fill);
        stroke.setColor(WHITE);
        stroke.setStrokeWidth(2 * dp);
        c.drawCircle(cx, cy, 8 * dp, stroke);
        fill.setColor(WHITE);
        c.drawCircle(cx, cy, 3.5f * dp, fill);
    }

    private void drawWaiting(Canvas c, float w, float cx, float cy, float R, float t) {
        stroke.setStrokeWidth(2 * dp);
        stroke.setColor(DEEP);
        c.drawCircle(cx, cy, R, stroke);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(monoBold);
        text.setTextSize(18 * dp);
        if (error != null) {
            text.setColor(RED);
            c.drawText("SENSOR ERROR", cx, cy - 10 * dp, text);
            text.setTypeface(mono);
            text.setTextSize(12 * dp);
            text.setColor(WHITE);
            c.drawText(error, cx, cy + 16 * dp, text);
        } else {
            boolean blink = ((int) (t * 2)) % 2 == 0;
            text.setColor(blink ? CYAN : DEEP);
            c.drawText("ACQUIRING SIGNAL", cx, cy, text);
        }
        text.setTypeface(mono);
    }

    private void drawPanel(Canvas c, float w, float y, float pad, float line, float buttonsH,
                           float hd, long now) {
        text.setTypeface(mono);
        text.setTextSize(14 * dp);
        text.setTextAlign(Paint.Align.LEFT);
        float x = pad;
        float labelW = text.measureText("FIELD  ");
        float base = y + 14 * dp;

        // FIELD
        label(c, "FIELD", x, base);
        float f = field;
        if (Float.isNaN(f)) {
            val(c, "--.- µT", x + labelW, base, DEEP);
        } else {
            String q;
            int qc;
            if (f < 20 || f > 75) { q = "INTERFERENCE"; qc = RED; }
            else if (f < 25 || f > 65) { q = "NOISY"; qc = ORANGE; }
            else { q = "CLEAN"; qc = GREEN; }
            String v = String.format(Locale.US, "%5.1f µT", f);
            val(c, v, x + labelW, base, WHITE);
            float bx = x + labelW + text.measureText(v + " ");
            float cell = 7 * dp, gap = 2.5f * dp;
            int lit = Math.max(0, Math.min(10, Math.round(Math.min(f, 100f) / 10f)));
            for (int i = 0; i < 10; i++) {
                fill.setColor(i < lit ? qc : 0xFF1C2A33);
                c.drawRect(bx + i * (cell + gap), base - 11 * dp, bx + i * (cell + gap) + cell, base, fill);
            }
            val(c, q, bx + 10 * (cell + gap) + 6 * dp, base, qc);
        }
        base += line;

        // PITCH / ROLL
        label(c, "TILT", x, base);
        if (!Float.isNaN(hd)) {
            boolean lvl = Math.abs(pitch) < 4 && Math.abs(roll) < 4;
            String v = String.format(Locale.US, "P%+6.1f°  R%+6.1f°  ", pitch, roll);
            val(c, v, x + labelW, base, WHITE);
            val(c, lvl ? "◎ LEVEL" : "◌ TILT", x + labelW + text.measureText(v), base,
                    lvl ? GREEN : ORANGE);
        }
        base += line;

        // MARK
        label(c, "MARK", x, base);
        if (mark == null || Float.isNaN(hd)) {
            val(c, "—  tap MARK to lock a bearing", x + labelW, base, DEEP);
        } else {
            float d = angDiff(mark, hd);
            String turn = Math.abs(d) < 3 ? "ON TARGET" : (d > 0 ? "TURN RIGHT ↻" : "TURN LEFT ↺");
            String v = String.format(Locale.US, "%03d°  Δ%+4d°  ", mark, Math.round(d));
            val(c, v, x + labelW, base, YEL);
            val(c, turn, x + labelW + text.measureText(v), base, PINK);
        }
        base += line;

        // STATUS / toast
        if (toast != null && now < toastUntil) {
            val(c, toast, x, base, YEL);
        } else {
            label(c, "CAL", x, base);
            String acc;
            int ac;
            switch (accuracy) {
                case 3: acc = "HIGH"; ac = GREEN; break;
                case 2: acc = "MEDIUM"; ac = YEL; break;
                case 1: acc = "LOW - wave phone in a figure-8"; ac = ORANGE; break;
                case 0: acc = "UNRELIABLE - figure-8 now"; ac = RED; break;
                default: acc = "--"; ac = DEEP;
            }
            if (trueMode && !Float.isNaN(declination) && accuracy >= 2) {
                acc += String.format(Locale.US, "   DECL %+.1f°", declination);
            }
            val(c, acc, x + labelW, base, ac);
        }

        drawButtons(c, w, base + 14 * dp, pad, buttonsH);
    }

    private void drawButtons(Canvas c, float w, float by, float pad, float buttonsH) {
        float gap = 8 * dp;
        float bw = (w - pad * 2 - 3 * gap) / 4f;
        float bh = buttonsH - 10 * dp;
        bMark.set(pad, by, pad + bw, by + bh);
        bTrue.set(pad + (bw + gap), by, pad + (bw + gap) + bw, by + bh);
        bGlobe.set(pad + 2 * (bw + gap), by, pad + 2 * (bw + gap) + bw, by + bh);
        bCal.set(pad + 3 * (bw + gap), by, w - pad, by + bh);
        button(c, bMark, mark != null ? "CLEAR" : "MARK", YEL, mark != null);
        button(c, bTrue, trueMode ? "TRUE" : "MAG", MAGENTA, trueMode);
        button(c, bGlobe, globeMode ? "DIAL" : "GLOBE", GREEN, globeMode);
        button(c, bCal, "CALIB", CYAN, false);
    }

    private void label(Canvas c, String s, float x, float y) {
        text.setColor(GREY);
        c.drawText(s, x, y, text);
    }

    private void val(Canvas c, String s, float x, float y, int col) {
        text.setColor(col);
        c.drawText(s, x, y, text);
    }

    private void button(Canvas c, RectF r, String label, int col, boolean active) {
        float rad = 8 * dp;
        fill.setColor(active ? ((col & 0x00FFFFFF) | 0x40000000) : 0xFF07121A);
        c.drawRoundRect(r, rad, rad, fill);
        glow.setStrokeWidth(5 * dp);
        glow.setColor((col & 0x00FFFFFF) | 0x26000000);
        c.drawRoundRect(r, rad, rad, glow);
        stroke.setStrokeWidth(1.5f * dp);
        stroke.setColor(col);
        c.drawRoundRect(r, rad, rad, stroke);
        text.setTypeface(monoBold);
        text.setTextSize(13 * dp);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(active ? WHITE : col);
        c.drawText(label, r.centerX(), r.centerY() + 5 * dp, text);
        text.setTypeface(mono);
        text.setTextAlign(Paint.Align.LEFT);
    }

    // ================================================================ 3D globe

    private float trueHeading() {
        float hd = heading;
        if (Float.isNaN(hd)) return Float.NaN;
        float d = Float.isNaN(declination) ? 0f : declination;
        return (hd + d + 360f) % 360f;
    }

    private void drawGlobe(Canvas c, float w, float areaTop, float areaBottom, float t) {
        float gcx = w / 2f;
        float areaH = areaBottom - areaTop;
        float r = Math.min(w / 2f - 30 * dp, areaH / 2f - 46 * dp);
        float gcy = areaTop + areaH / 2f + 14 * dp;
        boolean haveLoc = !Float.isNaN(lat) && !Float.isNaN(lon);
        float th = trueHeading();

        // stars
        for (int i = 0; i < globe.stars.length; i += 3) {
            float sx = globe.stars[i] * w;
            float sy = areaTop + globe.stars[i + 1] * areaH;
            float ddx = sx - gcx, ddy = sy - gcy;
            if (ddx * ddx + ddy * ddy < (r * 1.12f) * (r * 1.12f)) continue;
            float tw = 0.55f + 0.45f * (float) Math.sin(t * 1.7f + i);
            int a = (int) (255 * globe.stars[i + 2] * tw);
            fill.setColor((a << 24) | 0x00CFEFFF);
            c.drawCircle(sx, sy, (globe.stars[i + 2] > 0.85f ? 1.6f : 1.0f) * dp, fill);
        }

        // camera
        if (haveLoc && !Float.isNaN(th)) {
            globe.setCamera(lat, lon, th + yawOff, tilt, 3.2, gcx, gcy, r);
        } else {
            globe.setCamera(15, ((t * 12f) % 360f) - 180f, 0, 20, 3.2, gcx, gcy, r);
        }

        // atmosphere glow
        float gr = r * 1.2f;
        fill.setShader(new RadialGradient(gcx, gcy, gr,
                new int[]{0x0000F0FF, 0x0000F0FF, 0x6600F0FF, 0x0000F0FF},
                new float[]{0f, 0.78f, r / gr, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(gcx, gcy, gr, fill);
        fill.setShader(null);

        // ---- back side (seen through the holographic globe)
        gFront.reset();
        gBack.reset();
        for (float[] p : globe.parallels) globe.addPolyline(p, gFront, gBack);
        for (float[] p : globe.meridians) globe.addPolyline(p, gFront, gBack);
        gFront2.reset();
        gBack2.reset();
        for (float[] p : globe.coast) globe.addPolyline(p, gFront2, gBack2);
        stroke.setStrokeWidth(1 * dp);
        stroke.setColor(0xFF0B2A33);
        c.drawPath(gBack, stroke);
        stroke.setColor(0xFF1E5A2A);
        c.drawPath(gBack2, stroke);

        // ---- ocean sphere with 3D shading
        fill.setShader(new RadialGradient(gcx - r * 0.35f, gcy - r * 0.4f, r * 1.5f,
                new int[]{0xD0103848, 0xE0061A26, 0xF0010509}, new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        c.drawCircle(gcx, gcy, r, fill);
        fill.setShader(null);

        // ---- front graticule
        stroke.setStrokeWidth(1 * dp);
        stroke.setColor(0xFF1A4F5E);
        c.drawPath(gFront, stroke);
        // equator + prime meridian highlighted
        gFront.reset();
        gBack.reset();
        globe.addPolyline(globe.parallels[5], gFront, gBack);
        globe.addPolyline(globe.meridians[12], gFront, gBack);
        stroke.setColor(0xFF2A8CA0);
        stroke.setStrokeWidth(1.4f * dp);
        c.drawPath(gFront, stroke);

        // ---- front coastlines (neon)
        glow.setStrokeWidth(5 * dp);
        glow.setColor(0x3339FF14);
        c.drawPath(gFront2, glow);
        stroke.setStrokeWidth(1.6f * dp);
        stroke.setColor(GREEN);
        c.drawPath(gFront2, stroke);

        // limb
        stroke.setStrokeWidth(2 * dp);
        stroke.setColor(0xFF00B8C8);
        c.drawCircle(gcx, gcy, r, stroke);

        // ---- Earth's spin axis + poles
        drawAxis(c, 1, RED, "TRUE NORTH");
        drawAxis(c, -1, NEEDLE_TAIL, "S");

        text.setTextAlign(Paint.Align.CENTER);
        if (!haveLoc) {
            text.setTypeface(monoBold);
            text.setTextSize(15 * dp);
            boolean blink = ((int) (t * 2)) % 2 == 0;
            text.setColor(blink ? YEL : ORANGE);
            c.drawText("LOCATION NEEDED", gcx, gcy - 4 * dp, text);
            text.setTypeface(mono);
            text.setTextSize(12 * dp);
            text.setColor(WHITE);
            c.drawText("tap GLOBE again or turn on location", gcx, gcy + 16 * dp, text);
            drawGlobeHud(c, w, areaTop, Float.NaN);
            return;
        }

        // ---- magnetic north dip pole + path
        if (!Float.isNaN(magPoleLat)) {
            float[] mp = Globe.arcBetween(lat, lon, magPoleLat, magPoleLon, 1.5);
            pathPair(c, mp, MAGENTA, 0x66FF1FD1, 2f, true);
            if (globe.projectLL(magPoleLat, magPoleLon)) {
                boolean f = globe.facingLL(magPoleLat, magPoleLon);
                fill.setColor(f ? MAGENTA : 0x88FF1FD1);
                c.drawCircle(globe.px, globe.py, 5 * dp, fill);
                stroke.setColor(f ? PINK : 0x88FF6BEA);
                stroke.setStrokeWidth(1.5f * dp);
                c.drawCircle(globe.px, globe.py, 9 * dp, stroke);
                label(c, "MAG N", globe.px + 12 * dp, globe.py + 4 * dp, f ? PINK : 0x99FF6BEA, false);
            }
        }

        // ---- true north path (meridian to the geographic pole)
        float[] tn = Globe.arcBetween(lat, lon, 90, lon, 1.5);
        pathPair(c, tn, RED, 0x88FF2A3D, 3f, true);

        // ---- mark
        if (mark != null) {
            float mt = trueMode ? mark : mark + (Float.isNaN(declination) ? 0 : declination);
            float[] mk = Globe.arcFrom(lat, lon, mt, 40, 1.5);
            pathPair(c, mk, YEL, 0x55FFE600, 1.5f, true);
        }

        // ---- heading beam
        float[] hb = Globe.arcFrom(lat, lon, th, 50, 1.0);
        pathPair(c, hb, YEL, 0x66FFE600, 3f, false);
        int n = hb.length / 3;
        if (n > 2 && globe.facing(hb[(n - 1) * 3], hb[(n - 1) * 3 + 1], hb[(n - 1) * 3 + 2])
                && globe.project(hb[(n - 3) * 3], hb[(n - 3) * 3 + 1], hb[(n - 3) * 3 + 2])) {
            float bx = globe.px, by = globe.py;
            if (globe.project(hb[(n - 1) * 3], hb[(n - 1) * 3 + 1], hb[(n - 1) * 3 + 2])) {
                float ax = globe.px, ay = globe.py;
                double ang = Math.atan2(ay - by, ax - bx);
                float s = 11 * dp;
                path.reset();
                path.moveTo(ax + (float) Math.cos(ang) * s * 0.4f, ay + (float) Math.sin(ang) * s * 0.4f);
                path.lineTo(ax + (float) Math.cos(ang + 2.6) * s, ay + (float) Math.sin(ang + 2.6) * s);
                path.lineTo(ax + (float) Math.cos(ang - 2.6) * s, ay + (float) Math.sin(ang - 2.6) * s);
                path.close();
                fill.setColor(YEL);
                c.drawPath(path, fill);
            }
        }

        // ---- you are here
        if (globe.projectLL(lat, lon)) {
            float ux = globe.px, uy = globe.py;
            float ph = (t % 1.4f) / 1.4f;
            stroke.setStrokeWidth(2 * dp);
            stroke.setColor(((int) (255 * (1 - ph)) << 24) | 0x0000F0FF);
            c.drawCircle(ux, uy, 6 * dp + ph * 22 * dp, stroke);
            fill.setColor(WHITE);
            c.drawCircle(ux, uy, 5 * dp, fill);
            fill.setColor(CYAN);
            c.drawCircle(ux, uy, 2.5f * dp, fill);
            label(c, "YOU", ux + 10 * dp, uy + 16 * dp, CYAN, true);
        }

        // north pole label (on top of everything)
        if (globe.projectLL(90, 0)) {
            boolean f = globe.facingLL(90, 0);
            fill.setColor(f ? RED : 0x99FF2A3D);
            path.reset();
            path.moveTo(globe.px, globe.py - 7 * dp);
            path.lineTo(globe.px + 6 * dp, globe.py);
            path.lineTo(globe.px, globe.py + 7 * dp);
            path.lineTo(globe.px - 6 * dp, globe.py);
            path.close();
            c.drawPath(path, fill);
            if (!f) label(c, "N POLE · far side", globe.px + 10 * dp, globe.py + 4 * dp, 0xAAFF2A3D, false);
        }

        drawGlobeHud(c, w, areaTop, th);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(mono);
        text.setTextSize(11 * dp);
        text.setColor(DEEP);
        c.drawText("drag to orbit · double-tap to reset", gcx, areaBottom - 6 * dp, text);
    }

    /** Draws the spin axis sticking out of one pole (dir = +1 north, -1 south). */
    private void drawAxis(Canvas c, int dir, int col, String lbl) {
        float lx = 0, ly = 0;
        boolean prev = false;
        stroke.setStrokeWidth(2.5f * dp);
        stroke.setColor(col);
        for (int i = 0; i <= 20; i++) {
            double z = dir * (1.0 + 0.45 * i / 20.0);
            boolean vis = !globe.occluded(0, 0, z) && globe.project(0, 0, z);
            float x = globe.px, y = globe.py;
            if (vis && prev) c.drawLine(lx, ly, x, y, stroke);
            lx = x;
            ly = y;
            prev = vis;
        }
        if (prev) {
            fill.setColor(col);
            c.drawCircle(lx, ly, 3.5f * dp, fill);
            label(c, lbl, lx + 8 * dp, ly - 6 * dp, col, true);
        }
    }

    /** Front part bright with glow, back part dim (optionally dashed). */
    private void pathPair(Canvas c, float[] pts, int col, int backCol, float width, boolean dashBack) {
        gFront.reset();
        gBack.reset();
        globe.addPolyline(pts, gFront, gBack);
        stroke.setStrokeWidth(width * dp * 0.8f);
        stroke.setColor(backCol);
        if (dashBack) stroke.setPathEffect(new DashPathEffect(new float[]{5 * dp, 5 * dp}, 0));
        c.drawPath(gBack, stroke);
        stroke.setPathEffect(null);
        glow.setStrokeWidth(width * dp * 3.5f);
        glow.setColor((col & 0x00FFFFFF) | 0x33000000);
        c.drawPath(gFront, glow);
        stroke.setStrokeWidth(width * dp);
        stroke.setColor(col);
        c.drawPath(gFront, stroke);
    }

    private void label(Canvas c, String s, float x, float y, int col, boolean bold) {
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(bold ? monoBold : mono);
        text.setTextSize(12 * dp);
        text.setColor(BG);
        text.setStyle(Paint.Style.STROKE);
        text.setStrokeWidth(3 * dp);
        c.drawText(s, x, y, text);
        text.setStyle(Paint.Style.FILL);
        text.setColor(col);
        c.drawText(s, x, y, text);
        text.setTypeface(mono);
    }

    private void drawGlobeHud(Canvas c, float w, float y, float th) {
        float pad = 14 * dp;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(monoBold);
        text.setTextSize(22 * dp);
        float base = y + 22 * dp;
        if (Float.isNaN(th)) {
            text.setColor(DEEP);
            c.drawText("---°", pad, base, text);
        } else {
            String a = String.format(Locale.US, "%03d°", Math.round(th) % 360);
            text.setColor(CYAN);
            c.drawText(a, pad, base, text);
            float x = pad + text.measureText(a) + 6 * dp;
            text.setTypeface(mono);
            text.setTextSize(13 * dp);
            text.setColor(MAGENTA);
            c.drawText("TRUE", x, base - 9 * dp, text);
            text.setColor(TEAL);
            c.drawText(String.format(Locale.US, "%03d° MAG", Math.round(heading) % 360), x, base + 5 * dp, text);
        }
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTypeface(monoBold);
        text.setTextSize(13 * dp);
        text.setColor(GREEN);
        c.drawText("GLOBE//3D", w - pad, base - 9 * dp, text);
        text.setTypeface(mono);
        text.setColor(GREY);
        c.drawText(Float.isNaN(th) ? "" : cardinal(th) + " · HEADING-UP", w - pad, base + 5 * dp, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawGlobePanel(Canvas c, float w, float y, float pad, float line, float buttonsH, long now) {
        text.setTypeface(mono);
        text.setTextSize(14 * dp);
        text.setTextAlign(Paint.Align.LEFT);
        float x = pad;
        float labelW = text.measureText("TRUE N ");
        float base = y + 14 * dp;
        boolean haveLoc = !Float.isNaN(lat) && !Float.isNaN(lon);
        float th = trueHeading();

        label(c, "TRUE N", x, base);
        if (haveLoc && !Float.isNaN(th)) {
            float rel = angDiff(0, th);
            String turn = Math.abs(rel) < 4 ? "AHEAD ▲" : (rel > 0 ? String.format(Locale.US, "RIGHT %d° ↻", Math.round(rel))
                    : String.format(Locale.US, "LEFT %d° ↺", Math.round(-rel)));
            String v = turn + "  ";
            val(c, v, x + labelW, base, RED);
            val(c, String.format(Locale.US, "%,d km", Math.round((90 - lat) * 111.195)), x + labelW + text.measureText(v), base, WHITE);
        } else {
            val(c, "—", x + labelW, base, DEEP);
        }
        base += line;

        label(c, "MAG N", x, base);
        if (haveLoc && !Float.isNaN(magPoleLat)) {
            double b = Globe.bearing(lat, lon, magPoleLat, magPoleLon);
            double d = Globe.distanceKm(lat, lon, magPoleLat, magPoleLon);
            String v = String.format(Locale.US, "BRG %03d°T  ", Math.round(b) % 360);
            val(c, v, x + labelW, base, MAGENTA);
            val(c, String.format(Locale.US, "%,d km", Math.round(d)), x + labelW + text.measureText(v), base, WHITE);
        } else {
            val(c, Float.isNaN(magPoleLat) ? "locating dip pole..." : "—", x + labelW, base, DEEP);
        }
        base += line;

        label(c, "DECL", x, base);
        if (!Float.isNaN(declination)) {
            val(c, String.format(Locale.US, "%+.1f° %s  (true = mag %s %.1f°)", declination,
                    declination >= 0 ? "E" : "W", declination >= 0 ? "+" : "−", Math.abs(declination)),
                    x + labelW, base, YEL);
        } else {
            val(c, "needs location", x + labelW, base, DEEP);
        }
        base += line;

        if (toast != null && now < toastUntil) {
            val(c, toast, x, base, YEL);
        } else {
            label(c, "LOC", x, base);
            if (haveLoc) {
                val(c, String.format(Locale.US, "%.2f°%s  %.2f°%s", Math.abs(lat), lat >= 0 ? "N" : "S",
                        Math.abs(lon), lon >= 0 ? "E" : "W"), x + labelW, base, CYAN);
            } else {
                val(c, "unknown", x + labelW, base, DEEP);
            }
        }
        drawButtons(c, w, base + 14 * dp, pad, buttonsH);
    }

    // ================================================================ input

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = x;
                downY = lastY = y;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (globeMode && downY < globeAreaBottom) {
                    if (!dragging && Math.hypot(x - downX, y - downY) > 10 * dp) dragging = true;
                    if (dragging) {
                        yawOff -= (x - lastX) * 0.35f;
                        tilt = Math.max(0f, Math.min(80f, tilt + (y - lastY) * 0.25f));
                    }
                }
                lastX = x;
                lastY = y;
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    dragging = false;
                    return true;
                }
                handleTap(x, y);
                performClick();
                return true;
            default:
                return true;
        }
    }

    private void handleTap(float x, float y) {
        if (bMark.contains(x, y)) {
            float hd = displayHeading();
            if (mark != null) mark = null;
            else if (!Float.isNaN(hd)) mark = Math.round(hd) % 360;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        } else if (bTrue.contains(x, y)) {
            trueMode = !trueMode;
            mark = null;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (callbacks != null) callbacks.onTrueModeChanged(trueMode);
        } else if (bGlobe.contains(x, y)) {
            globeMode = !globeMode;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (callbacks != null) callbacks.onGlobeModeChanged(globeMode);
        } else if (bCal.contains(x, y)) {
            showToast("WAVE PHONE IN A FIGURE-8 \u221E FOR ~10 s", 8000);
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        } else if (globeMode && y < globeAreaBottom) {
            long now = SystemClock.uptimeMillis();
            if (now - lastTap < 350) {
                yawOff = 0f;
                tilt = 30f;
                showToast("VIEW RESET", 1200);
            }
            lastTap = now;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
