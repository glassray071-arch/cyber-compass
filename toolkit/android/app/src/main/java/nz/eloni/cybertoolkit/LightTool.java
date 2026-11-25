package nz.eloni.cybertoolkit;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;

/** Lux meter with min / max / average and rough task-lighting guide. */
public class LightTool extends Tool {

    private final Ring hist = new Ring(400);
    private float lux = -1, mn = Float.MAX_VALUE, mx;
    private double sum;
    private long n;
    private Btn hold;

    public LightTool(MainActivity a) {
        super(a);
        btn("RESET", () -> {
            mn = Float.MAX_VALUE;
            mx = 0;
            sum = 0;
            n = 0;
            hist.clear();
        });
        hold = btn("HOLD", () -> hold.on = !hold.on);
    }

    @Override
    String title() {
        return "LIGHT METER";
    }

    @Override
    String aim() {
        return "The light sensor is under the screen at the TOP, by the selfie camera. Face the screen at the light and keep the top uncovered.";
    }

    @Override
    void onStart() {
        listen(Sensor.TYPE_LIGHT, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (hold.on) return;
        lux = e.values[0];
        mn = Math.min(mn, lux);
        mx = Math.max(mx, lux);
        sum += lux;
        n++;
        hist.add(lux);
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        float w = a.width(), cx = w / 2f;
        if (lux < 0) {
            text(c, "Waiting for light sensor…", cx, a.top + 60 * dp, 14, Neon.YEL, Paint.Align.CENTER, false);
            return;
        }
        text(c, f("%,.0f", lux), cx, a.top + 72 * dp, 46, Neon.YEL, Paint.Align.CENTER, true);
        text(c, "LUX", cx, a.top + 96 * dp, 14, Neon.CYAN, Paint.Align.CENTER, false);
        bar(c, 16 * dp, a.top + 112 * dp, w - 16 * dp, a.top + 134 * dp,
                (float) (Math.log10(1 + lux) / 5), Neon.YEL);
        text(c, f("min %,.0f   max %,.0f   avg %,.0f", mn == Float.MAX_VALUE ? 0 : mn, mx,
                n == 0 ? 0 : sum / n), cx, a.top + 160 * dp, 13, Neon.CYAN, Paint.Align.CENTER, false);

        RectF g = new RectF(16 * dp, a.top + 176 * dp, w - 16 * dp, a.top + 266 * dp);
        frame(c, g, Neon.CYAN);
        graph(c, hist, g, 0, Math.max(10, hist.max() * 1.1f), Neon.YEL);

        float y = g.bottom + 30 * dp;
        text(c, "ROUGH GUIDE (check AS/NZS 1680 for compliance)", 16 * dp, y, 11, Neon.GREY,
                Paint.Align.LEFT, false);
        String[] names = {"Corridor / storage", "General office", "Workshop bench", "Fine detail work"};
        int[] req = {100, 320, 500, 800};
        for (int i = 0; i < names.length; i++) {
            y += 20 * dp;
            boolean ok = lux >= req[i];
            text(c, f("%s %-20s ~%d lux", ok ? "✓" : "·", names[i], req[i]), 20 * dp, y, 13,
                    ok ? Neon.GREEN : Neon.GREY, Paint.Align.LEFT, false);
        }
        y += 28 * dp;
        text(c, "Sensor sits beside the front camera – aim it at the light.", 16 * dp, y, 11,
                Neon.GREY, Paint.Align.LEFT, false);
        y += 16 * dp;
        text(c, "Phone readings can be ±20% or more.", 16 * dp, y, 11, Neon.GREY, Paint.Align.LEFT, false);
    }
}
