package nz.eloni.cybertoolkit;

import android.Manifest;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.ToneGenerator;

/**
 * Leak hunter: compressed-air, gas and vacuum leaks hiss loudest at very high frequencies.
 * Measures sound energy in a high band (default 16-22 kHz) against the room's background,
 * with Geiger feedback. SWEEP MAP uses the phone's orientation to paint where the hiss is
 * loudest - a poor man's acoustic camera.
 */
public class LeakTool extends Tool {

    private static final int SR = 48000, N = 2048;
    private static final int[][] BANDS = {{16000, 22000}, {12000, 20000}, {8000, 16000}};
    private static final int COLS = 24, ROWS = 14;
    private static final float SPAN_AZ = 120f, SPAN_EL = 70f;

    private final float[] win = FFT.hann(N);
    private final float[] specDb = new float[N / 2];
    private final float[] specDraw = new float[N / 2];
    private volatile float level = -200, top = -200;
    private volatile boolean go;
    private Thread th;
    private AudioRecord rec;
    private String status = "";
    private int band;

    private float base = Float.NaN, smooth = 0;
    private long calUntil;
    private double calSum;
    private int calN;
    private final Ring hist = new Ring(300);
    private long lastBeep;
    private ToneGenerator tg;

    private final float[][] heat = new float[ROWS][COLS];
    private final float[] rotM = new float[9];
    private boolean haveRot;
    private float az, el, az0;
    private Btn mapBtn, bandBtn, vibe, sound;

    public LeakTool(MainActivity a) {
        super(a);
        band = prefs.getInt("leak_band", 0);
        btn("CAL", this::calibrate).color = Neon.GREEN;
        bandBtn = btn(bandLabel(), () -> {
            band = (band + 1) % BANDS.length;
            prefs.edit().putInt("leak_band", band).apply();
            bandBtn.label = bandLabel();
            calibrate();
        });
        mapBtn = btn("SWEEP MAP", this::toggleMap);
        mapBtn.color = Neon.MAG;
        vibe = btn("VIBE", () -> vibe.on = !vibe.on);
        sound = btn("SOUND", () -> sound.on = !sound.on);
        btn("CLEAR", this::clearMap);
        vibe.on = true;
    }

    private String bandLabel() {
        return (BANDS[band][0] / 1000) + "-" + (BANDS[band][1] / 1000) + "k";
    }

    private void calibrate() {
        base = Float.NaN;
        calSum = 0;
        calN = 0;
        calUntil = System.currentTimeMillis() + 2000;
        hist.clear();
    }

    private void clearMap() {
        for (float[] r : heat) java.util.Arrays.fill(r, 0);
        az0 = az;
    }

    private void toggleMap() {
        if (!haveRot) {
            act.toast("No rotation sensor – map unavailable");
            return;
        }
        mapBtn.on = !mapBtn.on;
        if (mapBtn.on) clearMap();
    }

    @Override
    String title() {
        return "LEAK HUNTER";
    }

    @Override
    void onStart() {
        haveRot = listen(Sensor.TYPE_ROTATION_VECTOR, SensorManager.SENSOR_DELAY_GAME)
                || listen(Sensor.TYPE_GAME_ROTATION_VECTOR, SensorManager.SENSOR_DELAY_GAME);
        try {
            tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
        } catch (RuntimeException e) {
            tg = null;
        }
        act.needPerms(new String[]{Manifest.permission.RECORD_AUDIO}, this::startRec,
                () -> status = "MICROPHONE PERMISSION DENIED");
    }

    private void startRec() {
        rec = Audio.openWide(act, SR, N * 4);
        if (rec == null) {
            status = "MICROPHONE BUSY OR UNAVAILABLE";
            return;
        }
        calibrate();
        go = true;
        th = new Thread(this::loop, "leak");
        th.start();
    }

    @Override
    void onStop() {
        go = false;
        if (th != null) {
            try {
                th.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        th = null;
        if (rec != null) {
            try {
                rec.stop();
            } catch (Exception ignored) {
            }
            rec.release();
            rec = null;
        }
        if (tg != null) tg.release();
        tg = null;
    }

    private void loop() {
        short[] b = new short[N];
        float[] re = new float[N], im = new float[N];
        try {
            rec.startRecording();
        } catch (Exception e) {
            status = "CANNOT START MICROPHONE";
            return;
        }
        while (go) {
            if (!Audio.readFully(rec, b)) break;
            for (int i = 0; i < N; i++) {
                re[i] = b[i] / 32768f * win[i];
                im[i] = 0;
            }
            FFT.fft(re, im);
            int[] bd = BANDS[band];
            int k0 = bd[0] * N / SR, k1 = Math.min(N / 2 - 1, bd[1] * N / SR);
            int t0 = 21000 * N / SR, t1 = 23500 * N / SR;
            double sum = 0, tsum = 0;
            for (int k = 1; k < N / 2; k++) {
                double pw = (re[k] * re[k] + im[k] * im[k]) / ((double) N * N);
                specDb[k] = (float) (10 * Math.log10(pw + 1e-20));
                if (k >= k0 && k <= k1) sum += pw;
                if (k >= t0 && k <= t1) tsum += pw;
            }
            level = (float) (10 * Math.log10(sum + 1e-20));
            top = (float) (10 * Math.log10(tsum / Math.max(1, t1 - t0) + 1e-20));
            synchronized (specDraw) {
                System.arraycopy(specDb, 0, specDraw, 0, specDb.length);
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        int t = e.sensor.getType();
        if (t != Sensor.TYPE_ROTATION_VECTOR && t != Sensor.TYPE_GAME_ROTATION_VECTOR) return;
        SensorManager.getRotationMatrixFromVector(rotM, e.values);
        // direction the BACK of the phone points (like aiming a camera), in world coords
        float dx = -rotM[2], dy = -rotM[5], dz = -rotM[8];
        az = (float) Math.toDegrees(Math.atan2(dx, dy));
        el = (float) Math.toDegrees(Math.asin(Neon.clamp(dz, -1, 1)));
    }

    private static int heatColor(float db) {
        float t = Neon.clamp(db / 20f, 0, 1);
        int r, g, bl;
        if (t < 0.33f) {
            float u = t / 0.33f;
            r = 0; g = (int) (240 * u); bl = 255;
        } else if (t < 0.66f) {
            float u = (t - 0.33f) / 0.33f;
            r = (int) (255 * u); g = 240; bl = (int) (255 * (1 - u));
        } else {
            float u = (t - 0.66f) / 0.34f;
            r = 255; g = (int) (240 * (1 - u)); bl = 0;
        }
        int a = (int) (60 + 195 * t);
        return (a << 24) | (r << 16) | (g << 8) | bl;
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        float w = a.width(), cx = w / 2f;
        if (rec == null) {
            text(c, status.isEmpty() ? "Starting microphone…" : status, cx, a.top + 60 * dp, 14, Neon.RED,
                    Paint.Align.CENTER, false);
            return;
        }
        long now = System.currentTimeMillis();
        float lv = level;
        smooth += 0.3f * (lv - smooth);
        if (now < calUntil) {
            calSum += lv;
            calN++;
            text(c, "LEARNING BACKGROUND…", cx, a.top + 60 * dp, 20, Neon.YEL, Paint.Align.CENTER, true);
            text(c, "point the phone away from the suspected leak", cx, a.top + 86 * dp, 12, Neon.GREY,
                    Paint.Align.CENTER, false);
            return;
        }
        if (Float.isNaN(base)) base = calN > 0 ? (float) (calSum / calN) : smooth;
        float ex = Math.max(0, smooth - base);
        hist.add(ex);
        boolean hit = ex > 6;
        if (hit && now - lastBeep > Math.max(60, 600 - ex * 25)) {
            lastBeep = now;
            if (vibe.on) act.vibrate(20);
            if (sound.on && tg != null) tg.startTone(ToneGenerator.TONE_PROP_BEEP, 30);
        }
        if (mapBtn.on && haveRot) {
            float d = ((az - az0) % 360 + 540) % 360 - 180;
            int col = (int) ((d + SPAN_AZ / 2) / SPAN_AZ * COLS);
            int row = (int) ((SPAN_EL / 2 - el) / SPAN_EL * ROWS);
            if (col >= 0 && col < COLS && row >= 0 && row < ROWS) heat[row][col] = Math.max(heat[row][col], ex);
        }

        int colr = ex < 3 ? Neon.GREEN : ex < 10 ? Neon.YEL : Neon.RED;
        text(c, f("+%.1f dB", ex), cx, a.top + 54 * dp, 38, colr, Paint.Align.CENTER, true);
        text(c, hit ? "▲ HISS ABOVE BACKGROUND" : "background", cx, a.top + 76 * dp, 12, colr,
                Paint.Align.CENTER, hit);
        bar(c, 16 * dp, a.top + 88 * dp, w - 16 * dp, a.top + 106 * dp, ex / 25f, colr);
        boolean filtered = top < -135 && band == 0;
        text(c, filtered ? "⚠ this mic cuts off high sound – try BAND 8-16k"
                        : f("band %s · level %.0f dB · background %.0f dB", bandLabel(), smooth, base),
                cx, a.top + 124 * dp, 10, filtered ? Neon.YEL : Neon.GREY, Paint.Align.CENTER, false);

        RectF box = new RectF(16 * dp, a.top + 136 * dp, w - 16 * dp,
                a.top + 136 * dp + Math.min(a.height() * 0.42f, (w - 32 * dp) * ROWS / COLS));
        frame(c, box, mapBtn.on ? Neon.MAG : Neon.CYAN);
        if (mapBtn.on) drawMap(c, box);
        else drawSpectrum(c, box);

        RectF g = new RectF(16 * dp, box.bottom + 12 * dp, w - 16 * dp, Math.min(a.bottom - 58 * dp, box.bottom + 72 * dp));
        if (g.height() > 24 * dp) {
            frame(c, g, Neon.alpha(Neon.CYAN, 0x80));
            graph(c, hist, g, 0, Math.max(12, hist.max()), colr);
        }
        float y = a.bottom - 40 * dp;
        String[] tips = mapBtn.on
                ? new String[]{"Aim the BACK of the phone like a camera and sweep",
                "slowly across the area. Hot cells = loudest hiss.",
                "Then go in close – the reading rises near the leak."}
                : new String[]{"Hold the BOTTOM of the phone (mic) towards fittings,",
                "couplings and valves; move slowly. CAL in quiet air.",
                "Works within ~1 m on audible-to-high hiss."};
        for (String s : tips) {
            text(c, s, 16 * dp, y, 10, Neon.GREY, Paint.Align.LEFT, false);
            y += 14 * dp;
        }
    }

    private void drawMap(Canvas c, RectF box) {
        float cw = box.width() / COLS, ch = box.height() / ROWS;
        fill.setStyle(Paint.Style.FILL);
        int br = -1, bc = -1;
        float best = 3;
        for (int r = 0; r < ROWS; r++) {
            for (int k = 0; k < COLS; k++) {
                float v = heat[r][k];
                if (v > best) {
                    best = v;
                    br = r;
                    bc = k;
                }
                if (v <= 0.5f) continue;
                fill.setColor(heatColor(v));
                c.drawRect(box.left + k * cw + 1, box.top + r * ch + 1, box.left + (k + 1) * cw - 1,
                        box.top + (r + 1) * ch - 1, fill);
            }
        }
        line(c, box.centerX(), box.top, box.centerX(), box.bottom, Neon.alpha(Neon.CYAN, 0x30), 1);
        line(c, box.left, box.centerY(), box.right, box.centerY(), Neon.alpha(Neon.CYAN, 0x30), 1);
        if (br >= 0) {
            float hx = box.left + (bc + 0.5f) * cw, hy = box.top + (br + 0.5f) * ch;
            circle(c, hx, hy, Math.max(cw, ch) * 1.2f, Neon.WHITE, 2);
            float daz = (bc + 0.5f) / COLS * SPAN_AZ - SPAN_AZ / 2;
            float del = SPAN_EL / 2 - (br + 0.5f) / ROWS * SPAN_EL;
            text(c, f("LOUDEST %+.0f dB at %+.0f° %s, %+.0f° %s", best, Math.abs(daz), daz < 0 ? "L" : "R",
                    Math.abs(del), del < 0 ? "down" : "up"), box.centerX(), box.bottom - 6 * dp, 10,
                    Neon.WHITE, Paint.Align.CENTER, true);
        }
        float d = ((az - az0) % 360 + 540) % 360 - 180;
        float ax = box.left + (d + SPAN_AZ / 2) / SPAN_AZ * box.width();
        float ay = box.top + (SPAN_EL / 2 - el) / SPAN_EL * box.height();
        ax = Neon.clamp(ax, box.left, box.right);
        ay = Neon.clamp(ay, box.top, box.bottom);
        circle(c, ax, ay, 10 * dp, Neon.GREEN, 2);
        line(c, ax - 16 * dp, ay, ax + 16 * dp, ay, Neon.GREEN, 1.5f);
        line(c, ax, ay - 16 * dp, ax, ay + 16 * dp, Neon.GREEN, 1.5f);
        text(c, "◀ 60°", box.left + 4 * dp, box.top + 12 * dp, 9, Neon.GREY, Paint.Align.LEFT, false);
        text(c, "60° ▶", box.right - 4 * dp, box.top + 12 * dp, 9, Neon.GREY, Paint.Align.RIGHT, false);
    }

    private void drawSpectrum(Canvas c, RectF box) {
        int[] bd = BANDS[band];
        float fLo = 2000, fHi = SR / 2f;
        float bx0 = box.left + (bd[0] - fLo) / (fHi - fLo) * box.width();
        float bx1 = box.left + (bd[1] - fLo) / (fHi - fLo) * box.width();
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Neon.alpha(Neon.MAG, 0x22));
        c.drawRect(bx0, box.top, bx1, box.bottom, fill);
        int cols = (int) (box.width() / (3 * dp));
        synchronized (specDraw) {
            for (int i = 0; i < cols; i++) {
                float f0 = fLo + (fHi - fLo) * i / cols, f1 = fLo + (fHi - fLo) * (i + 1) / cols;
                int k0 = (int) (f0 * N / SR), k1 = Math.max(k0 + 1, (int) (f1 * N / SR));
                float v = -200;
                for (int k = k0; k < k1 && k < N / 2; k++) v = Math.max(v, specDraw[k]);
                float hgt = Neon.clamp((v + 130) / 90f, 0, 1) * box.height();
                boolean in = f0 >= bd[0] && f1 <= bd[1];
                fill.setColor(in ? Neon.MAG : Neon.CYAN);
                c.drawRect(box.left + i * 3 * dp, box.bottom - hgt, box.left + i * 3 * dp + 2 * dp, box.bottom, fill);
            }
        }
        String[] labs = {"2k", "8k", "16k", "24k"};
        float[] fr = {2000, 8000, 16000, 24000};
        for (int i = 0; i < labs.length; i++) {
            float x = box.left + (fr[i] - fLo) / (fHi - fLo) * box.width();
            text(c, labs[i], Neon.clamp(x, box.left + 8 * dp, box.right - 12 * dp), box.top + 12 * dp, 9,
                    Neon.GREY, Paint.Align.CENTER, false);
        }
    }
}
