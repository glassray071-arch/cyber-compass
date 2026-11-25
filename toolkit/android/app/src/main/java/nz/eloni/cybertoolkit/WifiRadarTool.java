package nz.eloni.cybertoolkit;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/** Nearby Wi-Fi access points on a radar, placed by estimated distance from signal strength. */
public class WifiRadarTool extends Tool {

    private final WifiManager wm;
    private final List<ScanResult> aps = new ArrayList<>();
    private String status = "STARTING";
    private long lastResults;
    private boolean registered;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final long t0 = System.currentTimeMillis();

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            pull();
            status = "LIVE";
        }
    };

    private final Runnable auto = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            scan();
            h.postDelayed(this, 30000);
        }
    };

    public WifiRadarTool(MainActivity a) {
        super(a);
        wm = (WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        btn("RESCAN", this::scan);
    }

    @Override
    String title() {
        return "WI-FI RADAR";
    }

    @Override
    String aim() {
        return "Hold it upright in the open. Keep your hand off the top and bottom of the frame - the Wi-Fi antennas are there.";
    }

    @Override
    void onStart() {
        act.needPerms(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, () -> {
            IntentFilter f = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                act.registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
            } else {
                act.registerReceiver(rx, f);
            }
            registered = true;
            pull();
            h.post(auto);
        }, () -> status = "LOCATION PERMISSION DENIED");
    }

    @Override
    void onStop() {
        h.removeCallbacks(auto);
        if (registered) {
            try {
                act.unregisterReceiver(rx);
            } catch (Exception ignored) {
            }
            registered = false;
        }
    }

    @SuppressWarnings("deprecation")
    private void scan() {
        if (wm == null) {
            status = "NO WI-FI";
            return;
        }
        if (!wm.isWifiEnabled()) {
            status = "WI-FI IS OFF";
            return;
        }
        LocationManager lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 28 && lm != null && !lm.isLocationEnabled()) {
            status = "TURN ON LOCATION";
            return;
        }
        boolean ok = false;
        try {
            ok = wm.startScan();
        } catch (Exception ignored) {
        }
        status = ok ? "SCANNING…" : "ANDROID SCAN LIMIT – CACHED";
        pull();
    }

    private void pull() {
        try {
            List<ScanResult> r = wm.getScanResults();
            aps.clear();
            if (r != null) aps.addAll(r);
            aps.sort((x, y) -> y.level - x.level);
            lastResults = System.currentTimeMillis();
        } catch (SecurityException e) {
            status = "NEED LOCATION PERMISSION";
        }
    }

    static float estDistance(int rssi, int freq) {
        float ref = freq < 3000 ? -40f : -47f;
        return (float) Math.pow(10, (ref - rssi) / (10 * 2.7));
    }

    private static int levelColor(int rssi) {
        return rssi > -60 ? Neon.GREEN : rssi > -75 ? Neon.YEL : Neon.RED;
    }

    @SuppressWarnings("deprecation")
    private static String ssid(ScanResult r) {
        String s = r.SSID;
        return (s == null || s.isEmpty()) ? "<hidden>" : s;
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        float w = a.width();
        float R = Math.min(w * 0.42f, a.height() * 0.28f);
        float cx = w / 2f, cy = a.top + R + 22 * dp;
        float sweep = ((System.currentTimeMillis() - t0) / 1000f * 120f) % 360f;

        for (int k = 1; k <= 3; k++) circle(c, cx, cy, R * k / 3f, Neon.alpha(Neon.GREEN, 0x50), 1.2f);
        line(c, cx - R, cy, cx + R, cy, Neon.alpha(Neon.GREEN, 0x30), 1);
        line(c, cx, cy - R, cx, cy + R, Neon.alpha(Neon.GREEN, 0x30), 1);
        for (int k = 0; k < 24; k++) {
            double ang = Math.toRadians(sweep - k * 1.5);
            line(c, cx, cy, cx + (float) (R * Math.cos(ang)), cy + (float) (R * Math.sin(ang)),
                    Neon.alpha(Neon.GREEN, Math.max(0, 0xC0 - k * 8)), k == 0 ? 2.5f : 2f);
        }
        text(c, "1m", cx + R / 3f + 2 * dp, cy - 3 * dp, 9, Neon.GREY, Paint.Align.LEFT, false);
        text(c, "8m", cx + 2 * R / 3f + 2 * dp, cy - 3 * dp, 9, Neon.GREY, Paint.Align.LEFT, false);
        text(c, "60m", cx + R + 2 * dp, cy - 3 * dp, 9, Neon.GREY, Paint.Align.LEFT, false);

        int shown = Math.min(aps.size(), 20);
        for (int i = 0; i < shown; i++) {
            ScanResult r = aps.get(i);
            float d = estDistance(r.level, r.frequency);
            float frac = (float) (Math.log10(1 + d) / Math.log10(61));
            float rr = Math.max(6 * dp, Neon.clamp(frac, 0, 1) * R);
            String key = r.BSSID == null ? String.valueOf(i) : r.BSSID;
            int ang = (key.hashCode() & 0x7fffffff) % 360;
            float bx = cx + (float) (rr * Math.cos(Math.toRadians(ang)));
            float by = cy + (float) (rr * Math.sin(Math.toRadians(ang)));
            float diff = Math.abs(((sweep - ang) % 360 + 540) % 360 - 180);
            boolean lit = diff < 30;
            int col = levelColor(r.level);
            dot(c, bx, by, (lit ? 6 : 4) * dp, col, lit);
            text(c, String.valueOf(i + 1), bx + 7 * dp, by - 5 * dp, 10, col, Paint.Align.LEFT, false);
        }
        dot(c, cx, cy, 5 * dp, Neon.CYAN, true);

        float y = cy + R + 30 * dp;
        long age = lastResults == 0 ? -1 : (System.currentTimeMillis() - lastResults) / 1000;
        text(c, aps.size() + " NETWORKS  ·  " + status, 14 * dp, y, 12, Neon.CYAN, Paint.Align.LEFT, false);
        y += 16 * dp;
        text(c, age < 0 ? "waiting for scan…" : "updated " + age + "s ago · auto every 30s", 14 * dp, y,
                10, Neon.GREY, Paint.Align.LEFT, false);
        y += 22 * dp;
        for (int i = 0; i < shown && y < a.bottom - 4 * dp; i++) {
            ScanResult r = aps.get(i);
            String band = r.frequency >= 5900 ? "6G " : r.frequency >= 5000 ? "5G " : "2.4";
            String name = ssid(r);
            if (name.length() > 16) name = name.substring(0, 15) + "…";
            String row = f("%2d %-16s %s %4ddBm ≈%3.0fm", i + 1, name, band, r.level,
                    estDistance(r.level, r.frequency));
            text(c, row, 12 * dp, y, 12, levelColor(r.level), Paint.Align.LEFT, false);
            y += 19 * dp;
        }
        if (shown == 0) {
            text(c, "Needs Wi-Fi ON + Location ON", w / 2f, y + 10 * dp, 12, Neon.YEL,
                    Paint.Align.CENTER, false);
        }
        text(c, "distance is an estimate · direction is not measured", w / 2f, a.bottom - 4 * dp, 9,
                Neon.GREY, Paint.Align.CENTER, false);
    }
}
