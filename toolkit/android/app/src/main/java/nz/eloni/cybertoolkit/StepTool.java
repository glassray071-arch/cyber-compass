package nz.eloni.cybertoolkit;

import android.Manifest;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.os.Build;

/** Measuring wheel by pacing: steps x stride. Calibrate stride by walking a known 10 m. */
public class StepTool extends Tool {

    private long steps, calStart = -1;
    private float stride;
    private boolean useDetector, paused;
    private float lp, slow;
    private boolean above;
    private long lastStep;
    private Btn pauseBtn, calBtn;
    private String status = "";

    public StepTool(MainActivity a) {
        super(a);
        stride = prefs.getFloat("stride", 0.75f);
        pauseBtn = btn("PAUSE", () -> {
            paused = !paused;
            pauseBtn.on = paused;
        });
        btn("RESET", () -> steps = 0);
        btn("STRIDE −", () -> setStride(stride - 0.01f));
        btn("STRIDE +", () -> setStride(stride + 0.01f));
        calBtn = btn("CAL 10 m", this::calibrate);
        calBtn.color = Neon.MAG;
    }

    private void setStride(float v) {
        stride = Neon.clamp(v, 0.3f, 1.5f);
        prefs.edit().putFloat("stride", stride).apply();
    }

    private void calibrate() {
        if (calStart < 0) {
            calStart = steps;
            calBtn.label = "DONE 10 m";
            calBtn.on = true;
            paused = false;
            pauseBtn.on = false;
        } else {
            long n = steps - calStart;
            if (n >= 5) {
                setStride(10f / n);
                status = f("calibrated: %d steps = 10 m", n);
            } else {
                status = "too few steps – walk the full 10 m";
            }
            calStart = -1;
            calBtn.label = "CAL 10 m";
            calBtn.on = false;
        }
    }

    @Override
    String title() {
        return "MEASURE WALK";
    }

    @Override
    String aim() {
        return "Hold it in your hand or keep it in a pocket and walk normally. Calibrate once with CAL 10 m.";
    }

    @Override
    void onStart() {
        Runnable det = () -> {
            useDetector = listen(Sensor.TYPE_STEP_DETECTOR, SensorManager.SENSOR_DELAY_FASTEST);
            if (!useDetector) listen(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME);
        };
        Runnable fallback = () -> {
            useDetector = false;
            listen(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME);
        };
        if (!has(Sensor.TYPE_STEP_DETECTOR)) fallback.run();
        else if (Build.VERSION.SDK_INT >= 29) {
            act.needPerms(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, det, fallback);
        } else det.run();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (paused) return;
        if (e.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
            steps++;
            return;
        }
        // accelerometer fallback: peak detection on smoothed magnitude
        float m = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1]
                + e.values[2] * e.values[2]);
        lp += 0.3f * (m - lp);
        slow += 0.02f * (m - slow);
        float d = lp - slow;
        long now = System.currentTimeMillis();
        if (!above && d > 1.2f && now - lastStep > 300) {
            above = true;
            lastStep = now;
            steps++;
        } else if (above && d < 0.3f) {
            above = false;
        }
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        float w = a.width(), cx = w / 2f;
        float dist = steps * stride;
        text(c, f("%.1f m", dist), cx, a.top + 72 * dp, 48, Neon.GREEN, Paint.Align.CENTER, true);
        text(c, steps + " steps · stride " + f("%.2f m", stride), cx, a.top + 98 * dp, 13, Neon.CYAN,
                Paint.Align.CENTER, false);

        float R = Math.min(w * 0.26f, a.height() * 0.16f);
        float cy = a.top + 130 * dp + R;
        circle(c, cx, cy, R, Neon.MAG, 3);
        double rot = dist / (2 * Math.PI * 0.5) * 2 * Math.PI; // like a 1 m circumference wheel
        for (int k = 0; k < 6; k++) {
            double ang = rot + k * Math.PI / 3;
            line(c, cx, cy, cx + (float) (R * Math.cos(ang)), cy + (float) (R * Math.sin(ang)),
                    Neon.alpha(Neon.MAG, k == 0 ? 0xFF : 0x60), k == 0 ? 3 : 1.5f);
        }
        line(c, cx - R * 1.6f, cy + R, cx + R * 1.6f, cy + R, Neon.CYAN, 2);

        float y = cy + R + 40 * dp;
        if (paused) {
            text(c, "PAUSED", cx, y, 16, Neon.YEL, Paint.Align.CENTER, true);
            y += 24 * dp;
        }
        if (calStart >= 0) {
            text(c, f("CALIBRATING: walk exactly 10 m… %d steps", steps - calStart), cx, y, 13, Neon.MAG,
                    Paint.Align.CENTER, true);
            y += 24 * dp;
        }
        if (!status.isEmpty()) {
            text(c, status, cx, y, 12, Neon.CYAN, Paint.Align.CENTER, false);
            y += 24 * dp;
        }
        String[] tips = {
                "Pace out cable runs, trenches, fences or rooms.",
                "CAL 10 m: tap, walk a measured 10 m, tap DONE.",
                useDetector ? "Using the phone's step detector." : "Using accelerometer step detection.",
                "Hold the phone or keep it in a pocket while walking."};
        for (String t : tips) {
            text(c, t, 16 * dp, y, 11, Neon.GREY, Paint.Align.LEFT, false);
            y += 16 * dp;
        }
    }
}
