package nz.eloni.cybertoolkit;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;

/** Magnetometer metal / magnet finder with Geiger-style beeps. */
public class MetalTool extends Tool {

    private final Ring hist = new Ring(300);
    private float base = Float.NaN, mag, delta, thr;
    private double accSum;
    private int accN;
    private long calStart, lastBeep;
    private ToneGenerator tg;
    private Btn sound, vibe;

    public MetalTool(MainActivity a) {
        super(a);
        thr = prefs.getFloat("metal_thr", 8f);
        btn("RECAL", this::recal);
        btn("SENS −", () -> setThr(thr + 2));
        btn("SENS +", () -> setThr(thr - 2));
        sound = btn("SOUND", () -> sound.on = !sound.on);
        vibe = btn("VIBE", () -> vibe.on = !vibe.on);
        sound.on = true;
    }

    private void setThr(float t) {
        thr = Neon.clamp(t, 2, 80);
        prefs.edit().putFloat("metal_thr", thr).apply();
    }

    private void recal() {
        base = Float.NaN;
        accSum = 0;
        accN = 0;
        calStart = System.currentTimeMillis();
        hist.clear();
    }

    @Override
    String title() {
        return "METAL DETECTOR";
    }

    @Override
    void onStart() {
        recal();
        listen(Sensor.TYPE_MAGNETIC_FIELD, SensorManager.SENSOR_DELAY_GAME);
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

    @Override
    public void onSensorChanged(SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        mag = (float) Math.sqrt(x * x + y * y + z * z);
        long now = System.currentTimeMillis();
        if (Float.isNaN(base)) {
            accSum += mag;
            accN++;
            if (now - calStart > 1500 && accN > 0) base = (float) (accSum / accN);
            return;
        }
        delta = Math.abs(mag - base);
        hist.add(delta);
        if (delta > thr) {
            long gap = (long) Math.max(60, 700 - delta * 8);
            if (now - lastBeep > gap) {
                lastBeep = now;
                if (sound.on && tg != null) tg.startTone(ToneGenerator.TONE_PROP_BEEP, 40);
                if (vibe.on) act.vibrate(30);
            }
        }
    }

    @Override
    void drawBody(Canvas c, RectF a) {
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
        // threshold marker
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
        text(c, "Sensor is usually near the top of the phone.", 16 * dp, y, 11, Neon.GREY,
                Paint.Align.LEFT, false);
        y += 17 * dp;
        text(c, "Finds steel: screws, nails, studs, rebar, magnets.", 16 * dp, y, 11, Neon.GREY,
                Paint.Align.LEFT, false);
        y += 17 * dp;
        text(c, "Does NOT find live wires – use a proper tester.", 16 * dp, y, 11, Neon.YEL,
                Paint.Align.LEFT, false);
        y += 17 * dp;
        text(c, "VIBE is off by default: the motor disturbs the sensor.", 16 * dp, y, 11, Neon.GREY,
                Paint.Align.LEFT, false);
    }
}
