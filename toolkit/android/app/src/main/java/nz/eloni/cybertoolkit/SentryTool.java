package nz.eloni.cybertoolkit;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.AudioManager;
import android.media.Image;
import android.media.ToneGenerator;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Camera motion sentry: watches the scene, photographs anything that moves, alerts you. */
public class SentryTool extends CamTool {

    private static final int GW = 64, GH = 48;
    private final float[] bg = new float[GW * GH];
    private final boolean[] moved = new boolean[GW * GH];
    private boolean bgInit;
    private volatile float change;
    private float sens;
    private enum State { IDLE, COUNTDOWN, ARMED }
    private State state = State.IDLE;
    private long tState, lastTrig;
    private final List<String> events = new ArrayList<>();
    private ToneGenerator tg;
    private Btn arm, siren, light;

    public SentryTool(MainActivity a) {
        super(a);
        sens = prefs.getFloat("sentry_sens", 3f);
        arm = btn("ARM", () -> {
            if (state == State.IDLE) {
                state = State.COUNTDOWN;
                tState = System.currentTimeMillis();
                arm.label = "DISARM";
                arm.on = true;
            } else {
                state = State.IDLE;
                arm.label = "ARM";
                arm.on = false;
            }
        });
        arm.color = Neon.RED;
        btn("SENS −", () -> setSens(sens * 1.5f));
        btn("SENS +", () -> setSens(sens / 1.5f));
        siren = btn("SIREN", () -> siren.on = !siren.on);
        light = btn("LIGHT", () -> {
            light.on = !light.on;
            if (cam != null) cam.setTorch(light.on);
        });
        light.color = Neon.YEL;
    }

    private void setSens(float v) {
        sens = Neon.clamp(v, 0.3f, 30f);
        prefs.edit().putFloat("sentry_sens", sens).apply();
    }

    @Override
    String title() {
        return "CAMERA SENTRY";
    }

    @Override
    String aim() {
        return "Prop the phone up with the rear camera (top-left of back) facing the door / bench. Keep it plugged in - screen stays on.";
    }

    @Override
    void onToolStart() {
        try {
            tg = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        } catch (RuntimeException e) {
            tg = null;
        }
    }

    @Override
    void onToolStop() {
        state = State.IDLE;
        arm.label = "ARM";
        arm.on = false;
        if (tg != null) tg.release();
        tg = null;
    }

    @Override
    void onCamOpened() {
        if (cam != null) cam.setTorch(light.on);
    }

    @Override
    void onCamFrame(Image img) {
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer Y = p.getBuffer();
        int rs = p.getRowStride(), ps = p.getPixelStride();
        int w = img.getWidth(), h = img.getHeight();
        int changed = 0;
        float sum = 0;
        float[] cur = new float[GW * GH];
        for (int gy = 0; gy < GH; gy++) {
            for (int gx = 0; gx < GW; gx++) {
                int x = gx * w / GW + w / GW / 2, y = gy * h / GH + h / GH / 2;
                float v = Y.get(y * rs + x * ps) & 0xff;
                cur[gy * GW + gx] = v;
                sum += v;
            }
        }
        float mean = sum / cur.length;
        for (int i = 0; i < cur.length; i++) {
            // normalise for overall brightness changes (clouds, auto exposure)
            float v = cur[i] - mean;
            if (!bgInit) bg[i] = v;
            float d = Math.abs(v - bg[i]);
            moved[i] = d > 25;
            if (moved[i]) changed++;
            bg[i] += 0.05f * (v - bg[i]);
        }
        bgInit = true;
        change = changed * 100f / cur.length;
    }

    @Override
    void postProcess(int[] px, int w, int h) {
        if (state != State.ARMED) return;
        for (int gy = 0; gy < GH; gy++) {
            for (int gx = 0; gx < GW; gx++) {
                if (!moved[gy * GW + gx]) continue;
                int x0 = gx * w / GW, y0 = gy * h / GH, x1 = (gx + 1) * w / GW, y1 = (gy + 1) * h / GH;
                for (int y = y0; y < y1; y += 2) {
                    for (int x = x0; x < x1; x += 2) {
                        int c = px[y * w + x];
                        px[y * w + x] = 0xff000000 | (Math.min(255, ((c >> 16) & 0xff) + 120) << 16) | (c & 0x00ffff);
                    }
                }
            }
        }
    }

    private void trigger(long now) {
        lastTrig = now;
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(now));
        String file = saveSnapshot("sentry");
        events.add(0, stamp + f("  %.0f%% moved", change) + (file == null ? "" : "  ▣ saved"));
        if (events.size() > 6) events.remove(events.size() - 1);
        act.vibratePattern(new long[]{0, 300, 120, 300});
        act.notifyAlarm("⚠ CAMERA SENTRY", "Movement at " + stamp + (file == null ? "" : " - photo in Pictures/CyberToolkit"));
        if (siren.on && tg != null) tg.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 2500);
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        float w = a.width(), cx = w / 2f;
        long now = System.currentTimeMillis();
        if (state == State.COUNTDOWN && now - tState > 5000) {
            state = State.ARMED;
            bgInit = false;
        }
        if (state == State.ARMED && change > sens && now - lastTrig > 4000) trigger(now);

        RectF pv = new RectF(8 * dp, a.top + 34 * dp, w - 8 * dp, a.top + 34 * dp + (w - 16 * dp) * 4 / 3f * 0.75f);
        if (pv.bottom > a.bottom - 120 * dp) pv.bottom = a.bottom - 120 * dp;
        drawPreview(c, pv, true);
        String st = state == State.ARMED ? (now - lastTrig < 2500 ? "!! MOVEMENT !!" : "● ARMED")
                : state == State.COUNTDOWN ? "arming in " + (5 - (now - tState) / 1000) + "s – step away"
                : "DISARMED";
        int col = state == State.ARMED ? Neon.RED : state == State.COUNTDOWN ? Neon.YEL : Neon.CYAN;
        text(c, st, cx, a.top + 24 * dp, 16, col, Paint.Align.CENTER, true);
        float y = pv.bottom + 22 * dp;
        text(c, f("change %.1f%%  ·  trigger above %.1f%%", change, sens), 16 * dp, y, 12, Neon.CYAN, Paint.Align.LEFT, false);
        y += 8 * dp;
        bar(c, 16 * dp, y, w - 16 * dp, y + 12 * dp, change / (sens * 3), change > sens ? Neon.RED : Neon.GREEN);
        y += 30 * dp;
        text(c, "EVENTS (photos → Pictures/CyberToolkit)", 16 * dp, y, 12, Neon.MAG, Paint.Align.LEFT, true);
        if (events.isEmpty()) text(c, "none", 24 * dp, y + 18 * dp, 11, Neon.GREY, Paint.Align.LEFT, false);
        for (String e : events) {
            y += 17 * dp;
            if (y > a.bottom - 4 * dp) break;
            text(c, e, 20 * dp, y, 11, Neon.YEL, Paint.Align.LEFT, false);
        }
    }
}
