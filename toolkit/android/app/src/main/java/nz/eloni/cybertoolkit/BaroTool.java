package nz.eloni.cybertoolkit;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;

/** Barometer: pressure, altitude, relative height and floor counter. */
public class BaroTool extends Tool {

    private float pr = Float.NaN, ref = Float.NaN;
    private final Ring hist = new Ring(600);
    private long lastAdd;
    private float floorH;

    public BaroTool(MainActivity a) {
        super(a);
        floorH = prefs.getFloat("floor_h", 3.0f);
        btn("ZERO", () -> ref = pr);
        btn("FLOOR −", () -> setFloor(floorH - 0.1f));
        btn("FLOOR +", () -> setFloor(floorH + 0.1f));
        btn("CLEAR", () -> {
            ref = Float.NaN;
            hist.clear();
        });
    }

    private void setFloor(float v) {
        floorH = Neon.clamp(v, 2.4f, 5f);
        prefs.edit().putFloat("floor_h", floorH).apply();
    }

    @Override
    String title() {
        return "BAROMETER";
    }

    @Override
    void onStart() {
        listen(Sensor.TYPE_PRESSURE, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float v = e.values[0];
        pr = Float.isNaN(pr) ? v : pr + 0.15f * (v - pr);
        if (Float.isNaN(ref)) ref = pr;
        long now = System.currentTimeMillis();
        if (now - lastAdd > 1000) {
            lastAdd = now;
            hist.add(pr);
        }
    }

    private static float alt(float hpa) {
        return SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hpa);
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        float w = a.width(), cx = w / 2f;
        if (!has(Sensor.TYPE_PRESSURE)) {
            text(c, "NO BAROMETER IN THIS PHONE", cx, a.top + 70 * dp, 16, Neon.RED, Paint.Align.CENTER, true);
            return;
        }
        if (Float.isNaN(pr)) {
            text(c, "Waiting for barometer…", cx, a.top + 70 * dp, 14, Neon.YEL, Paint.Align.CENTER, false);
            return;
        }
        float rel = alt(pr) - alt(ref);
        float floors = rel / floorH;
        text(c, f("%.2f hPa", pr), cx, a.top + 54 * dp, 32, Neon.CYAN, Paint.Align.CENTER, true);
        text(c, f("altitude ≈ %.0f m (standard atmosphere)", alt(pr)), cx, a.top + 78 * dp, 11, Neon.GREY,
                Paint.Align.CENTER, false);
        text(c, f("%+.1f m", rel), cx * 0.6f, a.top + 130 * dp, 34, Neon.GREEN, Paint.Align.CENTER, true);
        text(c, "HEIGHT FROM ZERO", cx * 0.6f, a.top + 150 * dp, 10, Neon.GREY, Paint.Align.CENTER, false);

        // building
        float bx = w * 0.72f, bw = w * 0.2f, fh = 16 * dp;
        float base = a.top + 230 * dp;
        int cur = Math.round(floors);
        for (int k = -3; k <= 6; k++) {
            float yy = base - (k + 3) * fh;
            boolean me = k == cur;
            RectF r = new RectF(bx, yy - fh + 2 * dp, bx + bw, yy);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(me ? Neon.alpha(Neon.MAG, 0xB0) : Neon.alpha(Neon.CYAN, k == 0 ? 0x50 : 0x20));
            c.drawRect(r, fill);
            if (me || k == 0) text(c, k == 0 ? "0" : f("%+d", k), bx - 6 * dp, yy - 3 * dp, 10,
                    me ? Neon.MAG : Neon.CYAN, Paint.Align.RIGHT, false);
        }
        text(c, f("FLOOR %+d", cur), bx + bw / 2, base + 18 * dp, 13, Neon.MAG, Paint.Align.CENTER, true);

        RectF g = new RectF(16 * dp, base + 36 * dp, w - 16 * dp, Math.min(a.bottom - 60 * dp, base + 196 * dp));
        frame(c, g, Neon.CYAN);
        float lo = hist.min(), hi = hist.max();
        if (hi - lo < 0.3f) {
            float mid = (hi + lo) / 2;
            lo = mid - 0.15f;
            hi = mid + 0.15f;
        }
        graph(c, hist, g, lo, hi, Neon.CYAN);
        text(c, f("%.2f", hi), g.left + 4 * dp, g.top + 12 * dp, 9, Neon.GREY, Paint.Align.LEFT, false);
        text(c, f("%.2f", lo), g.left + 4 * dp, g.bottom - 4 * dp, 9, Neon.GREY, Paint.Align.LEFT, false);
        float y = g.bottom + 22 * dp;
        text(c, f("floor height %.1f m · ~0.12 hPa per metre", floorH), 16 * dp, y, 11, Neon.GREY,
                Paint.Align.LEFT, false);
        y += 16 * dp;
        text(c, "ZERO on the ground floor, then ride the lift.", 16 * dp, y, 11, Neon.GREY, Paint.Align.LEFT, false);
        y += 16 * dp;
        text(c, "Weather and air-con drift the reading over time.", 16 * dp, y, 11, Neon.GREY,
                Paint.Align.LEFT, false);
    }
}
