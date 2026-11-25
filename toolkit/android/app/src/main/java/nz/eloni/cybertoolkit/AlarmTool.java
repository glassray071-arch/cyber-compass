package nz.eloni.cybertoolkit;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.speech.tts.TextToSpeech;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Tamper alarm: leave the phone on your bag, toolbox or door; any movement triggers it. */
public class AlarmTool extends Tool {

    private enum State { IDLE, COUNTDOWN, SETTLING, ARMED }

    private State state = State.IDLE;
    private long tState, lastTrig;
    private final float[] base = new float[3];
    private int settleN;
    private float thr, live, peak;
    private final List<String> events = new ArrayList<>();
    private TextToSpeech tts;
    private ToneGenerator tg;
    private Btn arm, siren, voice;

    public AlarmTool(MainActivity a) {
        super(a);
        thr = prefs.getFloat("alarm_thr", 0.6f);
        arm = btn("ARM", this::toggleArm);
        arm.color = Neon.RED;
        btn("SENS −", () -> setThr(thr + 0.2f));
        btn("SENS +", () -> setThr(thr - 0.2f));
        siren = btn("SIREN", () -> siren.on = !siren.on);
        voice = btn("VOICE", () -> voice.on = !voice.on);
        siren.on = true;
        voice.on = true;
    }

    private void setThr(float t) {
        thr = Neon.clamp(t, 0.1f, 5f);
        prefs.edit().putFloat("alarm_thr", thr).apply();
    }

    private void toggleArm() {
        if (state == State.IDLE) {
            state = State.COUNTDOWN;
            tState = System.currentTimeMillis();
            arm.label = "DISARM";
            arm.on = true;
        } else {
            disarm();
        }
    }

    private void disarm() {
        state = State.IDLE;
        arm.label = "ARM";
        arm.on = false;
        if (tg != null) tg.stopTone();
    }

    @Override
    String title() {
        return "MOTION ALARM";
    }

    @Override
    String aim() {
        return "Lay it on the item you want to guard (toolbox, laptop, bag). Any way up works.";
    }

    @Override
    void onStart() {
        listen(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME);
        tts = new TextToSpeech(act, s -> {
            if (s == TextToSpeech.SUCCESS && tts != null) tts.setLanguage(Locale.UK);
        });
        try {
            tg = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        } catch (RuntimeException e) {
            tg = null;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            act.needPerms(new String[]{"android.permission.POST_NOTIFICATIONS"}, null, null);
        }
    }

    @Override
    void onStop() {
        disarm();
        if (tts != null) tts.shutdown();
        tts = null;
        if (tg != null) tg.release();
        tg = null;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        long now = System.currentTimeMillis();
        if (state == State.SETTLING) {
            for (int i = 0; i < 3; i++) base[i] += e.values[i];
            settleN++;
            if (now - tState > 1200 && settleN > 0) {
                for (int i = 0; i < 3; i++) base[i] /= settleN;
                state = State.ARMED;
                peak = 0;
            }
        } else if (state == State.ARMED) {
            float dx = e.values[0] - base[0], dy = e.values[1] - base[1], dz = e.values[2] - base[2];
            live = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            peak = Math.max(peak * 0.97f, live);
            if (live > thr && now - lastTrig > 5000) trigger(now);
        }
    }

    private void trigger(long now) {
        lastTrig = now;
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(now));
        events.add(0, f("%s  movement %.2f m/s²", stamp, live));
        if (events.size() > 8) events.remove(events.size() - 1);
        act.vibratePattern(new long[]{0, 400, 150, 400, 150, 400});
        act.notifyAlarm("⚠ CYBER//TOOLKIT", "Motion detected at " + stamp);
        if (siren.on && tg != null) tg.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 3000);
        if (voice.on && tts != null) tts.speak("Motion detected", TextToSpeech.QUEUE_FLUSH, null, "alarm");
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        float w = a.width(), cx = w / 2f;
        long now = System.currentTimeMillis();
        if (state == State.COUNTDOWN) {
            long left = 5 - (now - tState) / 1000;
            if (left <= 0) {
                state = State.SETTLING;
                tState = now;
                settleN = 0;
                base[0] = base[1] = base[2] = 0;
            }
            text(c, String.valueOf(Math.max(0, left)), cx, a.top + 90 * dp, 60, Neon.YEL, Paint.Align.CENTER, true);
            text(c, "put the phone down…", cx, a.top + 120 * dp, 14, Neon.GREY, Paint.Align.CENTER, false);
        } else if (state == State.SETTLING) {
            text(c, "LEARNING POSITION", cx, a.top + 90 * dp, 20, Neon.YEL, Paint.Align.CENTER, true);
        } else if (state == State.ARMED) {
            boolean flash = now - lastTrig < 3000 && (now / 250) % 2 == 0;
            text(c, flash ? "!! TRIGGERED !!" : "● ARMED ●", cx, a.top + 90 * dp, 30, Neon.RED,
                    Paint.Align.CENTER, true);
            bar(c, 16 * dp, a.top + 116 * dp, w - 16 * dp, a.top + 140 * dp, peak / (thr * 2),
                    peak > thr ? Neon.RED : Neon.GREEN);
            float tx = 16 * dp + (w - 32 * dp) * 0.5f;
            line(c, tx, a.top + 110 * dp, tx, a.top + 146 * dp, Neon.MAG, 2);
        } else {
            text(c, "DISARMED", cx, a.top + 90 * dp, 30, Neon.CYAN, Paint.Align.CENTER, true);
            text(c, "Press ARM, then leave the phone on your", cx, a.top + 120 * dp, 12, Neon.GREY,
                    Paint.Align.CENTER, false);
            text(c, "toolbox, bag, laptop or a door handle.", cx, a.top + 138 * dp, 12, Neon.GREY,
                    Paint.Align.CENTER, false);
        }
        float y = a.top + 180 * dp;
        text(c, f("trigger above %.1f m/s²", thr), 16 * dp, y, 13, Neon.CYAN, Paint.Align.LEFT, false);
        y += 20 * dp;
        text(c, "siren " + (siren.on ? "ON" : "OFF") + "  ·  voice " + (voice.on ? "ON" : "OFF"),
                16 * dp, y, 13, Neon.CYAN, Paint.Align.LEFT, false);
        y += 32 * dp;
        text(c, "EVENTS", 16 * dp, y, 13, Neon.MAG, Paint.Align.LEFT, true);
        if (events.isEmpty()) {
            text(c, "none", 24 * dp, y + 20 * dp, 12, Neon.GREY, Paint.Align.LEFT, false);
        }
        for (String ev : events) {
            y += 20 * dp;
            text(c, ev, 24 * dp, y, 12, Neon.YEL, Paint.Align.LEFT, false);
        }
        text(c, "Keep this screen open – the alarm stops if you leave the app.", cx, a.bottom - 8 * dp,
                10, Neon.GREY, Paint.Align.CENTER, false);
    }
}
