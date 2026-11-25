package nz.eloni.cybertoolkit;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;

import java.nio.ByteBuffer;

/** Fingertip pulse (camera + flash photoplethysmography). A fun demo, not a medical device. */
public class PulseTool extends CamTool {

    private static final int CAP = 600;
    private final double[] t = new double[CAP];
    private final float[] v = new float[CAP];
    private int n, head;
    private volatile boolean finger;
    private volatile float bpm = -1, quality;
    private final Ring wave = new Ring(240);
    private long startMs, lastCalc;
    private boolean locked;

    public PulseTool(MainActivity a) {
        super(a);
        btn("RESTART", this::restart).color = Neon.RED;
    }

    private void restart() {
        n = 0;
        head = 0;
        bpm = -1;
        wave.clear();
        startMs = System.currentTimeMillis();
        locked = false;
        if (cam != null) cam.setup(null);
    }

    @Override
    String title() {
        return "PULSE (DEMO)";
    }

    @Override
    String aim() {
        return "Rest a fingertip lightly over the MAIN rear lens AND the flash beside the island. Don't press hard; keep still 15 s.";
    }

    @Override
    int reqW() {
        return 320;
    }

    @Override
    int reqH() {
        return 240;
    }

    @Override
    boolean wantPreview() {
        return false;
    }

    @Override
    void onCamOpened() {
        if (cam != null) cam.setTorch(true);
        restart();
    }

    @Override
    void onCamFrame(Image img) {
        Image.Plane[] pl = img.getPlanes();
        ByteBuffer Y = pl[0].getBuffer(), V = pl[2].getBuffer(), U = pl[1].getBuffer();
        int yr = pl[0].getRowStride(), yp = pl[0].getPixelStride();
        int ur = pl[1].getRowStride(), up = pl[1].getPixelStride();
        int w = img.getWidth(), h = img.getHeight();
        long sr = 0, sg = 0, sb = 0;
        int cnt = 0;
        for (int y = h / 4; y < 3 * h / 4; y += 4) {
            for (int x = w / 4; x < 3 * w / 4; x += 4) {
                int yy = Y.get(y * yr + x * yp) & 0xff;
                int uvi = (y >> 1) * ur + (x >> 1) * up;
                int u = (U.get(uvi) & 0xff) - 128, vv = (V.get(uvi) & 0xff) - 128;
                sr += yy + ((91881 * vv) >> 16);
                sg += yy - ((22554 * u + 46802 * vv) >> 16);
                sb += yy + ((116130 * u) >> 16);
                cnt++;
            }
        }
        float r = sr / (float) cnt, g = sg / (float) cnt, b = sb / (float) cnt;
        finger = r > 90 && r > g * 1.6f && r > b * 1.6f;
        if (!finger) {
            n = 0;
            return;
        }
        if (!locked && System.currentTimeMillis() - startMs > 2000 && cam != null) {
            locked = true;
            cam.setup(bld -> {
                bld.set(CaptureRequest.CONTROL_AE_LOCK, true);
                bld.set(CaptureRequest.CONTROL_AWB_LOCK, true);
            });
        }
        t[head] = img.getTimestamp() / 1e9;
        v[head] = r;
        head = (head + 1) % CAP;
        if (n < CAP) n++;
    }

    private void analyse() {
        if (n < 150) return;
        // resample to 30 Hz over the newest ~12 s
        int m = Math.min(n, CAP);
        double tEnd = t[(head - 1 + CAP) % CAP], tStart = t[(head - m + CAP) % CAP];
        double span = Math.min(12, tEnd - tStart);
        if (span < 6) return;
        int N = (int) (span * 30);
        float[] s = new float[N];
        int k = (head - m + CAP) % CAP;
        int used = 0;
        double t0 = tEnd - span;
        for (int i = 0; i < N; i++) {
            double ti = t0 + i / 30.0;
            while (used < m - 2 && t[(k + 1) % CAP] < ti) {
                k = (k + 1) % CAP;
                used++;
            }
            int k2 = (k + 1) % CAP;
            double dt = t[k2] - t[k];
            double fr = dt > 0 ? (ti - t[k]) / dt : 0;
            s[i] = (float) (v[k] + Neon.clamp((float) fr, 0, 1) * (v[k2] - v[k]));
        }
        // detrend with 1 s moving average, then autocorrelate 40-180 BPM
        float[] d = new float[N];
        for (int i = 0; i < N; i++) {
            float a = 0;
            int c = 0;
            for (int j = Math.max(0, i - 15); j < Math.min(N, i + 15); j++) {
                a += s[j];
                c++;
            }
            d[i] = s[i] - a / c;
        }
        synchronized (wave) {
            wave.clear();
            for (int i = Math.max(0, N - 240); i < N; i++) wave.add(-d[i]);
        }
        double e0 = 0;
        for (float x : d) e0 += x * x;
        int bestLag = -1;
        double best = 0;
        for (int lag = 10; lag <= 45; lag++) {
            double acc = 0;
            for (int i = 0; i + lag < N; i++) acc += d[i] * d[i + lag];
            if (acc > best) {
                best = acc;
                bestLag = lag;
            }
        }
        quality = e0 > 0 ? (float) (best / e0) : 0;
        bpm = bestLag > 0 && quality > 0.3f ? 60f * 30f / bestLag : -1;
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        float w = a.width(), cx = w / 2f;
        long now = System.currentTimeMillis();
        if (now - lastCalc > 500) {
            analyse();
            lastCalc = now;
        }
        if (cam == null || !cam.ready) {
            text(c, status(), cx, a.top + 60 * dp, 13, Neon.YEL, Paint.Align.CENTER, false);
            return;
        }
        boolean beat = bpm > 0 && (now % (long) (60000 / bpm)) < 150;
        dot(c, cx, a.top + 70 * dp, (beat ? 34 : 28) * dp, finger ? Neon.RED : Neon.GREY, beat);
        text(c, "♥", cx, a.top + 80 * dp, 28, Neon.WHITE, Paint.Align.CENTER, false);
        text(c, !finger ? "cover the lens + flash" : bpm > 0 ? f("%.0f BPM", bpm) : "reading… keep still",
                cx, a.top + 140 * dp, bpm > 0 && finger ? 34 : 16, finger ? Neon.GREEN : Neon.YEL, Paint.Align.CENTER, true);
        text(c, finger ? f("signal quality %.0f%%", quality * 100) : "", cx, a.top + 162 * dp, 11, Neon.CYAN,
                Paint.Align.CENTER, false);
        RectF g = new RectF(16 * dp, a.top + 180 * dp, w - 16 * dp, a.top + 280 * dp);
        frame(c, g, Neon.RED);
        synchronized (wave) {
            float m = Math.max(0.3f, wave.maxAbs());
            graph(c, wave, g, -m, m, Neon.RED);
        }
        text(c, "Not a medical device – for fun only.", cx, g.bottom + 26 * dp, 11, Neon.GREY, Paint.Align.CENTER, false);
    }
}
