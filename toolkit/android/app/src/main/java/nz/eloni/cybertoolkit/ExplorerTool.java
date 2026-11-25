package nz.eloni.cybertoolkit;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;

import java.util.List;

/** Lists every sensor in the phone; tap one for live values, graphs and specs. */
public class ExplorerTool extends Tool {

    private final List<Sensor> all;
    private Sensor sel;
    private float[] vals;
    private final Ring[] rings = {new Ring(240), new Ring(240), new Ring(240)};
    private float scroll, listTop;
    private long events, since;

    public ExplorerTool(MainActivity a) {
        super(a);
        all = sm.getSensorList(Sensor.TYPE_ALL);
    }

    @Override
    String title() {
        return sel == null ? "SENSORS (" + all.size() + ")" : "LIVE SENSOR";
    }

    @Override
    void onStart() {
        if (sel != null) sm.registerListener(this, sel, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    boolean onBack() {
        if (sel == null) return false;
        sm.unregisterListener(this);
        sel = null;
        return true;
    }

    private void open(Sensor s) {
        sel = s;
        vals = null;
        for (Ring r : rings) r.clear();
        events = 0;
        since = System.currentTimeMillis();
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        vals = e.values.clone();
        events++;
        for (int i = 0; i < 3 && i < vals.length; i++) rings[i].add(vals[i]);
    }

    @Override
    void onScroll(float dy) {
        if (sel == null) scroll = Math.max(0, scroll - dy);
    }

    @Override
    void onTap(float x, float y) {
        if (sel != null) return;
        int i = (int) ((y - listTop + scroll) / (60 * dp));
        if (y >= listTop && i >= 0 && i < all.size()) open(all.get(i));
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        if (sel == null) drawList(c, a);
        else drawLive(c, a);
    }

    private void drawList(Canvas c, RectF a) {
        float w = a.width(), rh = 60 * dp;
        listTop = a.top + 8 * dp;
        scroll = Math.min(scroll, Math.max(0, all.size() * rh - a.height() + 16 * dp));
        for (int i = 0; i < all.size(); i++) {
            float top = listTop + i * rh - scroll;
            if (top + rh < a.top || top > a.bottom) continue;
            Sensor s = all.get(i);
            int col = i % 2 == 0 ? Neon.CYAN : Neon.MAG;
            RectF r = new RectF(12 * dp, top + 4 * dp, w - 12 * dp, top + rh - 4 * dp);
            frame(c, r, Neon.alpha(col, 0x90));
            String name = s.getName();
            if (name.length() > 34) name = name.substring(0, 33) + "…";
            text(c, name, r.left + 10 * dp, r.top + 21 * dp, 13, Neon.WHITE, Paint.Align.LEFT, false);
            String type = s.getStringType().replace("android.sensor.", "");
            text(c, type + " · " + s.getVendor(), r.left + 10 * dp, r.top + 40 * dp, 10, col,
                    Paint.Align.LEFT, false);
        }
    }

    private void drawLive(Canvas c, RectF a) {
        float w = a.width();
        float y = a.top + 26 * dp;
        String name = sel.getName();
        if (name.length() > 30) name = name.substring(0, 29) + "…";
        text(c, name, 16 * dp, y, 15, Neon.MAG, Paint.Align.LEFT, true);
        y += 18 * dp;
        text(c, sel.getStringType().replace("android.sensor.", "") + " · " + sel.getVendor(), 16 * dp, y,
                11, Neon.CYAN, Paint.Align.LEFT, false);
        y += 26 * dp;
        if (sel.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT) {
            text(c, "One-shot trigger sensor – no live stream.", 16 * dp, y, 12, Neon.YEL, Paint.Align.LEFT, false);
        } else if (vals == null) {
            text(c, "Waiting… (some sensors only report on change)", 16 * dp, y, 12, Neon.YEL,
                    Paint.Align.LEFT, false);
        } else {
            String[] lab = {"x", "y", "z", "3", "4", "5"};
            int[] cols = {Neon.RED, Neon.GREEN, Neon.CYAN, Neon.YEL, Neon.YEL, Neon.YEL};
            for (int i = 0; i < Math.min(6, vals.length); i++) {
                text(c, f("%s  %14.4f", lab[i], vals[i]), 16 * dp, y, 16, cols[i], Paint.Align.LEFT, i < 3);
                y += 22 * dp;
            }
            RectF g = new RectF(16 * dp, y, w - 16 * dp, y + 150 * dp);
            frame(c, g, Neon.CYAN);
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                if (rings[i].n == 0) continue;
                lo = Math.min(lo, rings[i].min());
                hi = Math.max(hi, rings[i].max());
            }
            if (hi - lo < 1e-3f) {
                hi += 0.5f;
                lo -= 0.5f;
            }
            for (int i = 0; i < 3; i++) graph(c, rings[i], g, lo, hi, cols[i]);
            y = g.bottom + 26 * dp;
        }
        float secs = (System.currentTimeMillis() - since) / 1000f;
        String[] info = {
                f("range %.1f   resolution %.5f", sel.getMaximumRange(), sel.getResolution()),
                f("power %.2f mA   version %d", sel.getPower(), sel.getVersion()),
                sel.getMinDelay() > 0 ? f("max rate %.0f Hz   now %.0f Hz", 1e6 / sel.getMinDelay(),
                        secs > 0 ? events / secs : 0) : "reports on change",
                sel.isWakeUpSensor() ? "wake-up sensor" : "non-wake-up sensor",
        };
        for (String s : info) {
            text(c, s, 16 * dp, y, 11, Neon.GREY, Paint.Align.LEFT, false);
            y += 17 * dp;
        }
    }
}
