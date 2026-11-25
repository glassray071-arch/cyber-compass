package nz.eloni.cybertoolkit;

import android.Manifest;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;

/** Tilt-compensated compass with optional true north from the phone's location. */
public class CompassTool extends Tool {

    private final float[] rotM = new float[9], orient = new float[3];
    private float[] grav, geo;
    private boolean useRot;
    private float sx = 1, sy = 0, field;
    private int accuracy = -1;
    private float decl = Float.NaN;
    private Btn trueBtn;

    public CompassTool(MainActivity a) {
        super(a);
        trueBtn = btn("TRUE N", () -> {
            if (Float.isNaN(decl)) {
                findDeclination(true);
            } else {
                trueBtn.on = !trueBtn.on;
            }
        });
    }

    @Override
    String title() {
        return "COMPASS";
    }

    @Override
    String aim() {
        return "Hold it flat and level with the top edge pointing forward. Take off any magnetic case or ring first.";
    }

    @Override
    void onStart() {
        useRot = listen(Sensor.TYPE_ROTATION_VECTOR, SensorManager.SENSOR_DELAY_GAME);
        if (!useRot) listen(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME);
        listen(Sensor.TYPE_MAGNETIC_FIELD, SensorManager.SENSOR_DELAY_GAME);
        if (act.granted(Manifest.permission.ACCESS_FINE_LOCATION)) findDeclination(false);
    }

    private void findDeclination(boolean ask) {
        Runnable ok = () -> {
            LocationManager lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            try {
                for (String prov : lm.getProviders(true)) {
                    Location l = lm.getLastKnownLocation(prov);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                }
            } catch (SecurityException ignored) {
            }
            if (best != null) {
                decl = new GeomagneticField((float) best.getLatitude(), (float) best.getLongitude(),
                        (float) best.getAltitude(), System.currentTimeMillis()).getDeclination();
                if (ask) trueBtn.on = true;
            } else if (ask) {
                act.toast("No location yet – open Maps once, then try again");
            }
        };
        if (ask) act.needPerms(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, ok,
                () -> act.toast("Location needed for true north"));
        else ok.run();
    }

    private static float[] lp(float[] old, float[] in) {
        if (old == null) return new float[]{in[0], in[1], in[2]};
        for (int i = 0; i < 3; i++) old[i] += 0.2f * (in[i] - old[i]);
        return old;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        int t = e.sensor.getType();
        if (t == Sensor.TYPE_MAGNETIC_FIELD) {
            geo = lp(geo, e.values);
            field = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1]
                    + e.values[2] * e.values[2]);
            if (!useRot && grav != null && SensorManager.getRotationMatrix(rotM, null, grav, geo)) publish();
        } else if (t == Sensor.TYPE_ACCELEROMETER) {
            grav = lp(grav, e.values);
        } else if (t == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotM, e.values);
            publish();
        }
    }

    private void publish() {
        SensorManager.getOrientation(rotM, orient);
        double az = orient[0];
        sx += 0.25f * ((float) Math.cos(az) - sx);
        sy += 0.25f * ((float) Math.sin(az) - sy);
    }

    @Override
    public void onAccuracyChanged(Sensor s, int acc) {
        if (s.getType() == Sensor.TYPE_MAGNETIC_FIELD) accuracy = acc;
    }

    private float heading() {
        float hd = (float) Math.toDegrees(Math.atan2(sy, sx));
        if (trueBtn.on && !Float.isNaN(decl)) hd += decl;
        return (hd % 360 + 360) % 360;
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        float hd = heading();
        float w = a.width();
        float R = Math.min(w * 0.42f, a.height() * 0.34f);
        float cx = w / 2f, cy = a.top + R + 40 * dp;
        String[] card = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

        text(c, f("%03.0f°", hd), cx, a.top + 30 * dp, 30, Neon.GREEN, Paint.Align.CENTER, true);
        text(c, card[(int) ((hd + 22.5f) / 45f) % 8] + (trueBtn.on ? "  TRUE" : "  MAG"),
                cx + 80 * dp, a.top + 30 * dp, 13, Neon.CYAN, Paint.Align.LEFT, false);

        circle(c, cx, cy, R, Neon.CYAN, 2);
        circle(c, cx, cy, R * 0.62f, Neon.alpha(Neon.CYAN, 0x40), 1);
        for (int d = 0; d < 360; d += 5) {
            double ang = Math.toRadians(d - hd - 90);
            float len = d % 30 == 0 ? 16 * dp : (d % 10 == 0 ? 10 * dp : 5 * dp);
            float x1 = cx + (float) (R * Math.cos(ang)), y1 = cy + (float) (R * Math.sin(ang));
            float x2 = cx + (float) ((R - len) * Math.cos(ang)), y2 = cy + (float) ((R - len) * Math.sin(ang));
            line(c, x1, y1, x2, y2, d % 30 == 0 ? Neon.CYAN : Neon.alpha(Neon.CYAN, 0x70), d % 30 == 0 ? 2 : 1);
            if (d % 30 == 0) {
                String lab = d % 90 == 0 ? card[d / 45] : String.valueOf(d);
                float lr = R - 32 * dp;
                text(c, lab, cx + (float) (lr * Math.cos(ang)), cy + (float) (lr * Math.sin(ang)) + 5 * dp,
                        d % 90 == 0 ? 16 : 11, d == 0 ? Neon.MAG : Neon.WHITE, Paint.Align.CENTER, d == 0);
            }
        }
        // north needle
        double nAng = Math.toRadians(-hd - 90);
        line(c, cx, cy, cx + (float) (R * 0.55f * Math.cos(nAng)), cy + (float) (R * 0.55f * Math.sin(nAng)),
                Neon.MAG, 4);
        line(c, cx, cy, cx - (float) (R * 0.35f * Math.cos(nAng)), cy - (float) (R * 0.35f * Math.sin(nAng)),
                Neon.GREY, 4);
        dot(c, cx, cy, 6 * dp, Neon.WHITE, true);
        // lubber line
        line(c, cx, cy - R - 14 * dp, cx, cy - R + 18 * dp, Neon.YEL, 3);

        float y = cy + R + 34 * dp;
        boolean clean = field > 20 && field < 70;
        text(c, f("FIELD %.1f µT  %s", field, clean ? "CLEAN" : "INTERFERENCE"), 16 * dp, y, 13,
                clean ? Neon.GREEN : Neon.RED, Paint.Align.LEFT, false);
        String[] accN = {"UNRELIABLE", "LOW", "MEDIUM", "HIGH"};
        y += 20 * dp;
        text(c, "CALIBRATION " + (accuracy >= 0 && accuracy <= 3 ? accN[accuracy] : "?")
                        + (accuracy >= 0 && accuracy < 2 ? " – wave in a figure-8" : ""),
                16 * dp, y, 12, accuracy >= 2 ? Neon.CYAN : Neon.YEL, Paint.Align.LEFT, false);
        y += 20 * dp;
        text(c, Float.isNaN(decl) ? "Tap TRUE N for true north (uses location)"
                : f("Declination %+.1f°", decl), 16 * dp, y, 12, Neon.GREY, Paint.Align.LEFT, false);
    }
}
