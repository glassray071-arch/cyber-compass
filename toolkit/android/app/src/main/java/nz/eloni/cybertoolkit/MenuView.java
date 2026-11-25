package nz.eloni.cybertoolkit;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Neon tile menu (2 columns, scrolls if the screen is short). */
public class MenuView extends View {

    private final MainActivity act;
    private final float dp;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] tiles = new RectF[Tools.NAMES.length];
    private final boolean[] avail = new boolean[Tools.NAMES.length];
    private final String version;
    private float scroll, maxScroll, downY, lastY;
    private boolean dragging;
    private final long t0 = System.currentTimeMillis();

    public MenuView(MainActivity a) {
        super(a);
        act = a;
        dp = a.getResources().getDisplayMetrics().density;
        for (int i = 0; i < tiles.length; i++) {
            tiles[i] = new RectF();
            avail[i] = Tools.available(i, a);
        }
        version = a.version();
        setBackgroundColor(Neon.BG);
    }

    private void text(Canvas c, String s, float x, float y, float size, int color,
                      Paint.Align al, boolean glow) {
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(glow ? Neon.MONO_B : Neon.MONO);
        p.setTextSize(size * dp);
        p.setColor(color);
        p.setTextAlign(al);
        if (glow) p.setShadowLayer(10 * dp, 0, 0, color);
        c.drawText(s, x, y, p);
        p.clearShadowLayer();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float t = (System.currentTimeMillis() - t0) / 1000f;

        c.save();
        c.translate(0, -scroll);
        text(c, "CYBER//TOOLKIT", w / 2f, 58 * dp, 28, Neon.MAG, Paint.Align.CENTER, true);
        text(c, Tools.NAMES.length + " SENSOR TOOLS  ·  v" + version, w / 2f, 82 * dp, 12,
                Neon.CYAN, Paint.Align.CENTER, false);

        float m = 12 * dp, gap = 10 * dp, th = 92 * dp, top = 104 * dp;
        float tw = (w - 2 * m - gap) / 2f;
        for (int i = 0; i < tiles.length; i++) {
            int col = i % 2, row = i / 2;
            float x = m + col * (tw + gap), y = top + row * (th + gap);
            tiles[i].set(x, y, x + tw, y + th);
            int color = avail[i] ? Tools.COLORS[i] : Neon.GREY;
            float pulse = 0.5f + 0.5f * (float) Math.sin(t * 2 + i * 0.6);

            p.setStyle(Paint.Style.FILL);
            p.setColor(Neon.alpha(color, 0x10 + (int) (pulse * 0x10)));
            c.drawRoundRect(tiles[i], 10 * dp, 10 * dp, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f * dp);
            p.setColor(Neon.alpha(color, 0xC0));
            c.drawRoundRect(tiles[i], 10 * dp, 10 * dp, p);

            text(c, Tools.GLYPHS[i], x + 14 * dp, y + 36 * dp, 20, color, Paint.Align.LEFT, true);
            text(c, f2(i + 1), x + tw - 10 * dp, y + 22 * dp, 11, Neon.alpha(color, 0x99),
                    Paint.Align.RIGHT, false);
            text(c, Tools.NAMES[i], x + 14 * dp, y + 62 * dp, 14, Neon.WHITE, Paint.Align.LEFT, false);
            text(c, avail[i] ? Tools.DESCS[i] : "not on this phone", x + 14 * dp, y + 80 * dp, 11,
                    Neon.alpha(color, 0xCC), Paint.Align.LEFT, false);
        }
        float end = top + ((tiles.length + 1) / 2) * (th + gap) + 30 * dp;
        text(c, "made for Eloni · offline · no ads", w / 2f, end - 6 * dp, 11, Neon.GREY,
                Paint.Align.CENTER, false);
        c.restore();
        maxScroll = Math.max(0, end - h);

        // scanline
        float sy = (t * 120 * dp) % (h + 40 * dp) - 20 * dp;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x1400F0FF);
        c.drawRect(0, sy, w, sy + 3 * dp, p);
        postInvalidateOnAnimation();
    }

    private static String f2(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = lastY = e.getY();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getY() - downY) > 12 * dp) dragging = true;
                if (dragging) {
                    scroll = Neon.clamp(scroll - (e.getY() - lastY), 0, maxScroll);
                    lastY = e.getY();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    float x = e.getX(), y = e.getY() + scroll;
                    for (int i = 0; i < tiles.length; i++) {
                        if (tiles[i].contains(x, y)) {
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            act.open(i);
                            break;
                        }
                    }
                    performClick();
                }
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
