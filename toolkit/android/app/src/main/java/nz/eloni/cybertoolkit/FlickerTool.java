package nz.eloni.cybertoolkit;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.util.Range;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Light flicker meter. With a very short exposure the camera's rolling shutter reads each sensor
 * line at a slightly different time, so flickering light (LED drivers, fluorescent ballasts)
 * shows as bands. Percent flicker = (max-min)/(max+min) per line over time; frequency comes
 * from the band spacing and the sensor's reported rolling-shutter skew.
 */
public class FlickerTool extends CamTool {

    private static final int FRAMES = 24;
    private float[][] hist;
    private int hIdx, hN, rows;
    private volatile float pct = -1, freq = -1, meanY;
    private final Ring pctHist = new Ring(200);
    private final Ring profile = new Ring(240);
    private volatile int iso;
    private long exposure;
    private boolean manual;
    private int frameN;

    public FlickerTool(MainActivity a) {
        super(a);
        btn("RESET", () -> {
            hN = 0;
            pctHist.clear();
        });
    }

    @Override
    String title() {
        return "LIGHT FLICKER";
    }

    @Override
    String aim() {
        return "Point the rear camera (top-left of back) at a plain WHITE wall or paper lit by the lamp - not at the lamp. Hold still.";
    }

    @Override
    int reqW() {
        return 1280;
    }

    @Override
    int reqH() {
        return 720;
    }

    @Override
    int step() {
        return 4;
    }

    @Override
    boolean gray() {
        return true;
    }

    @Override
    void onCamOpened() {
        if (cam == null) return;
        manual = cam.manualSensor();
        if (!manual) return;
        Range<Long> er = cam.expRange();
        Range<Integer> ir = cam.isoRange();
        exposure = Math.max(er == null ? 100_000L : er.getLower(), 250_000L); // 1/4000 s
        iso = ir == null ? 800 : Math.min(ir.getUpper(), Math.max(ir.getLower(), 800));
        applyManual();
    }

    private void applyManual() {
        final long ex = exposure;
        final int is = iso;
        cam.setup(b -> {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ex);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, is);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L);
        });
    }

    @Override
    void onCamFrame(Image img) {
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer Y = p.getBuffer();
        int rs = p.getRowStride(), ps = p.getPixelStride();
        int w = img.getWidth(), h = img.getHeight();
        if (hist == null || rows != h) {
            rows = h;
            hist = new float[FRAMES][h];
            hN = 0;
        }
        float[] prof = hist[hIdx];
        int x0 = w / 4, x1 = 3 * w / 4;
        double total = 0;
        for (int y = 0; y < h; y++) {
            int base = y * rs, s = 0, n = 0;
            for (int x = x0; x < x1; x += 4) {
                s += Y.get(base + x * ps) & 0xff;
                n++;
            }
            prof[y] = s / (float) n;
            total += prof[y];
        }
        meanY = (float) (total / h);
        hIdx = (hIdx + 1) % FRAMES;
        if (hN < FRAMES) hN++;
        frameN++;
        // keep exposure in a usable range by adjusting ISO
        if (manual && frameN % 15 == 0 && cam != null) {
            Range<Integer> ir = cam.isoRange();
            if (ir != null && (meanY < 70 || meanY > 170)) {
                int ni = (int) (iso * 115f / Math.max(5, meanY));
                ni = Math.max(ir.getLower(), Math.min(ir.getUpper(), ni));
                if (ni != iso) {
                    iso = ni;
                    hN = 0;
                    applyManual();
                }
            }
        }
        if (hN >= 8) analyse();
    }

    private void analyse() {
        int h = rows;
        float[] mean = new float[h];
        float[] pcts = new float[h / 8];
        int k = 0;
        for (int y = 0; y < h; y++) {
            float mx = 0, mn = 999, sm = 0;
            for (int f = 0; f < hN; f++) {
                float v = hist[f][y];
                mx = Math.max(mx, v);
                mn = Math.min(mn, v);
                sm += v;
            }
            mean[y] = sm / hN;
            if (y % 8 == 0 && k < pcts.length) pcts[k++] = (mx + mn) > 0 ? (mx - mn) / (mx + mn) : 0;
        }
        Arrays.sort(pcts, 0, k);
        float p = k > 0 ? pcts[k / 2] * 100 : 0;
        // remove tiny noise floor from sensor noise (~1-2%)
        pct = Math.max(0, p - 1.5f);
        // spatial frequency of the newest frame relative to the mean (cancels vignetting)
        float[] cur = hist[(hIdx - 1 + FRAMES) % FRAMES];
        float[] r = new float[h];
        for (int y = 0; y < h; y++) r[y] = mean[y] > 1 ? cur[y] / mean[y] - 1 : 0;
        double best = 0;
        int bestC = 0;
        for (int c = 1; c <= 40; c++) {
            double re = 0, im = 0;
            for (int y = 0; y < h; y += 2) {
                double ang = 2 * Math.PI * c * y / h;
                re += r[y] * Math.cos(ang);
                im += r[y] * Math.sin(ang);
            }
            double mag = re * re + im * im;
            if (mag > best) {
                best = mag;
                bestC = c;
            }
        }
        long skew = cam == null ? 0 : cam.skewNs;
        freq = (skew > 0 && pct > 3) ? (float) (bestC / (skew / 1e9)) : -1;
        synchronized (profile) {
            profile.clear();
            for (int y = 0; y < h; y += Math.max(1, h / 240)) profile.add(r[y]);
        }
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        float w = a.width(), cx = w / 2f;
        float p = pct;
        long now = System.currentTimeMillis();
        if (p >= 0 && now % 250 < 20) pctHist.add(p);
        String rating;
        int col;
        float f = freq > 0 ? freq : 100;
        // IEEE 1789: low risk below 0.08 x f, no observable effect below 0.0333 x f (f in Hz)
        float lowRisk = Math.min(100, 0.08f * f), noEffect = Math.min(100, 0.0333f * f);
        if (p < 0) { rating = "measuring…"; col = Neon.GREY; }
        else if (p <= noEffect) { rating = "NO OBSERVABLE EFFECT"; col = Neon.GREEN; }
        else if (p <= lowRisk) { rating = "LOW RISK"; col = Neon.CYAN; }
        else if (p <= 30) { rating = "NOTICEABLE FLICKER"; col = Neon.YEL; }
        else { rating = "HIGH FLICKER"; col = Neon.RED; }

        text(c, p < 0 ? "— %" : f("%.0f %%", p), cx, a.top + 50 * dp, 38, col, Paint.Align.CENTER, true);
        text(c, rating, cx, a.top + 72 * dp, 14, col, Paint.Align.CENTER, true);
        String fs = freq > 0 ? f("≈ %.0f Hz", freq) + (Math.abs(freq - 100) < 20 ? "  (mains ×2 – 50 Hz supply)"
                : freq > 300 ? "  (PWM driver)" : "") : "frequency: needs visible bands";
        text(c, fs, cx, a.top + 92 * dp, 12, Neon.MAG, Paint.Align.CENTER, false);
        if (p > 30 && (freq < 0 || freq < 200)) {
            text(c, "⚠ stroboscopic risk – rotating machines can look stopped", cx, a.top + 110 * dp, 11, Neon.RED,
                    Paint.Align.CENTER, true);
        }
        RectF pv = new RectF(16 * dp, a.top + 120 * dp, w * 0.55f, a.bottom - 60 * dp);
        drawPreview(c, pv, true);
        RectF g = new RectF(w * 0.58f, a.top + 120 * dp, w - 16 * dp, a.bottom - 60 * dp);
        frame(c, g, Neon.CYAN);
        synchronized (profile) {
            float m = Math.max(0.02f, profile.maxAbs());
            // vertical profile: draw as bars from centre line
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Neon.YEL);
            float rh = g.height() / Math.max(1, profile.n);
            for (int i = 0; i < profile.n; i++) {
                float v = profile.get(i) / m * (g.width() / 2 - 4 * dp);
                float yy = g.top + i * rh;
                c.drawRect(Math.min(g.centerX(), g.centerX() + v), yy, Math.max(g.centerX(), g.centerX() + v),
                        yy + Math.max(1, rh), fill);
            }
        }
        text(c, "bands", g.centerX(), g.bottom + 12 * dp, 9, Neon.GREY, Paint.Align.CENTER, false);
        RectF th = new RectF(16 * dp, a.bottom - 50 * dp, w - 16 * dp, a.bottom - 18 * dp);
        frame(c, th, Neon.alpha(Neon.CYAN, 0x80));
        graph(c, pctHist, th, 0, Math.max(20, pctHist.max()), col);
        String info = manual ? f("exposure 1/%d s · ISO %d · skew %.1f ms", exposure > 0 ? 1_000_000_000L / exposure : 0,
                iso, cam == null ? 0 : cam.skewNs / 1e6) : "manual exposure not supported – may under-read";
        text(c, info, cx, a.bottom - 4 * dp, 9, Neon.GREY, Paint.Align.CENTER, false);
    }
}
