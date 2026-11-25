package nz.eloni.cybertoolkit;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;

/** Gyroscope tachometer and turn counter (put the phone on a turntable, reel, wheel or door). */
public class TachoTool extends Tool {

    private static final String[] AXES = {"Z (flat spin)", "X (roll)", "Y (pitch)"};
    private int axis;
    private float rpm, maxRpm, w;
    private double turns, angle;
    private long lastTs;
    private final Ring hist = new Ring(300);
    private Btn axisBtn;

    public TachoTool(MainActivity a) {
        super(a);
        axisBtn = btn("AXIS Z", () -> {
            axis = (axis + 1) % 3;
            axisBtn.label = "AXIS " + "ZXY".charAt(axis);
            reset();
        });
        btn("RESET", this::reset);
    }

    private void reset() {
        turns = 0;
        maxRpm = 0;
        hist.clear();
    }

    @Override
    String title() {
        return "GYRO TACHO";
    }

    @Override
    String aim() {
        return "Tape it SCREEN-UP with the CENTRE of the phone over the rotation axis. Use AXIS for other mounting positions.";
    }

    @Override
    void onStart() {
        lastTs = 0;
        listen(Sensor.TYPE_GYROSCOPE, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        int i = axis == 0 ? 2 : axis == 1 ? 0 : 1;
        float v = e.values[i];
        w += 0.2f * (v - w);
        if (lastTs != 0) {
            double dt = (e.timestamp - lastTs) / 1e9;
            if (dt > 0 && dt < 0.5) {
                turns += v * dt / (2 * Math.PI);
                angle += v * dt;
            }
        }
        lastTs = e.timestamp;
        rpm = (float) (Math.abs(w) * 60 / (2 * Math.PI));
        maxRpm = Math.max(maxRpm, rpm);
        hist.add(rpm);
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        float W = a.width(), cx = W / 2f;
        Sensor g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (g == null) {
            text(c, "NO GYROSCOPE IN THIS PHONE", cx, a.top + 70 * dp, 16, Neon.RED, Paint.Align.CENTER, true);
            return;
        }
        float limit = (float) (g.getMaximumRange() * 60 / (2 * Math.PI));
        text(c, f("%.1f", rpm), cx, a.top + 66 * dp, 46, rpm > limit * 0.95f ? Neon.RED : Neon.GREEN,
                Paint.Align.CENTER, true);
        text(c, "RPM  ·  " + (Math.abs(w) < 0.05f ? "still" : (w > 0 ? "anticlockwise ↺" : "clockwise ↻")),
                cx, a.top + 90 * dp, 13, Neon.CYAN, Paint.Align.CENTER, false);

        float R = Math.min(W * 0.3f, a.height() * 0.17f);
        float cy = a.top + 120 * dp + R;
        circle(c, cx, cy, R, Neon.CYAN, 2);
        for (int k = 0; k < 4; k++) {
            double ang = -angle + k * Math.PI / 2;
            line(c, cx, cy, cx + (float) (R * Math.cos(ang)), cy - (float) (R * Math.sin(ang)),
                    k == 0 ? Neon.MAG : Neon.alpha(Neon.CYAN, 0x70), k == 0 ? 4 : 2);
        }
        dot(c, cx, cy, 6 * dp, Neon.WHITE, true);

        float y = cy + R + 36 * dp;
        text(c, f("TURNS %.2f", Math.abs(turns)), 16 * dp, y, 20, Neon.YEL, Paint.Align.LEFT, true);
        text(c, f("max %.0f RPM", maxRpm), W - 16 * dp, y, 13, Neon.CYAN, Paint.Align.RIGHT, false);
        RectF gr = new RectF(16 * dp, y + 14 * dp, W - 16 * dp, y + 104 * dp);
        frame(c, gr, Neon.CYAN);
        graph(c, hist, gr, 0, Math.max(10, hist.max() * 1.1f), Neon.GREEN);
        y = gr.bottom + 24 * dp;
        text(c, "axis " + AXES[axis] + f(" · sensor limit ≈ %.0f RPM", limit), 16 * dp, y, 11, Neon.GREY,
                Paint.Align.LEFT, false);
        y += 16 * dp;
        text(c, "Tape the phone to the spinning part, centred.", 16 * dp, y, 11, Neon.GREY, Paint.Align.LEFT, false);
        y += 16 * dp;
        text(c, "Count reel / winch / wheel turns with TURNS.", 16 * dp, y, 11, Neon.GREY, Paint.Align.LEFT, false);
        y += 16 * dp;
        text(c, "Faster machines: use VIBRATION or the STROBE.", 16 * dp, y, 11, Neon.GREY, Paint.Align.LEFT, false);
    }
}
