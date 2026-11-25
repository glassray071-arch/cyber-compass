package nz.eloni.cybertoolkit;

import android.graphics.Typeface;

import java.util.Locale;

/** Shared neon palette and small maths helpers. */
final class Neon {
    static final int BG = 0xFF05060C;
    static final int PANEL = 0xFF0A0F1E;
    static final int CYAN = 0xFF00F0FF;
    static final int MAG = 0xFFFF1FD1;
    static final int GREEN = 0xFF39FF14;
    static final int YEL = 0xFFFFE600;
    static final int RED = 0xFFFF3355;
    static final int ORANGE = 0xFFFF8A00;
    static final int DIM = 0xFF1C3B4A;
    static final int GREY = 0xFF5A6478;
    static final int WHITE = 0xFFE8F6FF;

    static final Typeface MONO = Typeface.MONOSPACE;
    static final Typeface MONO_B = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);

    static final float G = 9.80665f;

    private Neon() {}

    static String f(String fmt, Object... a) {
        return String.format(Locale.US, fmt, a);
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
