package nz.eloni.cybertoolkit;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;

/** Machine vibration: RMS, dominant frequency, RPM, velocity estimate and live spectrum. */
public class VibrationTool extends Tool {

    private static final int N = 512;
    private final float[] buf = new float[N];
    private final long[] ts = new long[N];
    private int idx, count;
    private final float[] win = FFT.hann(N);
    private final float winSum = FFT.sum(win);
    private final float[] re = new float[N], im = new float[N];
    private final float[] spec = new float[N / 2];
    private final Ring wave = new Ring(N);
    private float fs, rms, p2p, peakF, peakA, velRms;
    private long lastCalc;
    private Btn hold;

    public VibrationTool(MainActivity a) {
        super(a);
        hold = btn("HOLD", () -> hold.on = !hold.on);
    }

    @Override
    String title() {
        return "VIBRATION METER";
    }

    @Override
    String aim() {
        return "Press it SCREEN-DOWN on the machine (with a case), or hold the LEFT edge hard against the bearing housing.";
    }

    @Override
    void onStart() {
        count = 0;
        listen(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (hold.on) return;
        float m = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1]
                + e.values[2] * e.values[2]);
        buf[idx] = m;
        ts[idx] = e.timestamp;
        idx = (idx + 1) % N;
        count++;
        wave.add(m);
    }

    private void analyse() {
        int oldest = idx;
        int newest = (idx - 1 + N) % N;
        double dur = (ts[newest] - ts[oldest]) / 1e9;
        if (dur <= 0) return;
        fs = (float) ((N - 1) / dur);
        float mean = 0, mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            float v = buf[(oldest + i) % N];
            mean += v;
            mn = Math.min(mn, v);
            mx = Math.max(mx, v);
        }
        mean /= N;
        p2p = mx - mn;
        double ss = 0;
        for (int i = 0; i < N; i++) {
            float v = buf[(oldest + i) % N] - mean;
            ss += v * v;
            re[i] = v * win[i];
            im[i] = 0;
        }
        rms = (float) Math.sqrt(ss / N);
        FFT.fft(re, im);
        int kMin = Math.max(1, (int) Math.ceil(2.0 * N / fs)); // ignore below 2 Hz (hand movement)
        int best = kMin;
        for (int k = 0; k < N / 2; k++) {
            spec[k] = 2 * (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) / winSum;
            if (k >= kMin && spec[k] > spec[best]) best = k;
        }
        peakF = best * fs / N;
        peakA = spec[best];
        velRms = peakF > 0 ? (float) (peakA / Math.sqrt(2) / (2 * Math.PI * peakF) * 1000) : 0;
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        float w = a.width();
        if (count < N) {
            text(c, "COLLECTING " + Math.min(100, count * 100 / N) + "%", w / 2, a.top + 70 * dp, 20,
                    Neon.YEL, Paint.Align.CENTER, true);
            text(c, "Place the phone firmly on the machine", w / 2, a.top + 100 * dp, 13, Neon.GREY,
                    Paint.Align.CENTER, false);
            return;
        }
        long now = System.currentTimeMillis();
        if (!hold.on && now - lastCalc > 150) {
            analyse();
            lastCalc = now;
        }
        // ISO 10816-style velocity bands (small machines) – indicative only
        String sev;
        int col;
        if (velRms < 0.71f) { sev = "GOOD"; col = Neon.GREEN; }
        else if (velRms < 1.8f) { sev = "SATISFACTORY"; col = Neon.CYAN; }
        else if (velRms < 4.5f) { sev = "UNSATISFACTORY"; col = Neon.YEL; }
        else { sev = "UNACCEPTABLE"; col = Neon.RED; }

        float y = a.top + 34 * dp;
        text(c, f("%.0f RPM", peakF * 60), 16 * dp, y, 28, Neon.MAG, Paint.Align.LEFT, true);
        text(c, f("%.1f Hz", peakF), w - 16 * dp, y, 20, Neon.MAG, Paint.Align.RIGHT, false);
        y += 26 * dp;
        text(c, f("RMS %.3f m/s²  (%.1f mg)   P-P %.2f", rms, rms / Neon.G * 1000, p2p), 16 * dp, y, 12,
                Neon.CYAN, Paint.Align.LEFT, false);
        y += 20 * dp;
        text(c, f("velocity ≈ %.2f mm/s  →  %s", velRms, sev), 16 * dp, y, 13, col, Paint.Align.LEFT, true);
        y += 18 * dp;
        text(c, f("sample rate %.0f Hz · reads up to %.0f Hz (%.0f RPM)", fs, fs / 2, fs * 30), 16 * dp, y,
                10, Neon.GREY, Paint.Align.LEFT, false);

        // spectrum
        RectF sp = new RectF(16 * dp, y + 16 * dp, w - 16 * dp, y + 16 * dp + a.height() * 0.38f);
        frame(c, sp, Neon.CYAN);
        int bins = N / 2;
        int cols = (int) Math.min(bins, sp.width() / (3 * dp));
        float colW = sp.width() / cols;
        float top = 0;
        for (int k = 1; k < bins; k++) top = Math.max(top, spec[k]);
        if (top <= 0) top = 1;
        fill.setStyle(Paint.Style.FILL);
        for (int i = 0; i < cols; i++) {
            int k0 = 1 + i * (bins - 1) / cols, k1 = 1 + (i + 1) * (bins - 1) / cols;
            float v = 0;
            for (int k = k0; k < Math.max(k1, k0 + 1); k++) v = Math.max(v, spec[k]);
            float hgt = v / top * (sp.height() - 4 * dp);
            fill.setColor(v >= top * 0.99f ? Neon.MAG : Neon.GREEN);
            c.drawRect(sp.left + i * colW, sp.bottom - hgt, sp.left + (i + 1) * colW - 1, sp.bottom, fill);
        }
        text(c, "0 Hz", sp.left, sp.bottom + 14 * dp, 10, Neon.GREY, Paint.Align.LEFT, false);
        text(c, f("%.0f Hz", fs / 2), sp.right, sp.bottom + 14 * dp, 10, Neon.GREY, Paint.Align.RIGHT, false);

        // waveform
        RectF wv = new RectF(16 * dp, sp.bottom + 30 * dp, w - 16 * dp, a.bottom - 8 * dp);
        if (wv.height() > 30 * dp) {
            frame(c, wv, Neon.alpha(Neon.CYAN, 0x80));
            float mid = 0;
            for (int k = 0; k < wave.n; k++) mid += wave.get(k);
            mid /= Math.max(1, wave.n);
            float amp = Math.max(0.05f, Math.max(Math.abs(wave.max() - mid), Math.abs(wave.min() - mid)));
            graph(c, wave, wv, mid - amp, mid + amp, Neon.YEL);
        }
    }
}
