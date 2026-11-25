package nz.eloni.cybertoolkit;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;

/**
 * Galaxy S26 sensor map: where each sensor / camera / mic sits, with live tests so you can
 * confirm them on your own phone. Find the magnetometer by sliding a screwdriver tip over the
 * back - tap the diagram where it reads strongest and the app remembers it.
 */
public class SensorMapTool extends Tool {

    private float lux = -1, prox = -1, proxMax = 5, mag, magBase = Float.NaN, magPeak;
    private float markX, markY;
    private final RectF backR = new RectF(), frontR = new RectF();

    public SensorMapTool(MainActivity a) {
        super(a);
        markX = prefs.getFloat("mag_x", -1);
        markY = prefs.getFloat("mag_y", -1);
        btn("RESET MAG", () -> {
            magBase = Float.NaN;
            magPeak = 0;
            markX = markY = -1;
            prefs.edit().remove("mag_x").remove("mag_y").apply();
        });
    }

    @Override
    String title() {
        return "S26 SENSOR MAP";
    }

    @Override
    String aim() {
        return "Slide a steel screwdriver tip slowly over the BACK; where MAG peaks is the compass chip - tap that spot on the BACK drawing.";
    }

    @Override
    void onStart() {
        listen(Sensor.TYPE_LIGHT, SensorManager.SENSOR_DELAY_UI);
        listen(Sensor.TYPE_PROXIMITY, SensorManager.SENSOR_DELAY_UI);
        listen(Sensor.TYPE_MAGNETIC_FIELD, SensorManager.SENSOR_DELAY_GAME);
        Sensor p = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (p != null) proxMax = p.getMaximumRange();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        switch (e.sensor.getType()) {
            case Sensor.TYPE_LIGHT:
                lux = e.values[0];
                break;
            case Sensor.TYPE_PROXIMITY:
                prox = e.values[0];
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                float x = e.values[0], y = e.values[1], z = e.values[2];
                mag = (float) Math.sqrt(x * x + y * y + z * z);
                if (Float.isNaN(magBase)) magBase = mag;
                magPeak = Math.max(magPeak * 0.995f, Math.abs(mag - magBase));
                break;
            default:
                break;
        }
    }

    @Override
    void onTap(float x, float y) {
        if (backR.contains(x, y)) {
            markX = (x - backR.left) / backR.width();
            markY = (y - backR.top) / backR.height();
            prefs.edit().putFloat("mag_x", markX).putFloat("mag_y", markY).apply();
        }
    }

    private void label(Canvas c, String s, float x, float y, float tx, float ty, int col, Paint.Align al) {
        line(c, x, y, tx, ty, Neon.alpha(col, 0x90), 1);
        dot(c, x, y, 3 * dp, col, false);
        text(c, s, tx, ty + 4 * dp, 9.5f, col, al, false);
    }

    private void phone(Canvas c, RectF r) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * dp);
        p.setColor(Neon.CYAN);
        c.drawRoundRect(r, 14 * dp, 14 * dp, p);
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        float w = a.width();
        float ph = Math.min(a.height() - 120 * dp, (w * 0.3f) * 149.6f / 71.7f);
        float pw = ph * 71.7f / 149.6f;
        float top = a.top + 22 * dp;
        frontR.set(w * 0.27f - pw / 2, top, w * 0.27f + pw / 2, top + ph);
        backR.set(w * 0.73f - pw / 2, top, w * 0.73f + pw / 2, top + ph);
        text(c, "FRONT", frontR.centerX(), top - 6 * dp, 11, Neon.WHITE, Paint.Align.CENTER, true);
        text(c, "BACK", backR.centerX(), top - 6 * dp, 11, Neon.WHITE, Paint.Align.CENTER, true);
        phone(c, frontR);
        phone(c, backR);

        // ---- front
        float fx = frontR.centerX(), f0 = frontR.top;
        boolean near = prox >= 0 && prox < proxMax;
        dot(c, fx, f0 + ph * 0.035f, 4 * dp, Neon.WHITE, false);                       // selfie cam
        dot(c, fx + pw * 0.14f, f0 + ph * 0.035f, 3.5f * dp, near ? Neon.RED : Neon.YEL, near); // light/prox
        line(c, fx - pw * 0.12f, f0 + 2 * dp, fx + pw * 0.12f, f0 + 2 * dp, Neon.GREEN, 3);   // earpiece / top mic
        circle(c, fx, f0 + ph * 0.8f, pw * 0.09f, Neon.MAG, 1.5f);                          // fingerprint
        line(c, frontR.right + 1 * dp, f0 + ph * 0.22f, frontR.right + 1 * dp, f0 + ph * 0.36f, Neon.GREY, 3); // volume
        line(c, frontR.right + 1 * dp, f0 + ph * 0.40f, frontR.right + 1 * dp, f0 + ph * 0.46f, Neon.GREY, 3); // power
        float by = frontR.bottom - 1 * dp;
        dot(c, fx - pw * 0.22f, by, 2.5f * dp, Neon.GREEN, true);                            // bottom mic
        line(c, fx - pw * 0.09f, by, fx + pw * 0.09f, by, Neon.WHITE, 3);                    // USB-C
        line(c, fx + pw * 0.16f, by, fx + pw * 0.34f, by, Neon.ORANGE, 3);                   // speaker
        float lx = frontR.left - 4 * dp;
        label(c, "light/prox", fx + pw * 0.14f, f0 + ph * 0.035f, lx - 2 * dp, f0 + ph * 0.10f, Neon.YEL, Paint.Align.RIGHT);
        label(c, "top mic+ear", fx - pw * 0.12f, f0 + 2 * dp, lx - 2 * dp, f0 + ph * 0.02f, Neon.GREEN, Paint.Align.RIGHT);
        label(c, "fingerprint", fx - pw * 0.09f, f0 + ph * 0.8f, lx - 2 * dp, f0 + ph * 0.8f, Neon.MAG, Paint.Align.RIGHT);
        label(c, "MAIN MIC", fx - pw * 0.22f, by, lx - 2 * dp, by - 8 * dp, Neon.GREEN, Paint.Align.RIGHT);
        label(c, "speaker", fx + pw * 0.25f, by, fx + pw * 0.25f, by + 16 * dp, Neon.ORANGE, Paint.Align.CENTER);
        label(c, "keys", frontR.right + 1 * dp, f0 + ph * 0.3f, frontR.right + 14 * dp, f0 + ph * 0.3f, Neon.GREY, Paint.Align.LEFT);

        // ---- back (mirror image: camera island top-left when viewed from behind)
        float bx0 = backR.left, b0 = backR.top;
        RectF island = new RectF(bx0 + pw * 0.08f, b0 + ph * 0.035f, bx0 + pw * 0.30f, b0 + ph * 0.30f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * dp);
        p.setColor(Neon.GREEN);
        c.drawRoundRect(island, pw * 0.11f, pw * 0.11f, p);
        for (int i = 0; i < 3; i++) {
            circle(c, island.centerX(), island.top + island.height() * (0.18f + i * 0.32f), pw * 0.07f, Neon.GREEN, 1.5f);
        }
        dot(c, island.right + pw * 0.09f, island.top + island.height() * 0.2f, 4 * dp, Neon.YEL, true); // flash
        circle(c, backR.centerX(), backR.top + ph * 0.55f, pw * 0.22f, Neon.alpha(Neon.CYAN, 0x50), 1);  // charging coil
        float rx = backR.right + 4 * dp;
        label(c, "cameras", island.right, island.centerY(), rx, island.centerY() + 10 * dp, Neon.GREEN, Paint.Align.LEFT);
        label(c, "flash", island.right + pw * 0.09f, island.top + island.height() * 0.2f, rx, island.top, Neon.YEL, Paint.Align.LEFT);
        label(c, "wireless coil", backR.centerX() + pw * 0.22f, backR.top + ph * 0.55f, rx, backR.top + ph * 0.55f,
                Neon.CYAN, Paint.Align.LEFT);
        if (markX >= 0) {
            float mx = backR.left + markX * backR.width(), my = backR.top + markY * backR.height();
            circle(c, mx, my, 9 * dp, Neon.MAG, 2.5f);
            label(c, "COMPASS (yours)", mx, my, rx, my + 14 * dp, Neon.MAG, Paint.Align.LEFT);
        } else {
            text(c, "compass: tap to mark", backR.centerX(), backR.top + ph * 0.18f, 9, Neon.MAG, Paint.Align.CENTER, false);
        }

        // ---- live tests
        float y = backR.bottom + 34 * dp;
        text(c, lux < 0 ? "LIGHT  —" : f("LIGHT %,.0f lux  (cover the top of the screen)", lux), 14 * dp, y, 12,
                Neon.YEL, Paint.Align.LEFT, false);
        y += 18 * dp;
        text(c, prox < 0 ? "PROXIMITY  —" : "PROXIMITY " + (near ? "NEAR ●" : "far") + "  (wave over the top)", 14 * dp, y, 12,
                near ? Neon.RED : Neon.YEL, Paint.Align.LEFT, near);
        y += 18 * dp;
        text(c, f("MAG %.1f µT  change %.1f", mag, Float.isNaN(magBase) ? 0 : Math.abs(mag - magBase)), 14 * dp, y, 12,
                Neon.MAG, Paint.Align.LEFT, false);
        y += 6 * dp;
        bar(c, 14 * dp, y, w - 14 * dp, y + 12 * dp, magPeak / 60f, Neon.MAG);
        y += 26 * dp;
        String[] notes = {
                "Remove magnetic Qi2 cases / rings before compass, metal or AC field.",
                "Camera island stops the phone lying flat on its back - lay it",
                "SCREEN-DOWN (on a cloth) for level, seismograph and vibration."};
        for (String s : notes) {
            if (y > a.bottom - 4 * dp) break;
            text(c, s, 14 * dp, y, 10, Neon.GREY, Paint.Align.LEFT, false);
            y += 14 * dp;
        }
    }
}
