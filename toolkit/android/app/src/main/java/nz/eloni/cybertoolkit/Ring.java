package nz.eloni.cybertoolkit;

/** Fixed-size ring buffer of floats. get(0) is the oldest sample. */
final class Ring {
    final float[] d;
    int n, i;

    Ring(int cap) {
        d = new float[cap];
    }

    void add(float v) {
        d[i] = v;
        i = (i + 1) % d.length;
        if (n < d.length) n++;
    }

    float get(int k) {
        return d[((i - n + k) % d.length + d.length) % d.length];
    }

    float last() {
        return n == 0 ? 0 : get(n - 1);
    }

    void clear() {
        n = 0;
        i = 0;
    }

    float min() {
        float m = Float.MAX_VALUE;
        for (int k = 0; k < n; k++) m = Math.min(m, get(k));
        return n == 0 ? 0 : m;
    }

    float max() {
        float m = -Float.MAX_VALUE;
        for (int k = 0; k < n; k++) m = Math.max(m, get(k));
        return n == 0 ? 0 : m;
    }

    float maxAbs() {
        float m = 0;
        for (int k = 0; k < n; k++) m = Math.max(m, Math.abs(get(k)));
        return m;
    }
}
