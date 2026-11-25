package nz.eloni.cybertoolkit;

import android.Manifest;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;

import java.util.Arrays;

/**
 * Experimental echo rangefinder: the speaker plays a short chirp, the mic records it,
 * cross-correlation finds the direct sound and the first echo; distance = delay x 343 / 2.
 */
public class SonarTool extends Tool {

    private static final int SR = 48000;
    private static final float C_SOUND = 343f;
    private static final float MAX_M = 6f;
    private final short[] chirp;
    private final float[] chirpF;
    private volatile float[] envDraw;
    private volatile float lastDist = Float.NaN, quality;
    private final float[] recent = new float[5];
    private int recentN;
    private volatile boolean busy, go;
    private Thread auto;
    private String status = "Tap PING";
    private Btn autoBtn;

    public SonarTool(MainActivity a) {
        super(a);
        int n = (int) (0.004f * SR);
        chirp = new short[n];
        chirpF = new float[n];
        double f0 = 3000, f1 = 9000, T = n / (double) SR;
        for (int i = 0; i < n; i++) {
            double t = i / (double) SR;
            double ph = 2 * Math.PI * (f0 * t + (f1 - f0) * t * t / (2 * T));
            double wv = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
            chirpF[i] = (float) (Math.sin(ph) * wv);
            chirp[i] = (short) (chirpF[i] * 30000);
        }
        btn("PING", () -> ping(true)).color = Neon.GREEN;
        autoBtn = btn("AUTO", this::toggleAuto);
        btn("CLEAR", () -> {
            recentN = 0;
            lastDist = Float.NaN;
        });
    }

    @Override
    String title() {
        return "SONAR (EXPERIMENTAL)";
    }

    @Override
    void onStart() {
        act.needPerms(new String[]{Manifest.permission.RECORD_AUDIO}, null,
                () -> status = "MICROPHONE PERMISSION DENIED");
    }

    @Override
    void onStop() {
        go = false;
        autoBtn.on = false;
    }

    private void toggleAuto() {
        autoBtn.on = !autoBtn.on;
        go = autoBtn.on;
        if (go) {
            auto = new Thread(() -> {
                while (go) {
                    ping(false);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "sonar-auto");
            auto.start();
        }
    }

    private void ping(boolean async) {
        if (!act.granted(Manifest.permission.RECORD_AUDIO)) {
            status = "NEEDS MICROPHONE PERMISSION";
            return;
        }
        if (busy) return;
        busy = true;
        if (async) new Thread(this::doPing, "sonar").start();
        else doPing();
    }

    private void doPing() {
        AudioRecord rec = null;
        AudioTrack track = null;
        try {
            int recN = (int) (0.45f * SR);
            rec = Audio.open(act, SR, recN * 2);
            if (rec == null) {
                status = "MICROPHONE UNAVAILABLE";
                return;
            }
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(chirp.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            track.write(chirp, 0, chirp.length);
            short[] warm = new short[SR / 20];
            short[] buf = new short[recN];
            rec.startRecording();
            Audio.readFully(rec, warm);
            track.play();
            Audio.readFully(rec, buf);
            analyse(buf);
        } catch (Exception e) {
            status = "AUDIO ERROR: " + e.getClass().getSimpleName();
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (Exception ignored) {
                }
                track.release();
            }
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Exception ignored) {
                }
                rec.release();
            }
            busy = false;
        }
    }

    private void analyse(short[] buf) {
        int L = chirpF.length;
        int n = buf.length - L;
        float[] env = new float[n];
        float peak = 0;
        int direct = 0;
        for (int i = 0; i < n; i++) {
            float s = 0;
            for (int k = 0; k < L; k++) s += buf[i + k] * chirpF[k];
            env[i] = Math.abs(s);
            if (env[i] > peak) {
                peak = env[i];
                direct = i;
            }
        }
        // smooth envelope (max over 12 samples)
        float[] sm = new float[n];
        for (int i = 0; i < n; i++) {
            float m = 0;
            for (int k = Math.max(0, i - 6); k < Math.min(n, i + 6); k++) m = Math.max(m, env[k]);
            sm[i] = m;
        }
        int guard = (int) (2 * 0.45f / C_SOUND * SR);          // ignore echoes nearer than 0.45 m
        int span = (int) (2 * MAX_M / C_SOUND * SR);
        int from = direct + guard, to = Math.min(n - 1, direct + span);
        if (peak <= 0 || from >= to) {
            status = "NO CHIRP HEARD – TURN MEDIA VOLUME UP";
            return;
        }
        float[] noise = Arrays.copyOfRange(sm, Math.min(n - 1, to), n);
        Arrays.sort(noise);
        float floor = noise.length > 0 ? noise[noise.length / 2] : 0;
        int echo = -1;
        float eBest = 0;
        for (int i = from + 1; i < to - 1; i++) {
            if (sm[i] > eBest && sm[i] >= sm[i - 1] && sm[i] >= sm[i + 1]) {
                eBest = sm[i];
                echo = i;
            }
        }
        float[] plot = new float[span];
        for (int i = 0; i < span && direct + i < n; i++) plot[i] = sm[direct + i] / peak;
        envDraw = plot;
        quality = floor > 0 ? eBest / floor : 0;
        if (echo < 0 || eBest < peak * 0.04f || quality < 3f) {
            status = "NO CLEAR ECHO – aim at a flat wall";
            return;
        }
        float d = (echo - direct) / (float) SR * C_SOUND / 2f;
        recent[recentN % recent.length] = d;
        recentN++;
        int m = Math.min(recentN, recent.length);
        float[] s = Arrays.copyOf(recent, m);
        Arrays.sort(s);
        lastDist = s[m / 2];
        status = f("echo %.2f m · quality %.0f×", d, quality);
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        float w = a.width(), cx = w / 2f;
        float d = lastDist;
        text(c, Float.isNaN(d) ? "— — m" : f("%.2f m", d), cx, a.top + 74 * dp, 46,
                Float.isNaN(d) ? Neon.GREY : Neon.GREEN, Paint.Align.CENTER, true);
        text(c, busy ? "PINGING…" : status, cx, a.top + 102 * dp, 12, busy ? Neon.YEL : Neon.CYAN,
                Paint.Align.CENTER, false);

        RectF g = new RectF(16 * dp, a.top + 126 * dp, w - 16 * dp, a.top + 126 * dp + a.height() * 0.38f);
        frame(c, g, Neon.CYAN);
        for (int m = 1; m < MAX_M; m++) {
            float x = g.left + g.width() * m / MAX_M;
            line(c, x, g.top, x, g.bottom, Neon.alpha(Neon.CYAN, 0x25), 1);
            text(c, m + "m", x, g.bottom + 13 * dp, 9, Neon.GREY, Paint.Align.CENTER, false);
        }
        float[] env = envDraw;
        if (env != null) {
            Path path = new Path();
            for (int i = 0; i < env.length; i++) {
                float x = g.left + g.width() * i / env.length;
                float y = g.bottom - Neon.clamp(env[i] * 4f, 0, 1) * g.height();
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f * dp);
            p.setColor(Neon.YEL);
            c.drawPath(path, p);
        }
        if (!Float.isNaN(d)) {
            float x = g.left + g.width() * d / MAX_M;
            line(c, x, g.top, x, g.bottom, Neon.MAG, 2.5f);
        }
        float y = g.bottom + 36 * dp;
        String[] tips = {
                "• Media volume UP · quiet room",
                "• Point the BOTTOM edge (speaker + mic) at a wall",
                "• Works best 0.5 – 4 m from a large flat surface",
                "• Echo graph ×4 zoom: tall spike = reflection",
                "• Experimental – some phones filter the echo out"};
        for (String t : tips) {
            text(c, t, 16 * dp, y, 11, Neon.GREY, Paint.Align.LEFT, false);
            y += 17 * dp;
        }
    }
}
