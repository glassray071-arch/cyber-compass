package nz.eloni.cybertoolkit;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.InputType;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Torch, strobe (doubles as a stroboscope tachometer), SOS, Morse messages and screen light. */
public class TorchTool extends Tool {

    private enum Mode { OFF, ON, STROBE, SOS, MORSE, SCREEN }

    private static final Map<Character, String> MORSE = new HashMap<>();

    static {
        String[] codes = {".-", "-...", "-.-.", "-..", ".", "..-.", "--.", "....", "..", ".---", "-.-",
                ".-..", "--", "-.", "---", ".--.", "--.-", ".-.", "...", "-", "..-", "...-", ".--", "-..-",
                "-.--", "--.."};
        for (int i = 0; i < 26; i++) MORSE.put((char) ('A' + i), codes[i]);
        String[] digits = {"-----", ".----", "..---", "...--", "....-", ".....", "-....", "--...", "---..", "----."};
        for (int i = 0; i < 10; i++) MORSE.put((char) ('0' + i), digits[i]);
    }

    private final CameraManager cm;
    private String camId;
    private Mode mode = Mode.OFF;
    private volatile boolean lit;
    private float hz;
    private String message = "SOS";
    private HandlerThread ht;
    private Handler hh;
    private final List<long[]> pattern = new ArrayList<>(); // {on(1/0), ms}
    private int pIdx;
    private Btn bTorch, bStrobe, bSos, bMorse, bScreen;

    public TorchTool(MainActivity a) {
        super(a);
        cm = (CameraManager) a.getSystemService(Context.CAMERA_SERVICE);
        hz = prefs.getFloat("strobe_hz", 5f);
        bTorch = btn("TORCH", () -> setMode(mode == Mode.ON ? Mode.OFF : Mode.ON));
        bStrobe = btn("STROBE", () -> setMode(mode == Mode.STROBE ? Mode.OFF : Mode.STROBE));
        bSos = btn("SOS", () -> setMode(mode == Mode.SOS ? Mode.OFF : Mode.SOS));
        bMorse = btn("MORSE", this::askMorse);
        btn("HZ −", () -> setHz(hz < 10 ? hz - 0.5f : hz - 1));
        btn("HZ +", () -> setHz(hz < 10 ? hz + 0.5f : hz + 1));
        bScreen = btn("SCREEN", () -> setMode(mode == Mode.SCREEN ? Mode.OFF : Mode.SCREEN));
        bSos.color = Neon.RED;
        bTorch.color = Neon.YEL;
    }

    private void setHz(float v) {
        hz = Neon.clamp(v, 0.5f, 30f);
        prefs.edit().putFloat("strobe_hz", hz).apply();
        if (mode == Mode.STROBE) setMode(Mode.STROBE);
    }

    @Override
    String title() {
        return "TORCH / STROBE";
    }

    @Override
    void onStart() {
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash)) {
                    if (camId == null || (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK)) {
                        camId = id;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        ht = new HandlerThread("torch");
        ht.start();
        hh = new Handler(ht.getLooper());
    }

    @Override
    void onStop() {
        setMode(Mode.OFF);
        if (ht != null) ht.quitSafely();
        ht = null;
        hh = null;
    }

    private void torch(boolean on) {
        lit = on;
        if (camId == null) return;
        try {
            cm.setTorchMode(camId, on);
        } catch (Exception ignored) {
        }
    }

    private void setMode(Mode m) {
        if (hh != null) hh.removeCallbacksAndMessages(null);
        torch(false);
        act.setBrightness(-1f);
        mode = m;
        bTorch.on = m == Mode.ON;
        bStrobe.on = m == Mode.STROBE;
        bSos.on = m == Mode.SOS;
        bMorse.on = m == Mode.MORSE;
        bScreen.on = m == Mode.SCREEN;
        switch (m) {
            case ON:
                torch(true);
                break;
            case STROBE:
                long half = Math.max(10, (long) (500 / hz));
                pattern.clear();
                pattern.add(new long[]{1, half});
                pattern.add(new long[]{0, half});
                runPattern();
                break;
            case SOS:
                buildMorse("SOS");
                runPattern();
                break;
            case MORSE:
                buildMorse(message);
                runPattern();
                break;
            case SCREEN:
                act.setBrightness(1f);
                break;
            default:
                break;
        }
    }

    private void buildMorse(String text) {
        long u = 200;
        pattern.clear();
        for (char ch : text.toUpperCase().toCharArray()) {
            if (ch == ' ') {
                pattern.add(new long[]{0, 4 * u});
                continue;
            }
            String code = MORSE.get(ch);
            if (code == null) continue;
            for (char s : code.toCharArray()) {
                pattern.add(new long[]{1, s == '.' ? u : 3 * u});
                pattern.add(new long[]{0, u});
            }
            pattern.add(new long[]{0, 2 * u});
        }
        pattern.add(new long[]{0, 7 * u});
    }

    private void runPattern() {
        final Handler handler = hh;
        if (handler == null || pattern.isEmpty()) return;
        final List<long[]> pat = new ArrayList<>(pattern);
        pIdx = 0;
        handler.post(new Runnable() {
            @Override
            public void run() {
                long[] step = pat.get(pIdx % pat.size());
                torch(step[0] == 1);
                pIdx++;
                handler.postDelayed(this, step[1]);
            }
        });
    }

    private void askMorse() {
        if (mode == Mode.MORSE) {
            setMode(Mode.OFF);
            return;
        }
        EditText et = new EditText(act);
        et.setText(message);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Morse message (A-Z, 0-9)")
                .setView(et)
                .setPositiveButton("FLASH", (d, w) -> {
                    String s = et.getText().toString().trim();
                    message = s.isEmpty() ? "SOS" : s;
                    setMode(Mode.MORSE);
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        float w = a.width(), cx = w / 2f;
        if (mode == Mode.SCREEN) {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0xFFFFFFFF);
            c.drawRect(a, fill);
            text(c, "SCREEN LIGHT · tap SCREEN to stop", cx, a.bottom - 14 * dp, 12, 0xFF000000,
                    Paint.Align.CENTER, false);
            return;
        }
        float R = Math.min(w * 0.28f, a.height() * 0.2f);
        float cy = a.top + 40 * dp + R;
        if (lit) {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Neon.alpha(Neon.YEL, 0x40));
            c.drawCircle(cx, cy, R * 1.25f, fill);
            dot(c, cx, cy, R, Neon.YEL, true);
        } else {
            circle(c, cx, cy, R, Neon.alpha(Neon.YEL, 0x80), 3);
        }
        text(c, mode.name(), cx, cy + 8 * dp, 20, lit ? 0xFF000000 : Neon.YEL, Paint.Align.CENTER, !lit);

        float y = cy + R + 40 * dp;
        if (camId == null) {
            text(c, "No camera flash found – SCREEN light still works", cx, y, 12, Neon.RED,
                    Paint.Align.CENTER, false);
            y += 24 * dp;
        }
        text(c, f("STROBE %.1f Hz  =  %.0f RPM", hz, hz * 60), cx, y, 18, Neon.MAG, Paint.Align.CENTER, true);
        y += 30 * dp;
        String[] tips = {
                "STROBE TACHO: in a dim room, shine the strobe on a",
                "marked fan / pulley / shaft. Adjust HZ until the mark",
                "looks frozen – RPM shown above (or a whole multiple).",
                "",
                "MORSE: type a message and the torch flashes it.",
                "SOS repeats ··· – – – ··· until stopped.",
                "Phone LEDs can't strobe precisely above ~15 Hz."};
        for (String t : tips) {
            text(c, t, 16 * dp, y, 11, Neon.GREY, Paint.Align.LEFT, false);
            y += 16 * dp;
        }
        if (mode == Mode.MORSE) {
            text(c, "» " + message, cx, y + 16 * dp, 16, Neon.CYAN, Paint.Align.CENTER, true);
        }
    }
}
