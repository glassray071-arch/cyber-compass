package nz.eloni.cybertoolkit;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Base class for every tool screen: header, button bar, drawing helpers, sensor plumbing. */
public abstract class Tool extends View implements SensorEventListener {

    protected final MainActivity act;
    protected final float dp;
    protected final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final SensorManager sm;
    protected final SharedPreferences prefs;
    protected final List<Btn> btns = new ArrayList<>();
    protected final float headerH;
    protected boolean running;
    protected final RectF body = new RectF();

    private float downY, lastY;
    private boolean dragging;
    private final Path path = new Path();

    public static class Btn {
        String label;
        int color = Neon.CYAN;
        boolean on;
        final Runnable action;
        final RectF r = new RectF();

        Btn(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }
    }

    public Tool(MainActivity a) {
        super(a);
        act = a;
        dp = a.getResources().getDisplayMetrics().density;
        sm = (SensorManager) a.getSystemService(Context.SENSOR_SERVICE);
        prefs = a.getSharedPreferences("cybertoolkit", Context.MODE_PRIVATE);
        p.setTypeface(Neon.MONO);
        headerH = 56 * dp;
        setBackgroundColor(Neon.BG);
    }

    abstract String title();

    abstract void drawBody(Canvas c, RectF area);

    void onStart() {}

    void onStop() {}

    /** Return true if the tool handled Back itself (e.g. closing a sub-view). */
    boolean onBack() {
        return false;
    }

    void onTap(float x, float y) {}

    void onScroll(float dy) {}

    final void start() {
        if (running) return;
        running = true;
        onStart();
        invalidate();
    }

    final void stop() {
        if (!running) return;
        running = false;
        sm.unregisterListener(this);
        onStop();
    }

    protected Btn btn(String label, Runnable r) {
        Btn b = new Btn(label, r);
        btns.add(b);
        return b;
    }

    protected boolean has(int type) {
        return sm.getDefaultSensor(type) != null;
    }

    protected boolean listen(int type, int rate) {
        Sensor s = sm.getDefaultSensor(type);
        return s != null && sm.registerListener(this, s, rate);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {}

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {}

    // ------------------------------------------------------------------ frame

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Neon.PANEL);
        c.drawRect(0, 0, w, headerH, fill);
        fill.setColor(Neon.MAG);
        c.drawRect(0, headerH - 2 * dp, w, headerH, fill);
        text(c, "◀", 18 * dp, headerH * 0.68f, 22, Neon.CYAN, Paint.Align.LEFT, false);
        text(c, title(), 50 * dp, headerH * 0.66f, 17, Neon.MAG, Paint.Align.LEFT, true);

        float margin = 12 * dp, gap = 8 * dp, bh = 46 * dp;
        float bottom = h - 18 * dp;
        int perRow = 4;
        int rows = (btns.size() + perRow - 1) / perRow;
        float top = rows == 0 ? bottom : bottom - rows * bh - (rows - 1) * gap;
        for (int r = 0; r < rows; r++) {
            int start = r * perRow;
            int n = Math.min(perRow, btns.size() - start);
            float bw = (w - 2 * margin - (n - 1) * gap) / n;
            float y = top + r * (bh + gap);
            for (int k = 0; k < n; k++) {
                Btn b = btns.get(start + k);
                float x = margin + k * (bw + gap);
                b.r.set(x, y, x + bw, y + bh);
                drawBtn(c, b);
            }
        }
        body.set(0, headerH, w, (rows == 0 ? h : top) - gap);
        c.save();
        c.clipRect(body);
        drawBody(c, body);
        c.restore();

        if (running) postInvalidateOnAnimation();
    }

    private void drawBtn(Canvas c, Btn b) {
        float rad = 8 * dp;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(b.on ? Neon.alpha(b.color, 0x55) : Neon.alpha(b.color, 0x14));
        c.drawRoundRect(b.r, rad, rad, fill);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * dp);
        p.setColor(b.color);
        c.drawRoundRect(b.r, rad, rad, p);
        float size = b.label.length() > 8 ? 11 : 13;
        text(c, b.label, b.r.centerX(), b.r.centerY() + size * dp * 0.36f, size,
                b.on ? Neon.WHITE : b.color, Paint.Align.CENTER, b.on);
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
                    onScroll(e.getY() - lastY);
                    lastY = e.getY();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    float x = e.getX(), y = e.getY();
                    if (y < headerH && x < getWidth() * 0.5f) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        if (!onBack()) act.showMenu();
                    } else {
                        boolean hit = false;
                        for (Btn b : new ArrayList<>(btns)) {
                            if (b.r.contains(x, y)) {
                                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                                b.action.run();
                                hit = true;
                                break;
                            }
                        }
                        if (!hit) onTap(x, y);
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

    // ---------------------------------------------------------------- helpers

    protected void text(Canvas c, String s, float x, float y, float sizeDp, int color,
                        Paint.Align al, boolean glow) {
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(glow ? Neon.MONO_B : Neon.MONO);
        p.setTextSize(sizeDp * dp);
        p.setColor(color);
        p.setTextAlign(al);
        if (glow) p.setShadowLayer(8 * dp, 0, 0, color);
        c.drawText(s, x, y, p);
        p.clearShadowLayer();
    }

    protected void line(Canvas c, float x1, float y1, float x2, float y2, int color, float wDp) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(wDp * dp);
        p.setColor(color);
        c.drawLine(x1, y1, x2, y2, p);
    }

    protected void circle(Canvas c, float cx, float cy, float r, int color, float wDp) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(wDp * dp);
        p.setColor(color);
        c.drawCircle(cx, cy, r, p);
    }

    protected void dot(Canvas c, float cx, float cy, float r, int color, boolean glow) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        if (glow) fill.setShadowLayer(10 * dp, 0, 0, color);
        c.drawCircle(cx, cy, r, fill);
        fill.clearShadowLayer();
    }

    /** Segmented neon bar. */
    protected void bar(Canvas c, float l, float t, float r, float b, float frac, int color) {
        frac = Neon.clamp(frac, 0, 1);
        int segs = Math.max(10, (int) ((r - l) / (7 * dp)));
        float sw = (r - l) / segs;
        int lit = Math.round(frac * segs);
        fill.setStyle(Paint.Style.FILL);
        for (int i = 0; i < segs; i++) {
            fill.setColor(i < lit ? color : Neon.DIM);
            c.drawRect(l + i * sw + 1 * dp, t, l + (i + 1) * sw - 1 * dp, b, fill);
        }
    }

    /** Corner-bracket frame. */
    protected void frame(Canvas c, RectF r, int color) {
        float k = 12 * dp;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * dp);
        p.setColor(Neon.alpha(color, 0x40));
        c.drawRect(r, p);
        p.setColor(color);
        p.setStrokeWidth(2.5f * dp);
        c.drawLine(r.left, r.top, r.left + k, r.top, p);
        c.drawLine(r.left, r.top, r.left, r.top + k, p);
        c.drawLine(r.right, r.top, r.right - k, r.top, p);
        c.drawLine(r.right, r.top, r.right, r.top + k, p);
        c.drawLine(r.left, r.bottom, r.left + k, r.bottom, p);
        c.drawLine(r.left, r.bottom, r.left, r.bottom - k, p);
        c.drawLine(r.right, r.bottom, r.right - k, r.bottom, p);
        c.drawLine(r.right, r.bottom, r.right, r.bottom - k, p);
    }

    /** Line graph of a ring buffer, right-aligned, between lo and hi. */
    protected void graph(Canvas c, Ring ring, RectF box, float lo, float hi, int color) {
        if (ring.n < 2) return;
        float span = (hi - lo) == 0 ? 1 : (hi - lo);
        float step = box.width() / (ring.d.length - 1);
        float x0 = box.right - (ring.n - 1) * step;
        path.reset();
        for (int k = 0; k < ring.n; k++) {
            float y = box.bottom - Neon.clamp((ring.get(k) - lo) / span, 0, 1) * box.height();
            if (k == 0) path.moveTo(x0, y);
            else path.lineTo(x0 + k * step, y);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.8f * dp);
        p.setColor(color);
        p.setShadowLayer(6 * dp, 0, 0, color);
        c.drawPath(path, p);
        p.clearShadowLayer();
    }

    protected float y(RectF area, float fracDown) {
        return area.top + area.height() * fracDown;
    }

    protected static String f(String fmt, Object... a) {
        return Neon.f(fmt, a);
    }
}
