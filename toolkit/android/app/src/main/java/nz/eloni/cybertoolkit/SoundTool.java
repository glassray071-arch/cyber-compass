package nz.eloni.cybertoolkit;

import android.Manifest;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.AudioRecord;

/** Sound level (approx dB), pitch / note finder and 50 Hz mains-hum detector. */
public class SoundTool extends Tool {

    private static final int SR = 44100, N = 4096;
    private static final String[] NOTES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    private final float[] win = FFT.hann(N);
    private final float winSum = FFT.sum(win);
    private final float[] spec = new float[N / 2];
    private final float[] specDraw = new float[N / 2];
    private final Ring hist = new Ring(300);
    private volatile boolean go;
    private Thread th;
    private AudioRecord rec;
    private volatile float dbfs = -120, peakF, humRatio;
    private float cal, max = -999, sum;
    private int cnt;
    private String status = "";

    public SoundTool(MainActivity a) {
        super(a);
        cal = prefs.getFloat("sound_cal", 94f);
        btn("CAL −", () -> setCal(cal - 1));
        btn("CAL +", () -> setCal(cal + 1));
        btn("RESET", () -> {
            max = -999;
            sum = 0;
            cnt = 0;
            hist.clear();
        });
    }

    private void setCal(float v) {
        cal = Neon.clamp(v, 60, 130);
        prefs.edit().putFloat("sound_cal", cal).apply();
    }

    @Override
    String title() {
        return "SOUND METER";
    }

    @Override
    void onStart() {
        act.needPerms(new String[]{Manifest.permission.RECORD_AUDIO}, this::startRec,
                () -> status = "MICROPHONE PERMISSION DENIED");
    }

    private void startRec() {
        rec = Audio.open(act, SR, N * 4);
        if (rec == null) {
            status = "MICROPHONE BUSY OR UNAVAILABLE";
            return;
        }
        go = true;
        th = new Thread(this::loop, "sound");
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
            double ss = 0;
            for (int i = 0; i < N; i++) {
                float v = b[i] / 32768f;
                ss += v * v;
                re[i] = v * win[i];
                im[i] = 0;
            }
            float rms = (float) Math.sqrt(ss / N);
            dbfs = (float) (20 * Math.log10(Math.max(rms, 1e-7)));
            FFT.fft(re, im);
            int best = 0;
            int kMin = (int) (40f * N / SR);
            for (int k = 0; k < N / 2; k++) {
                spec[k] = 2 * (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) / winSum;
                if (k >= kMin && spec[k] > spec[best]) best = k;
            }
            // parabolic interpolation for a finer pitch estimate
            float f = best;
            if (best > 0 && best < N / 2 - 1) {
                float l = spec[best - 1], m = spec[best], r = spec[best + 1];
                float den = l - 2 * m + r;
                if (den != 0) f = best + 0.5f * (l - r) / den;
            }
            peakF = f * SR / N;
            // hum: energy at 50/100/150 Hz vs the band 30-400 Hz
            float hum = 0, band = 0;
            int k30 = (int) (30f * N / SR), k400 = (int) (400f * N / SR);
            for (int k = k30; k <= k400; k++) band += spec[k];
            for (int hz = 50; hz <= 150; hz += 50) {
                int k = Math.round(hz * (float) N / SR);
                hum += Math.max(spec[k], Math.max(spec[k - 1], spec[k + 1]));
            }
            humRatio = band > 0 ? hum / band : 0;
            synchronized (specDraw) {
                System.arraycopy(spec, 0, specDraw, 0, spec.length);
            }
        }
    }

    private static String note(float f) {
        if (f < 20) return "";
        double n = 12 * Math.log(f / 440.0) / Math.log(2) + 69;
        int ni = (int) Math.round(n);
        int cents = (int) Math.round((n - ni) * 100);
        return NOTES[((ni % 12) + 12) % 12] + (ni / 12 - 1) + f("  %+d¢", cents);
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        float w = a.width(), cx = w / 2f;
        if (!status.isEmpty() && rec == null) {
            text(c, status, cx, a.top + 60 * dp, 14, Neon.RED, Paint.Align.CENTER, false);
            return;
        }
        float db = dbfs + cal;
        if (dbfs > -119) {
            hist.add(db);
            max = Math.max(max, db);
            sum += db;
            cnt++;
        }
        int col = db < 70 ? Neon.GREEN : db < 85 ? Neon.YEL : Neon.RED;
        text(c, f("%.0f dB", db), cx, a.top + 66 * dp, 44, col, Paint.Align.CENTER, true);
        text(c, f("max %.0f   avg %.0f   (approx, uncalibrated)", max < -900 ? 0 : max,
                cnt == 0 ? 0 : sum / cnt), cx, a.top + 92 * dp, 11, Neon.CYAN, Paint.Align.CENTER, false);
        bar(c, 16 * dp, a.top + 104 * dp, w - 16 * dp, a.top + 124 * dp, (db - 30) / 90f, col);
        float x85 = 16 * dp + (w - 32 * dp) * (55f / 90f);
        line(c, x85, a.top + 100 * dp, x85, a.top + 128 * dp, Neon.MAG, 2);
        text(c, "85", x85, a.top + 140 * dp, 9, Neon.MAG, Paint.Align.CENTER, false);
        if (db >= 85) {
            text(c, "HEARING PROTECTION ZONE", cx, a.top + 156 * dp, 13, Neon.RED, Paint.Align.CENTER, true);
        }

        float y = a.top + 182 * dp;
        text(c, f("PITCH %.1f Hz   %s", peakF, note(peakF)), 16 * dp, y, 14, Neon.MAG, Paint.Align.LEFT, true);
        y += 22 * dp;
        boolean hum = humRatio > 0.18f && db > 35;
        text(c, hum ? f("⚡ MAINS HUM 50/100 Hz DETECTED (%.0f%%)", humRatio * 100)
                        : f("no mains hum (%.0f%%)", humRatio * 100), 16 * dp, y, 12,
                hum ? Neon.YEL : Neon.GREY, Paint.Align.LEFT, hum);

        // log-frequency spectrum 30 Hz – 16 kHz
        RectF sp = new RectF(16 * dp, y + 16 * dp, w - 16 * dp, y + 16 * dp + a.height() * 0.3f);
        frame(c, sp, Neon.CYAN);
        int cols = (int) (sp.width() / (3 * dp));
        double lo = Math.log10(30), hi = Math.log10(16000);
        fill.setStyle(Paint.Style.FILL);
        synchronized (specDraw) {
            for (int i = 0; i < cols; i++) {
                double f0 = Math.pow(10, lo + (hi - lo) * i / cols), f1 = Math.pow(10, lo + (hi - lo) * (i + 1) / cols);
                int k0 = (int) (f0 * N / SR), k1 = Math.max(k0 + 1, (int) (f1 * N / SR));
                float v = 0;
                for (int k = k0; k < k1 && k < N / 2; k++) v = Math.max(v, specDraw[k]);
                float dbv = (float) (20 * Math.log10(Math.max(v, 1e-7)));
                float hgt = Neon.clamp((dbv + 100) / 90f, 0, 1) * sp.height();
                fill.setColor(i * 3 < cols ? Neon.MAG : (i * 3 < 2 * cols ? Neon.CYAN : Neon.GREEN));
                c.drawRect(sp.left + i * 3 * dp, sp.bottom - hgt, sp.left + i * 3 * dp + 2 * dp, sp.bottom, fill);
            }
        }
        String[] labs = {"50", "100", "1k", "10k"};
        float[] fr = {50, 100, 1000, 10000};
        for (int i = 0; i < labs.length; i++) {
            float x = sp.left + (float) ((Math.log10(fr[i]) - lo) / (hi - lo)) * sp.width();
            text(c, labs[i], x, sp.bottom + 13 * dp, 9, Neon.GREY, Paint.Align.CENTER, false);
        }
        RectF g = new RectF(16 * dp, sp.bottom + 24 * dp, w - 16 * dp, a.bottom - 22 * dp);
        if (g.height() > 30 * dp) {
            frame(c, g, Neon.alpha(Neon.CYAN, 0x80));
            graph(c, hist, g, 30, 120, col);
        }
        text(c, f("CAL offset %.0f – match to a real meter with CAL −/+", cal), cx, a.bottom - 6 * dp, 9,
                Neon.GREY, Paint.Align.CENTER, false);
    }
}
