package nz.eloni.cybertoolkit;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;

import java.util.Arrays;

/**
 * Magnetometer tool with two modes:
 * METAL - static field change (steel, magnets) with Geiger-style beeps.
 * AC FIELD - 50 Hz magnetic field from cables/equipment CARRYING CURRENT. Uses the fastest sample
 * rate the phone allows; if that is below 100 Hz it looks for the aliased 50 Hz tone instead.
 * Never a substitute for a voltage tester: a live cable with no load makes no magnetic field.
 */
public class MetalTool extends Tool {

    // ---- metal mode
    private final Ring hist = new Ring(300);
    private float base = Float.NaN, mag, delta, thr;
    private double accSum;
    private int accN;
    private long calStart, lastBeep;

    // ---- AC mode
    private static final int N = 256;
    private final float[][] ax = new float[3][N];
    private final long[] ts = new long[N];
    private int idx, count;
    private final float[] win = FFT.hann(N);
    private final float winSum = FFT.sum(win);
    private final float[] re = new float[N], im = new float[N];
    private final float[] spec = new float[N / 2];
    private final Ring acHist = new Ring(300);
    private float fs, fa, acAmp, snr, snrThr;
    private boolean blind, aliased;
    private long lastCalc;
    private String sensorName = "";

    private ToneGenerator tg;
    private Btn sound, vibe, modeBtn;
    private boolean ac;

    public MetalTool(MainActivity a) {
        super(a);
        thr = prefs.getFloat("metal_thr", 8f);
        snrThr = prefs.getFloat("ac_snr", 6f);
        modeBtn = btn("AC FIELD", this::toggleMode);
        modeBtn.color = Neon.YEL;
        btn("RECAL", this::recal);
        btn("SENS −", () -> sens(false));
        btn("SENS +", () -> sens(true));
        sound = btn("SOUND", () -> sound.on = !sound.on);
        vibe = btn("VIBE", () -> vibe.on = !vibe.on);
        sound.on = true;
    }

    private void sens(boolean more) {
        if (ac) {
            snrThr = Neon.clamp(snrThr + (more ? -1 : 1), 3, 30);
            prefs.edit().putFloat("ac_snr", snrThr).apply();
        } else {
            thr = Neon.clamp(thr + (more ? -2 : 2), 2, 80);
            prefs.edit().putFloat("metal_thr", thr).apply();
        }
    }

    private void toggleMode() {
        ac = !ac;
        modeBtn.on = ac;
        modeBtn.label = ac ? "METAL" : "AC FIELD";
        sm.unregisterListener(this);
        recal();
        register();
    }

    private void recal() {
        base = Float.NaN;
        accSum = 0;
        accN = 0;
        calStart = System.currentTimeMillis();
        hist.clear();
        acHist.clear();
        count = 0;
    }

    private void register() {
        if (ac) {
            // uncalibrated = less on-chip filtering; fall back to the normal sensor
            Sensor s = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED);
            if (s == null) s = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (s != null) {
                sensorName = s.getName();
                sm.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST);
            }
        } else {
            listen(Sensor.TYPE_MAGNETIC_FIELD, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    String title() {
        return ac ? "AC FIELD" : "METAL DETECTOR";
    }

    @Override
    void onStart() {
        recal();
        register();
        try {
            tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
        } catch (RuntimeException e) {
            tg = null;
        }
    }

    @Override
    void onStop() {
        if (tg != null) tg.release();
        tg = null;
    }

    private void beep(float strength) {
        long now = System.currentTimeMillis();
        long gap = (long) Math.max(60, 700 - strength * 600);
        if (now - lastBeep > gap) {
            lastBeep = now;
            if (sound.on && tg != null) tg.startTone(ToneGenerator.TONE_PROP_BEEP, 40);
            if (vibe.on) act.vibrate(30);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (ac) {
            for (int i = 0; i < 3; i++) ax[i][idx] = e.values[i];
            ts[idx] = e.timestamp;
            idx = (idx + 1) % N;
            count++;
            return;
        }
        float x = e.values[0], y = e.values[1], z = e.values[2];
        mag = (float) Math.sqrt(x * x + y * y + z * z);
        if (Float.isNaN(base)) {
            accSum += mag;
            accN++;
            if (System.currentTimeMillis() - calStart > 1500 && accN > 0) base = (float) (accSum / accN);
            return;
        }
        delta = Math.abs(mag - base);
        hist.add(delta);
        if (delta > thr) beep(Neon.clamp(delta / 80f, 0, 1));
    }

    // ------------------------------------------------------------------ AC maths

    private void analyseAc() {
        int oldest = idx, newest = (idx - 1 + N) % N;
        double dur = (ts[newest] - ts[oldest]) / 1e9;
        if (dur <= 0) return;
        fs = (float) ((N - 1) / dur);
        // where does 50 Hz land? (it aliases if the sensor samples below 100 Hz)
        if (fs >= 105) {
            fa = 50;
            aliased = false;
        } else {
            float k = Math.round(50f / fs);
            float f = Math.abs(50 - k * fs);
            if (f > fs / 2) f = fs - f;
            fa = f;
            aliased = true;
        }
        blind = fa < 4f || fa > fs / 2 - 1;
        float[] pow = new float[N / 2];
        for (int a = 0; a < 3; a++) {
            float mean = 0;
            for (int i = 0; i < N; i++) mean += ax[a][(oldest + i) % N];
            mean /= N;
            for (int i = 0; i < N; i++) {
                re[i] = (ax[a][(oldest + i) % N] - mean) * win[i];
                im[i] = 0;
            }
            FFT.fft(re, im);
            for (int k = 0; k < N / 2; k++) {
                float amp = 2 * (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) / winSum;
                pow[k] += amp * amp;
            }
        }
        for (int k = 0; k < N / 2; k++) spec[k] = (float) Math.sqrt(pow[k]);
        int kt = Math.round(fa * N / fs);
        float best = 0;
        for (int k = Math.max(1, kt - 2); k <= Math.min(N / 2 - 1, kt + 2); k++) best = Math.max(best, spec[k]);
        // noise floor: median of bins above 3 Hz, away from the target
        int kLow = (int) Math.ceil(3f * N / fs);
        float[] nz = new float[N / 2];
        int m = 0;
        for (int k = kLow; k < N / 2; k++) if (Math.abs(k - kt) > 4) nz[m++] = spec[k];
        Arrays.sort(nz, 0, m);
        float floor = m > 0 ? nz[m / 2] : 0;
        acAmp = best;
        snr = floor > 0 ? best / floor : 0;
        if (!blind) {
            acHist.add(snr);
            if (snr > snrThr) beep(Neon.clamp((snr - snrThr) / 30f, 0, 1));
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    void drawBody(Canvas c, RectF a) {
        if (ac) drawAc(c, a);
        else drawMetal(c, a);
    }

    private void drawAc(Canvas c, RectF a) {
        float w = a.width(), cx = w / 2f;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Neon.alpha(Neon.RED, 0x30));
        c.drawRect(0, a.top, w, a.top + 40 * dp, fill);
        text(c, "⚠ LOADED cables only – NEVER use to prove dead", cx, a.top + 25 * dp, 12, Neon.RED,
                Paint.Align.CENTER, true);
        if (count < N) {
            text(c, "SAMPLING " + Math.min(100, count * 100 / N) + "%", cx, a.top + 100 * dp, 18, Neon.YEL,
                    Paint.Align.CENTER, true);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCalc > 200) {
            analyseAc();
            lastCalc = now;
        }
        float y = a.top + 64 * dp;
        text(c, f("magnetometer %.0f samples/s", fs), 16 * dp, y, 12, Neon.CYAN, Paint.Align.LEFT, false);
        y += 17 * dp;
        String how = blind ? "✗ 50 Hz is invisible at this rate (alias ≈ 0 Hz)"
                : aliased ? f("✓ 50 Hz seen as an alias at %.1f Hz", fa) : "✓ 50 Hz measured directly";
        text(c, how, 16 * dp, y, 12, blind ? Neon.RED : Neon.GREEN, Paint.Align.LEFT, false);
        y += 12 * dp;
        if (blind) {
            y += 30 * dp;
            String[] msg = {
                    "This phone's magnetometer is too slow, or",
                    "samples at exactly 100 Hz, so it cannot see",
                    "mains-frequency fields. Use a clamp meter,",
                    "cable tracer or NCV tester instead."};
            for (String s : msg) {
                text(c, s, cx, y, 13, Neon.YEL, Paint.Align.CENTER, false);
                y += 20 * dp;
            }
            text(c, sensorName, cx, y + 10 * dp, 10, Neon.GREY, Paint.Align.CENTER, false);
            return;
        }
        boolean hit = snr > snrThr;
        int col = hit ? Neon.RED : Neon.GREEN;
        y += 46 * dp;
        text(c, f("%.0f×", snr), cx, y, 40, col, Paint.Align.CENTER, true);
        y += 22 * dp;
        text(c, hit ? "▲ 50 Hz FIELD – CURRENT FLOWING NEARBY" : "no 50 Hz field", cx, y, 13, col,
                Paint.Align.CENTER, hit);
        y += 18 * dp;
        text(c, f("signal %.3f µT · alert above %.0f× noise", acAmp, snrThr), cx, y, 11, Neon.CYAN,
                Paint.Align.CENTER, false);
        y += 12 * dp;
        bar(c, 16 * dp, y, w - 16 * dp, y + 18 * dp, snr / (snrThr * 4), col);
        float tx = 16 * dp + (w - 32 * dp) * 0.25f;
        line(c, tx, y - 4 * dp, tx, y + 22 * dp, Neon.MAG, 2);

        // spectrum (analysis runs on this thread, so spec is safe to read)
        RectF sp = new RectF(16 * dp, y + 36 * dp, w - 16 * dp, y + 36 * dp + 90 * dp);
        frame(c, sp, Neon.CYAN);
        float top = 1e-6f;
        for (int k = 1; k < N / 2; k++) top = Math.max(top, spec[k]);
        float bw = sp.width() / (N / 2f);
        int kt = Math.round(fa * N / fs);
        for (int k = 1; k < N / 2; k++) {
            float hgt = spec[k] / top * (sp.height() - 4 * dp);
            fill.setColor(Math.abs(k - kt) <= 2 ? Neon.MAG : Neon.GREEN);
            c.drawRect(sp.left + k * bw, sp.bottom - hgt, sp.left + (k + 1) * bw - 1, sp.bottom, fill);
        }
        text(c, "0 Hz", sp.left, sp.bottom + 13 * dp, 9, Neon.GREY, Paint.Align.LEFT, false);
        text(c, f("%.0f Hz   (magenta = 50 Hz)", fs / 2), sp.right, sp.bottom + 13 * dp, 9, Neon.GREY,
                Paint.Align.RIGHT, false);

        RectF g = new RectF(16 * dp, sp.bottom + 26 * dp, w - 16 * dp,
                Math.min(a.bottom - 60 * dp, sp.bottom + 106 * dp));
        if (g.height() > 30 * dp) {
            frame(c, g, Neon.alpha(Neon.CYAN, 0x80));
            float hi = Math.max(snrThr * 3, acHist.max());
            float ty = g.bottom - snrThr / hi * g.height();
            line(c, g.left, ty, g.right, ty, Neon.alpha(Neon.MAG, 0x80), 1);
            graph(c, acHist, g, 0, hi, col);
        }
        y = a.bottom - 40 * dp;
        text(c, "Keep the phone STILL, slide it slowly along the wall.", 16 * dp, y, 11, Neon.GREY,
                Paint.Align.LEFT, false);
        y += 15 * dp;
        text(c, "Turn on a heater/kettle on the circuit to trace it.", 16 * dp, y, 11, Neon.GREY,
                Paint.Align.LEFT, false);
        y += 15 * dp;
        text(c, "Twin cables cancel – expect a few cm range at best.", 16 * dp, y, 11, Neon.GREY,
                Paint.Align.LEFT, false);
    }

    private void drawMetal(Canvas c, RectF a) {
        float w = a.width(), cx = w / 2f;
        if (Float.isNaN(base)) {
            text(c, "CALIBRATING…", cx, a.top + 70 * dp, 22, Neon.YEL, Paint.Align.CENTER, true);
            text(c, "hold the phone away from metal", cx, a.top + 100 * dp, 13, Neon.GREY,
                    Paint.Align.CENTER, false);
            return;
        }
        boolean hit = delta > thr;
        int col = hit ? Neon.RED : Neon.GREEN;
        text(c, f("%.1f µT", delta), cx, a.top + 66 * dp, 40, col, Paint.Align.CENTER, true);
        text(c, hit ? "▲ METAL / MAGNET ▲" : "scanning…", cx, a.top + 96 * dp, 15, col,
                Paint.Align.CENTER, hit);
        bar(c, 16 * dp, a.top + 116 * dp, w - 16 * dp, a.top + 140 * dp, delta / (thr * 4), col);
        float tx = 16 * dp + (w - 32 * dp) * 0.25f;
        line(c, tx, a.top + 110 * dp, tx, a.top + 146 * dp, Neon.MAG, 2);

        RectF g = new RectF(16 * dp, a.top + 170 * dp, w - 16 * dp, a.top + 290 * dp);
        frame(c, g, Neon.CYAN);
        float top = Math.max(thr * 3, hist.max());
        float ty = g.bottom - (thr / top) * g.height();
        line(c, g.left, ty, g.right, ty, Neon.alpha(Neon.MAG, 0x80), 1);
        graph(c, hist, g, 0, top, col);

        float y = g.bottom + 30 * dp;
        text(c, f("field %.1f µT   base %.1f   alert > %.0f", mag, base, thr), 16 * dp, y, 12,
                Neon.CYAN, Paint.Align.LEFT, false);
        y += 26 * dp;
        String[] tips = {
                "Sensor is usually near the top of the phone.",
                "Finds steel: screws, nails, studs, rebar, magnets.",
                "Tap AC FIELD to trace cables that carry current.",
                "VIBE is off by default: the motor disturbs the sensor."};
        for (String s : tips) {
            text(c, s, 16 * dp, y, 11, Neon.GREY, Paint.Align.LEFT, false);
            y += 17 * dp;
        }
    }
}
