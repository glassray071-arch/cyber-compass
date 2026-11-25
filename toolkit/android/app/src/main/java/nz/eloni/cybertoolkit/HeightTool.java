package nz.eloni.cybertoolkit;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;

/**
 * Height and distance by angle: camera viewfinder + tilt sensor. Aim at the BASE of a pole,
 * wall or tree to get its distance (from your eye height), then at the TOP to get its height.
 */
public class HeightTool extends CamTool {

    private float[] g;
    private float eye;
    private float baseEl = Float.NaN, topEl = Float.NaN;

    public HeightTool(MainActivity a) {
        super(a);
        eye = prefs.getFloat("eye_h", 1.5f);
        btn("BASE", () -> {
            float e = elev();
            if (e < -1) {
                baseEl = e;
                topEl = Float.NaN;
            } else {
                act.toast("Aim DOWN at the base (where it meets the ground)");
            }
        }).color = Neon.GREEN;
        btn("TOP", () -> {
            if (Float.isNaN(baseEl)) act.toast("Mark the BASE first");
            else topEl = elev();
        }).color = Neon.MAG;
        btn("EYE −", () -> setEye(eye - 0.05f));
        btn("EYE +", () -> setEye(eye + 0.05f));
        btn("RESET", () -> {
            baseEl = Float.NaN;
            topEl = Float.NaN;
        });
    }

    private void setEye(float v) {
        eye = Neon.clamp(v, 0.5f, 3f);
        prefs.edit().putFloat("eye_h", eye).apply();
    }

    @Override
    String title() {
        return "HEIGHT METER";
    }

    @Override
    String aim() {
        return "Hold the phone upright at eye level, rear camera facing the object. Stand on the same ground level as its base.";
    }

    @Override
    void onToolStart() {
        listen(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (g == null) g = e.values.clone();
        for (int i = 0; i < 3; i++) g[i] += 0.1f * (e.values[i] - g[i]);
    }

    /** Elevation of the rear-camera axis above the horizon, degrees. */
    private float elev() {
        if (g == null) return 0;
        float n = (float) Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
        return (float) Math.toDegrees(Math.asin(Neon.clamp(-g[2] / n, -1, 1)));
    }

    private float roll() {
        return g == null ? 0 : (float) Math.toDegrees(Math.atan2(g[0], g[1]));
    }

    private float distFor(float el) {
        return (float) (eye / Math.tan(Math.toRadians(-el)));
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        float w = a.width();
        RectF pv = new RectF(0, a.top, w, a.bottom - 92 * dp);
        drawPreview(c, pv, true);
        float cx = pv.centerX(), cy = pv.centerY();
        float el = elev(), rl = roll();
        boolean upright = Math.abs(rl) < 3;
        // horizon tilt guide
        double rr = Math.toRadians(-rl);
        float L = w * 0.3f;
        line(c, cx - L * (float) Math.cos(rr), cy - L * (float) Math.sin(rr), cx + L * (float) Math.cos(rr),
                cy + L * (float) Math.sin(rr), upright ? Neon.alpha(Neon.GREEN, 0x90) : Neon.alpha(Neon.RED, 0xC0), 1.5f);
        circle(c, cx, cy, 14 * dp, Neon.GREEN, 2);
        line(c, cx - 30 * dp, cy, cx + 30 * dp, cy, Neon.GREEN, 1.5f);
        line(c, cx, cy - 30 * dp, cx, cy + 30 * dp, Neon.GREEN, 1.5f);
        text(c, f("%+.1f°", el), cx + 20 * dp, cy - 18 * dp, 14, Neon.GREEN, Paint.Align.LEFT, true);
        if (!upright) text(c, "keep phone upright", cx, pv.top + 24 * dp, 12, Neon.RED, Paint.Align.CENTER, true);

        float y = pv.bottom + 24 * dp;
        if (Float.isNaN(baseEl)) {
            String live = el < -1 ? f("ground distance at crosshair ≈ %.1f m", distFor(el)) : "aim at the BASE and tap BASE";
            text(c, live, 16 * dp, y, 13, Neon.CYAN, Paint.Align.LEFT, false);
        } else {
            float d = distFor(baseEl);
            text(c, f("DISTANCE %.1f m", d), 16 * dp, y, 16, Neon.GREEN, Paint.Align.LEFT, true);
            float tEl = Float.isNaN(topEl) ? el : topEl;
            float hgt = eye + d * (float) Math.tan(Math.toRadians(tEl));
            text(c, f("HEIGHT %.1f m", hgt), w - 16 * dp, y, 16, Float.isNaN(topEl) ? Neon.YEL : Neon.MAG,
                    Paint.Align.RIGHT, true);
            y += 18 * dp;
            text(c, Float.isNaN(topEl) ? "now aim at the TOP (live height shown) and tap TOP" : "measured – RESET for another",
                    16 * dp, y, 11, Neon.GREY, Paint.Align.LEFT, false);
        }
        y += 22 * dp;
        text(c, f("eye/camera height %.2f m · works best 5–40 m away on flat ground", eye), 16 * dp, y, 10, Neon.GREY,
                Paint.Align.LEFT, false);
        y += 15 * dp;
        text(c, "Rough guide only – not for clearance compliance.", 16 * dp, y, 10, Neon.YEL, Paint.Align.LEFT, false);
    }
}
