package nz.eloni.cybertoolkit;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 3-axis seismograph with STA/LTA event trigger and rough intensity (MMI) from peak acceleration. */
public class SeismoTool extends Tool {

    private float[] grav;
    private final Ring[] tr = {new Ring(500), new Ring(500), new Ring(500)};
    private final Ring mags = new Ring(500);
    private double sta, lta = 0.01;
    private float gain;
    private boolean eventOn;
    private long eventStart, lastEnd;
    private float eventPeak;
    private final List<String> events = new ArrayList<>();
    private int warm;

    public SeismoTool(MainActivity a) {
        super(a);
        gain = prefs.getFloat("seis_gain", 0.2f);
        btn("ZOOM −", () -> setGain(gain * 2));
        btn("ZOOM +", () -> setGain(gain / 2));
        btn("CLEAR", () -> events.clear());
    }

    private void setGain(float g) {
        gain = Neon.clamp(g, 0.0125f, 6.4f);
        prefs.edit().putFloat("seis_gain", gain).apply();
    }

    @Override
    String title() {
        return "SEISMOGRAPH";
    }

    @Override
    void onStart() {
        grav = null;
        warm = 0;
        listen(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (grav == null) grav = e.values.clone();
        float[] d = new float[3];
        for (int i = 0; i < 3; i++) {
            grav[i] += 0.02f * (e.values[i] - grav[i]);
            d[i] = e.values[i] - grav[i];
            tr[i].add(d[i]);
        }
        float m = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        mags.add(m);
        if (warm < 150) {
            warm++;
            lta += (m - lta) * 0.05;
            sta = lta;
            return;
        }
        sta += (m - sta) * (1.0 / 25);
        lta += (m - lta) * (1.0 / 1500);
        double ratio = sta / Math.max(lta, 1e-3);
        long now = System.currentTimeMillis();
        if (!eventOn && ratio > 4 && sta > 0.03 && now - lastEnd > 3000) {
            eventOn = true;
            eventStart = now;
            eventPeak = 0;
        }
        if (eventOn) {
            eventPeak = Math.max(eventPeak, m);
            if ((ratio < 1.5 && now - eventStart > 1000) || now - eventStart > 30000) {
                eventOn = false;
                lastEnd = now;
                String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(eventStart));
                events.add(0, f("%s  %.1fs  peak %.3f g  MMI %s", t, (now - eventStart) / 1000f,
                        eventPeak / Neon.G, mmi(eventPeak)));
                if (events.size() > 6) events.remove(events.size() - 1);
                act.vibrate(80);
            }
        }
    }

    /** Wald et al. (1999) PGA → Modified Mercalli intensity. Indicative only. */
    static String mmi(float pgaMs2) {
        double cm = pgaMs2 * 100;
        if (cm <= 0) return "I";
        double v = cm < 9.0 ? 2.20 * Math.log10(cm) + 1.00 : 3.66 * Math.log10(cm) - 1.66;
        int i = (int) Math.round(Neon.clamp((float) v, 1, 10));
        String[] r = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return r[i - 1];
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        float w = a.width();
        float live = mags.last(), pk = mags.max();
        float y = a.top + 30 * dp;
        text(c, f("%.4f g", live / Neon.G), 16 * dp, y, 24, eventOn ? Neon.RED : Neon.GREEN,
                Paint.Align.LEFT, true);
        text(c, eventOn ? "● EVENT" : "listening", w - 16 * dp, y, 14, eventOn ? Neon.RED : Neon.CYAN,
                Paint.Align.RIGHT, eventOn);
        y += 20 * dp;
        text(c, f("peak(10s) %.4f g · MMI %s · STA/LTA %.1f · ±%.2f m/s²", pk / Neon.G, mmi(pk),
                sta / Math.max(lta, 1e-3), gain), 16 * dp, y, 10, Neon.GREY, Paint.Align.LEFT, false);

        float th = Math.max(60 * dp, (a.height() - 240 * dp) / 3f);
        String[] lab = {"X", "Y", "Z"};
        int[] col = {Neon.RED, Neon.GREEN, Neon.CYAN};
        float top = y + 14 * dp;
        for (int i = 0; i < 3; i++) {
            RectF g = new RectF(28 * dp, top + i * (th + 6 * dp), w - 12 * dp, top + i * (th + 6 * dp) + th);
            frame(c, g, Neon.alpha(col[i], 0x90));
            line(c, g.left, g.centerY(), g.right, g.centerY(), Neon.alpha(col[i], 0x30), 1);
            text(c, lab[i], 12 * dp, g.centerY() + 5 * dp, 13, col[i], Paint.Align.LEFT, true);
            graph(c, tr[i], g, -gain, gain, col[i]);
        }
        y = top + 3 * (th + 6 * dp) + 18 * dp;
        text(c, "EVENTS (footsteps & doors count too)", 16 * dp, y, 12, Neon.MAG, Paint.Align.LEFT, true);
        if (events.isEmpty()) text(c, "none yet", 24 * dp, y + 18 * dp, 11, Neon.GREY, Paint.Align.LEFT, false);
        for (String ev : events) {
            y += 17 * dp;
            if (y > a.bottom - 20 * dp) break;
            text(c, ev, 20 * dp, y, 11, Neon.YEL, Paint.Align.LEFT, false);
        }
        text(c, "Lay the phone flat on a solid floor, screen up. Fun/indicative only.", w / 2f,
                a.bottom - 6 * dp, 9, Neon.GREY, Paint.Align.CENTER, false);
    }
}
