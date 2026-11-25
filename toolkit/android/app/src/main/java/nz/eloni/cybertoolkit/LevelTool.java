package nz.eloni.cybertoolkit;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;

/** Bubble level when flat, slope gauge when standing on an edge. ZERO measures relative angles. */
public class LevelTool extends Tool {

    private float[] g;
    private float refX, refY, refEdge;
    private boolean zeroed, wasLevel;
    private Btn hold;

    public LevelTool(MainActivity a) {
        super(a);
        btn("ZERO", this::zero);
        btn("CLEAR", () -> zeroed = false);
        hold = btn("HOLD", () -> hold.on = !hold.on);
    }

    @Override
    String title() {
        return "LEVEL / ANGLE";
    }

    @Override
    String aim() {
        return "Flat: the camera island makes it rock on its back, so lay it SCREEN-DOWN. Edge: use the LEFT side (no buttons) or the top edge.";
    }

    @Override
    void onStart() {
        listen(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (hold.on) return;
        if (g == null) g = new float[]{e.values[0], e.values[1], e.values[2]};
        for (int i = 0; i < 3; i++) g[i] += 0.12f * (e.values[i] - g[i]);
    }

    private float tiltX() {
        return (float) Math.toDegrees(Math.atan2(g[0], Math.hypot(g[1], g[2])));
    }

    private float tiltY() {
        return (float) Math.toDegrees(Math.atan2(g[1], Math.hypot(g[0], g[2])));
    }

    private boolean flat() {
        return Math.abs(g[2]) >= Math.max(Math.abs(g[0]), Math.abs(g[1]));
    }

    private boolean landscape() {
        return Math.abs(g[0]) > Math.abs(g[1]);
    }

    private float edgeAngle() {
        return landscape()
                ? (float) Math.toDegrees(Math.atan2(g[1], Math.abs(g[0])))
                : (float) Math.toDegrees(Math.atan2(g[0], Math.abs(g[1])));
    }

    private void zero() {
        if (g == null) return;
        refX = tiltX();
        refY = tiltY();
        refEdge = edgeAngle();
        zeroed = true;
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        if (g == null) {
            text(c, "Waiting for accelerometer…", a.centerX(), a.top + 60 * dp, 14, Neon.YEL,
                    Paint.Align.CENTER, false);
            return;
        }
        float w = a.width(), cx = w / 2f;
        boolean level;
        if (flat()) {
            float tx = tiltX() - (zeroed ? refX : 0), ty = tiltY() - (zeroed ? refY : 0);
            float tot = (float) Math.hypot(tx, ty);
            level = tot < 0.5f;
            int col = level ? Neon.GREEN : Neon.YEL;
            text(c, "FLAT" + (zeroed ? " · RELATIVE" : ""), 16 * dp, a.top + 26 * dp, 13, Neon.CYAN,
                    Paint.Align.LEFT, false);
            text(c, f("%.1f°", tot), cx, a.top + 72 * dp, 40, col, Paint.Align.CENTER, true);
            text(c, f("X %+.1f°    Y %+.1f°", tx, ty), cx, a.top + 100 * dp, 14, Neon.CYAN,
                    Paint.Align.CENTER, false);
            float R = Math.min(w * 0.4f, (a.height() - 140 * dp) * 0.45f);
            float cy = a.top + 130 * dp + R;
            circle(c, cx, cy, R, Neon.CYAN, 2);
            circle(c, cx, cy, R * 0.5f, Neon.alpha(Neon.CYAN, 0x50), 1);
            circle(c, cx, cy, 16 * dp, Neon.alpha(Neon.GREEN, 0xA0), 1.5f);
            line(c, cx - R, cy, cx + R, cy, Neon.alpha(Neon.CYAN, 0x40), 1);
            line(c, cx, cy - R, cx, cy + R, Neon.alpha(Neon.CYAN, 0x40), 1);
            // bubble rises to the high side; full scale = 10 degrees
            float bx = Neon.clamp(tx / 10f, -1, 1), by = Neon.clamp(ty / 10f, -1, 1);
            float mag = (float) Math.hypot(bx, by);
            if (mag > 1) {
                bx /= mag;
                by /= mag;
            }
            dot(c, cx + bx * (R - 14 * dp), cy - by * (R - 14 * dp), 13 * dp, col, true);
        } else {
            float ang = edgeAngle() - (zeroed ? refEdge : 0);
            level = Math.abs(ang) < 0.3f;
            int col = level ? Neon.GREEN : Neon.YEL;
            text(c, (landscape() ? "LANDSCAPE EDGE" : "PORTRAIT EDGE") + (zeroed ? " · RELATIVE" : ""),
                    16 * dp, a.top + 26 * dp, 13, Neon.CYAN, Paint.Align.LEFT, false);
            text(c, f("%+.1f°", ang), cx, a.top + 80 * dp, 44, col, Paint.Align.CENTER, true);
            double rad = Math.toRadians(ang);
            if (Math.abs(ang) > 0.05f) {
                float pct = (float) (Math.tan(rad) * 100);
                text(c, f("slope %+.1f%%   ·   1 in %.0f", pct, Math.abs(1 / Math.tan(rad))), cx,
                        a.top + 110 * dp, 14, Neon.CYAN, Paint.Align.CENTER, false);
            } else {
                text(c, "slope 0%", cx, a.top + 110 * dp, 14, Neon.CYAN, Paint.Align.CENTER, false);
            }
            // vial
            float vy = a.top + 170 * dp, vw = w * 0.8f;
            RectF vial = new RectF(cx - vw / 2, vy - 18 * dp, cx + vw / 2, vy + 18 * dp);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * dp);
            p.setColor(Neon.CYAN);
            c.drawRoundRect(vial, 18 * dp, 18 * dp, p);
            line(c, cx - 22 * dp, vy - 18 * dp, cx - 22 * dp, vy + 18 * dp, Neon.alpha(Neon.GREEN, 0xA0), 1.5f);
            line(c, cx + 22 * dp, vy - 18 * dp, cx + 22 * dp, vy + 18 * dp, Neon.alpha(Neon.GREEN, 0xA0), 1.5f);
            float bx = cx - Neon.clamp(ang / 10f, -1, 1) * (vw / 2 - 20 * dp);
            dot(c, bx, vy, 14 * dp, col, true);
            // tilted reference line
            float ly = vy + 90 * dp, len = w * 0.38f;
            float dx = (float) (len * Math.cos(rad)), dy = (float) (len * Math.sin(rad));
            line(c, cx - len, ly, cx + len, ly, Neon.alpha(Neon.CYAN, 0x40), 1);
            line(c, cx - dx, ly + dy, cx + dx, ly - dy, col, 4);
            text(c, "Stand the phone on its edge on a pipe, tray or bench.", cx, ly + 60 * dp, 11,
                    Neon.GREY, Paint.Align.CENTER, false);
            text(c, "ZERO on one surface, then measure the angle to another.", cx, ly + 78 * dp, 11,
                    Neon.GREY, Paint.Align.CENTER, false);
        }
        if (level && !wasLevel && !hold.on) act.vibrate(25);
        wasLevel = level;
        if (level) text(c, "◆ LEVEL ◆", cx, a.bottom - 12 * dp, 16, Neon.GREEN, Paint.Align.CENTER, true);
    }
}
