package nz.eloni.cybercompass;

import android.graphics.Path;

/**
 * Minimal 3D engine for a unit-sphere Earth: perspective camera, front/back
 * face separation, great-circle arcs. Earth frame: x = (0N,0E), y = (0N,90E), z = North Pole.
 */
final class Globe {

    /** Growable float array (avoids boxing). */
    static final class FloatList {
        float[] a = new float[256];
        int n;

        void add(float v) {
            if (n == a.length) {
                float[] b = new float[a.length * 2];
                System.arraycopy(a, 0, b, 0, n);
                a = b;
            }
            a[n++] = v;
        }

        void add3(double x, double y, double z) {
            add((float) x);
            add((float) y);
            add((float) z);
        }

        float[] toArray() {
            float[] b = new float[n];
            System.arraycopy(a, 0, b, 0, n);
            return b;
        }
    }

    final float[][] coast;
    final float[][] parallels;
    final float[][] meridians;
    final float[] stars;

    // camera state
    private double cx, cy, cz;                 // camera position
    private double fx, fy, fz;                 // forward (into screen)
    private double xx, xy, xz;                 // screen right
    private double yx, yy, yz;                 // screen up
    private double focal;
    private float ox, oy;

    /** Result of the last successful project() call. */
    float px, py;

    Globe() {
        coast = new float[WorldData.SHAPES.length][];
        for (int i = 0; i < coast.length; i++) coast[i] = densifyLatLon(WorldData.SHAPES[i], 2.0);

        parallels = new float[11][];
        int k = 0;
        for (int lat = -75; lat <= 75; lat += 15) {
            FloatList l = new FloatList();
            for (int lon = 0; lon <= 360; lon += 3) addLL(l, lat, lon);
            parallels[k++] = l.toArray();
        }
        meridians = new float[24][];
        k = 0;
        for (int lon = -180; lon < 180; lon += 15) {
            FloatList l = new FloatList();
            for (int lat = -90; lat <= 90; lat += 3) addLL(l, lat, lon);
            meridians[k++] = l.toArray();
        }
        java.util.Random r = new java.util.Random(20261125L);
        stars = new float[3 * 140];
        for (int i = 0; i < 140; i++) {
            stars[3 * i] = r.nextFloat();
            stars[3 * i + 1] = r.nextFloat();
            stars[3 * i + 2] = 0.3f + 0.7f * r.nextFloat();
        }
    }

    // ------------------------------------------------------------ geometry

    static void addLL(FloatList l, double latDeg, double lonDeg) {
        double la = Math.toRadians(latDeg), lo = Math.toRadians(lonDeg);
        l.add3(Math.cos(la) * Math.cos(lo), Math.cos(la) * Math.sin(lo), Math.sin(la));
    }

    static float[] densifyLatLon(float[] ll, double maxStepDeg) {
        FloatList pts = new FloatList();
        for (int i = 0; i + 1 < ll.length; i += 2) addLL(pts, ll[i], ll[i + 1]);
        float[] p = pts.toArray();
        FloatList out = new FloatList();
        for (int i = 0; i + 3 < p.length; i += 3) {
            double ax = p[i], ay = p[i + 1], az = p[i + 2];
            double bx = p[i + 3], by = p[i + 4], bz = p[i + 5];
            double dot = Math.max(-1, Math.min(1, ax * bx + ay * by + az * bz));
            int n = Math.max(1, (int) Math.ceil(Math.toDegrees(Math.acos(dot)) / maxStepDeg));
            for (int s = 0; s < n; s++) {
                double t = (double) s / n;
                double x = ax + (bx - ax) * t, y = ay + (by - ay) * t, z = az + (bz - az) * t;
                double len = Math.sqrt(x * x + y * y + z * z);
                out.add3(x / len, y / len, z / len);
            }
        }
        out.add(p[p.length - 3]);
        out.add(p[p.length - 2]);
        out.add(p[p.length - 1]);
        return out.toArray();
    }

    /** Great-circle arc from (lat,lon) along true azimuth for lenDeg degrees of arc. */
    static float[] arcFrom(double latDeg, double lonDeg, double azDeg, double lenDeg, double stepDeg) {
        double la = Math.toRadians(latDeg), lo = Math.toRadians(lonDeg), az = Math.toRadians(azDeg);
        double ux = Math.cos(la) * Math.cos(lo), uy = Math.cos(la) * Math.sin(lo), uz = Math.sin(la);
        double nx = -Math.sin(la) * Math.cos(lo), ny = -Math.sin(la) * Math.sin(lo), nz = Math.cos(la);
        double ex = -Math.sin(lo), ey = Math.cos(lo);
        double dx = nx * Math.cos(az) + ex * Math.sin(az);
        double dy = ny * Math.cos(az) + ey * Math.sin(az);
        double dz = nz * Math.cos(az);
        FloatList l = new FloatList();
        int n = Math.max(1, (int) Math.ceil(lenDeg / stepDeg));
        for (int i = 0; i <= n; i++) {
            double t = Math.toRadians(lenDeg * i / n);
            double c = Math.cos(t), s = Math.sin(t);
            l.add3(ux * c + dx * s, uy * c + dy * s, uz * c + dz * s);
        }
        return l.toArray();
    }

    /** Great-circle arc (slerp) between two lat/lon points. */
    static float[] arcBetween(double lat1, double lon1, double lat2, double lon2, double stepDeg) {
        FloatList a = new FloatList();
        addLL(a, lat1, lon1);
        addLL(a, lat2, lon2);
        float[] p = a.toArray();
        double dot = Math.max(-1, Math.min(1, p[0] * p[3] + p[1] * p[4] + p[2] * p[5]));
        double w = Math.acos(dot);
        FloatList l = new FloatList();
        int n = Math.max(1, (int) Math.ceil(Math.toDegrees(w) / stepDeg));
        double sw = Math.sin(w);
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            double k1, k2;
            if (sw < 1e-9) {
                k1 = 1 - t;
                k2 = t;
            } else {
                k1 = Math.sin((1 - t) * w) / sw;
                k2 = Math.sin(t * w) / sw;
            }
            l.add3(k1 * p[0] + k2 * p[3], k1 * p[1] + k2 * p[4], k1 * p[2] + k2 * p[5]);
        }
        return l.toArray();
    }

    /** Great-circle distance in km (mean Earth radius). */
    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6371.0088 * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /** Initial true bearing from point 1 to point 2, 0..360. */
    static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    // ------------------------------------------------------------ camera

    /**
     * Places the camera above and ahead of the observer so that the observer sits
     * below the globe's centre and their heading points up the screen.
     */
    void setCamera(double latDeg, double lonDeg, double headingDeg, double tiltDeg, double dist,
                   float screenCx, float screenCy, float radiusPx) {
        double la = Math.toRadians(latDeg), lo = Math.toRadians(lonDeg);
        double ux = Math.cos(la) * Math.cos(lo), uy = Math.cos(la) * Math.sin(lo), uz = Math.sin(la);
        double nx = -Math.sin(la) * Math.cos(lo), ny = -Math.sin(la) * Math.sin(lo), nz = Math.cos(la);
        double ex = -Math.sin(lo), ey = Math.cos(lo), ez = 0;
        double h = Math.toRadians(headingDeg), a = Math.toRadians(tiltDeg);
        double dx = nx * Math.cos(h) + ex * Math.sin(h);
        double dy = ny * Math.cos(h) + ey * Math.sin(h);
        double dz = nz * Math.cos(h) + ez * Math.sin(h);
        double kx = ux * Math.cos(a) + dx * Math.sin(a);
        double ky = uy * Math.cos(a) + dy * Math.sin(a);
        double kz = uz * Math.cos(a) + dz * Math.sin(a);
        cx = kx * dist;
        cy = ky * dist;
        cz = kz * dist;
        fx = -kx;
        fy = -ky;
        fz = -kz;
        double d = dx * fx + dy * fy + dz * fz;
        yx = dx - d * fx;
        yy = dy - d * fy;
        yz = dz - d * fz;
        double yl = Math.sqrt(yx * yx + yy * yy + yz * yz);
        yx /= yl;
        yy /= yl;
        yz /= yl;
        xx = fy * yz - fz * yy;
        xy = fz * yx - fx * yz;
        xz = fx * yy - fy * yx;
        focal = radiusPx * Math.sqrt(dist * dist - 1);
        ox = screenCx;
        oy = screenCy;
    }

    boolean project(double x, double y, double z) {
        double vx = x - cx, vy = y - cy, vz = z - cz;
        double depth = vx * fx + vy * fy + vz * fz;
        if (depth <= 1e-6) return false;
        px = (float) (ox + focal * (vx * xx + vy * xy + vz * xz) / depth);
        py = (float) (oy - focal * (vx * yx + vy * yy + vz * yz) / depth);
        return true;
    }

    boolean projectLL(double latDeg, double lonDeg) {
        double la = Math.toRadians(latDeg), lo = Math.toRadians(lonDeg);
        return project(Math.cos(la) * Math.cos(lo), Math.cos(la) * Math.sin(lo), Math.sin(la));
    }

    /** True if a point ON the unit sphere faces the camera. */
    boolean facing(double x, double y, double z) {
        return x * cx + y * cy + z * cz > 1.0;
    }

    boolean facingLL(double latDeg, double lonDeg) {
        double la = Math.toRadians(latDeg), lo = Math.toRadians(lonDeg);
        return facing(Math.cos(la) * Math.cos(lo), Math.cos(la) * Math.sin(lo), Math.sin(la));
    }

    /** True if the Earth hides a point in space (any radius) from the camera. */
    boolean occluded(double x, double y, double z) {
        double dx = x - cx, dy = y - cy, dz = z - cz;
        double a = dx * dx + dy * dy + dz * dz;
        double b = 2 * (cx * dx + cy * dy + cz * dz);
        double c = cx * cx + cy * cy + cz * cz - 1;
        double disc = b * b - 4 * a * c;
        if (disc < 0) return false;
        double t = (-b - Math.sqrt(disc)) / (2 * a);
        return t > 1e-6 && t < 1 - 1e-6;
    }

    /** Appends a polyline of sphere points to the front or back path depending on visibility. */
    void addPolyline(float[] p, Path front, Path back) {
        int last = 0;                 // 0 none, 1 front, 2 back
        float lx = 0, ly = 0;
        boolean lastOk = false;
        boolean lastFacing = false;
        for (int i = 0; i + 2 < p.length; i += 3) {
            boolean fac = facing(p[i], p[i + 1], p[i + 2]);
            boolean ok = project(p[i], p[i + 1], p[i + 2]);
            float x = px, y = py;
            if (ok && lastOk) {
                int side = (fac && lastFacing) ? 1 : 2;
                Path path = side == 1 ? front : back;
                if (side != last) path.moveTo(lx, ly);
                path.lineTo(x, y);
                last = side;
            } else {
                last = 0;
            }
            lx = x;
            ly = y;
            lastOk = ok;
            lastFacing = fac;
        }
    }
}
