package nz.eloni.cybertoolkit;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Magnifier for nameplates, fuse ratings and tiny labels: zoom, light, freeze, contrast/invert,
 * COLOUR ID for cable cores / LEDs, and IR CHECK (front camera sees remote-control and CCTV IR).
 */
public class MagnifierTool extends CamTool {

    private enum Mode { NORMAL, CONTRAST, INVERT, COLOUR }

    private static final float[] ZOOMS = {1, 2, 4, 8, 16, 30};
    private int zi;
    private Mode mode = Mode.NORMAL;
    private volatile int cr, cg, cb;
    private Btn light, freeze, modeBtn, irBtn;
    private String msg = "";

    public MagnifierTool(MainActivity a) {
        super(a);
        btn("ZOOM −", () -> setZoom(zi - 1));
        btn("ZOOM +", () -> setZoom(zi + 1));
        light = btn("LIGHT", () -> {
            light.on = !light.on;
            if (cam != null) cam.setTorch(light.on);
        });
        light.color = Neon.YEL;
        freeze = btn("FREEZE", () -> {
            frozen = !frozen;
            freeze.on = frozen;
        });
        modeBtn = btn("NORMAL", () -> {
            mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
            modeBtn.label = mode == Mode.COLOUR ? "COLOUR ID" : mode.name();
            modeBtn.on = mode != Mode.NORMAL;
        });
        btn("SAVE", () -> {
            String n = saveSnapshot("zoom");
            msg = n == null ? "save failed" : "saved " + n;
        }).color = Neon.GREEN;
        irBtn = btn("IR CHECK", () -> {
            irBtn.on = !irBtn.on;
            light.on = false;
            reopen(irBtn.on);
            zi = 0;
        });
        irBtn.color = Neon.MAG;
    }

    private void setZoom(int i) {
        if (cam == null) return;
        float max = cam.maxZoom();
        int top = 0;
        for (int k = 0; k < ZOOMS.length; k++) if (ZOOMS[k] <= max) top = k;
        zi = Math.max(0, Math.min(top, i));
        cam.setZoom(ZOOMS[zi]);
    }

    @Override
    String title() {
        return irBtn != null && irBtn.on ? "IR CHECK (FRONT CAM)" : "MAGNIFIER";
    }

    @Override
    String aim() {
        return irBtn != null && irBtn.on
                ? "Point a TV remote / CCTV IR lamp at the SELFIE camera (top-centre of screen) and press a button - IR shows as a flashing light."
                : "Rear camera island (top-left of back). Hold ~10 cm from the label; LIGHT = flash beside the island.";
    }

    @Override
    int reqW() {
        return 1280;
    }

    @Override
    int reqH() {
        return 960;
    }

    @Override
    void onCamOpened() {
        if (cam != null) {
            cam.setZoom(ZOOMS[zi]);
            cam.setTorch(light.on);
        }
    }

    @Override
    void postProcess(int[] px, int w, int h) {
        // centre colour sample (9x9)
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int y = h / 2 - 4; y <= h / 2 + 4; y++) {
            for (int x = w / 2 - 4; x <= w / 2 + 4; x++) {
                int c = px[y * w + x];
                r += (c >> 16) & 0xff;
                g += (c >> 8) & 0xff;
                b += c & 0xff;
                n++;
            }
        }
        cr = (int) (r / n);
        cg = (int) (g / n);
        cb = (int) (b / n);
        if (mode == Mode.INVERT) {
            for (int i = 0; i < px.length; i++) px[i] = 0xff000000 | (~px[i] & 0x00ffffff);
        } else if (mode == Mode.CONTRAST) {
            int lo = 255, hi = 0;
            for (int i = 0; i < px.length; i += 7) {
                int c = px[i];
                int yy = (((c >> 16) & 0xff) * 3 + ((c >> 8) & 0xff) * 6 + (c & 0xff)) / 10;
                lo = Math.min(lo, yy);
                hi = Math.max(hi, yy);
            }
            int span = Math.max(16, hi - lo);
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                int yy = (((c >> 16) & 0xff) * 3 + ((c >> 8) & 0xff) * 6 + (c & 0xff)) / 10;
                int v = Math.max(0, Math.min(255, (yy - lo) * 255 / span));
                v = v > 128 ? Math.min(255, v + 40) : Math.max(0, v - 40);
                px[i] = 0xff000000 | (v << 16) | (v << 8) | v;
            }
        }
    }

    /** Names a colour from RGB (rough - lighting changes it). */
    static String colourName(int r, int g, int b) {
        float[] hsv = new float[3];
        android.graphics.Color.RGBToHSV(r, g, b, hsv);
        float hue = hsv[0], s = hsv[1], v = hsv[2];
        if (v < 0.16f) return "BLACK";
        if (s < 0.18f) return v > 0.78f ? "WHITE" : "GREY";
        if (hue < 15 || hue >= 340) return v < 0.45f ? "BROWN / DARK RED" : "RED";
        if (hue < 42) return v < 0.6f ? "BROWN" : "ORANGE";
        if (hue < 70) return "YELLOW";
        if (hue < 165) return "GREEN";
        if (hue < 195) return "CYAN / TURQUOISE";
        if (hue < 255) return "BLUE";
        if (hue < 290) return "VIOLET";
        return "PINK / MAGENTA";
    }

    private static String coreHint(String name) {
        if (name.startsWith("BROWN") || name.equals("RED")) return "AS/NZS 3000: active colour (brown new / red old)";
        if (name.equals("BLUE") || name.equals("BLACK")) return "AS/NZS 3000: neutral colour (blue new / black old)";
        if (name.equals("GREEN") || name.equals("YELLOW")) return "green-yellow = earth";
        return "";
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        float w = a.width();
        RectF pv = new RectF(8 * dp, a.top + 6 * dp, w - 8 * dp, a.bottom - (mode == Mode.COLOUR ? 70 : 26) * dp);
        boolean live = drawPreview(c, pv, true);
        float cx = pv.centerX(), cy = pv.centerY();
        if (live) {
            line(c, cx - 22 * dp, cy, cx - 8 * dp, cy, Neon.GREEN, 2);
            line(c, cx + 8 * dp, cy, cx + 22 * dp, cy, Neon.GREEN, 2);
            line(c, cx, cy - 22 * dp, cx, cy - 8 * dp, Neon.GREEN, 2);
            line(c, cx, cy + 8 * dp, cx, cy + 22 * dp, Neon.GREEN, 2);
            text(c, f("%.0f×", ZOOMS[zi]) + (frozen ? "  FROZEN" : ""), pv.left + 10 * dp, pv.top + 20 * dp, 14,
                    frozen ? Neon.MAG : Neon.GREEN, Paint.Align.LEFT, true);
        }
        if (mode == Mode.COLOUR) {
            int r = cr, g = cg, b = cb;
            String name = colourName(r, g, b);
            float y = pv.bottom + 10 * dp;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0xff000000 | (r << 16) | (g << 8) | b);
            c.drawRoundRect(new RectF(12 * dp, y, 60 * dp, y + 48 * dp), 6 * dp, 6 * dp, fill);
            text(c, name, 72 * dp, y + 18 * dp, 16, Neon.WHITE, Paint.Align.LEFT, true);
            text(c, f("#%02X%02X%02X  RGB %d,%d,%d", r, g, b, r, g, b), 72 * dp, y + 34 * dp, 11, Neon.CYAN,
                    Paint.Align.LEFT, false);
            String hint = coreHint(name);
            text(c, hint.isEmpty() ? "colour only - never trust colour to prove a conductor"
                            : hint + " - verify!", 72 * dp, y + 48 * dp, 9,
                    Neon.YEL, Paint.Align.LEFT, false);
        }
        text(c, msg.isEmpty() ? "zoom up to " + (cam == null ? "?" : f("%.0f×", cam.maxZoom())) + " · FREEZE to read, SAVE to keep" : msg,
                w / 2f, a.bottom - 8 * dp, 10, Neon.GREY, Paint.Align.CENTER, false);
    }
}
