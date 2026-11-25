package nz.eloni.cybertoolkit;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Job-site log: GPS fix + note + optional photo, kept as CSV and shareable. */
public class LoggerTool extends Tool {

    private static final String HEADER = "timestamp,latitude,longitude,accuracy_m,note,photo";
    private final File csv;
    private final List<String[]> rows = new ArrayList<>();
    private String status = "";
    private boolean busy;
    private Location best;
    private LocationManager lm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private float scroll;
    private final List<RectF> rowRects = new ArrayList<>();

    private final LocationListener ll = new LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
            if (best == null || l.getAccuracy() < best.getAccuracy()) best = l;
            status = f("GPS FIX ±%.0f m…", best.getAccuracy());
            if (best.getAccuracy() <= 15) finishFix();
        }

        @Override
        public void onStatusChanged(String p, int s, Bundle b) {}

        @Override
        public void onProviderEnabled(String p) {}

        @Override
        public void onProviderDisabled(String p) {}
    };

    private final Runnable timeout = this::finishFix;

    public LoggerTool(MainActivity a) {
        super(a);
        csv = new File(a.getFilesDir(), "site_log.csv");
        btn("NEW ENTRY", this::newEntry).color = Neon.GREEN;
        btn("SHARE CSV", this::share);
        btn("DEL LAST", this::deleteLast).color = Neon.RED;
        load();
    }

    @Override
    String title() {
        return "SITE LOGGER";
    }

    @Override
    String aim() {
        return "The GPS antenna is in the top of the phone. Keep the top edge pointing at the sky and take it out of your pocket for a fix.";
    }

    @Override
    void onStart() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT < 29) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        act.needPerms(perms.toArray(new String[0]), null, () -> status = "LOCATION PERMISSION DENIED");
    }

    @Override
    void onStop() {
        stopGps();
        busy = false;
    }

    private void stopGps() {
        h.removeCallbacks(timeout);
        if (lm != null) {
            try {
                lm.removeUpdates(ll);
            } catch (Exception ignored) {
            }
        }
    }

    // --------------------------------------------------------------- new entry

    private void newEntry() {
        if (busy) return;
        if (!act.granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            act.toast("Location permission needed – saving without GPS");
            askNote(null);
            return;
        }
        busy = true;
        best = null;
        status = "ACQUIRING GPS…";
        lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
        try {
            for (String prov : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (lm.isProviderEnabled(prov)) {
                    lm.requestLocationUpdates(prov, 0, 0, ll, Looper.getMainLooper());
                }
            }
        } catch (SecurityException e) {
            status = "NO LOCATION ACCESS";
        }
        h.postDelayed(timeout, 25000);
    }

    private void finishFix() {
        if (!busy) return;
        stopGps();
        busy = false;
        if (best == null) {
            try {
                Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                best = l;
            } catch (SecurityException ignored) {
            }
        }
        status = best == null ? "NO FIX – SAVING WITHOUT GPS" : f("FIX ±%.0f m", best.getAccuracy());
        askNote(best);
    }

    private void askNote(Location loc) {
        EditText et = new EditText(act);
        et.setHint("e.g. DB2 swapped RCD, cable run to shed");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(loc == null ? "Note (no GPS)" : f("Note  (±%.0f m)", loc.getAccuracy()))
                .setView(et)
                .setPositiveButton("SAVE", (d, w) -> save(loc, et.getText().toString(), ""))
                .setNeutralButton("SAVE + PHOTO", (d, w) -> {
                    String name = "site_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                            .format(new Date()) + ".jpg";
                    String note = et.getText().toString();
                    act.takePhoto(name, ok -> save(loc, note, ok ? name : ""));
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void save(Location loc, String note, String photo) {
        String[] r = {
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()),
                loc == null ? "" : f("%.6f", loc.getLatitude()),
                loc == null ? "" : f("%.6f", loc.getLongitude()),
                loc == null ? "" : f("%.0f", loc.getAccuracy()),
                note.trim(), photo};
        rows.add(r);
        writeAll();
        status = "SAVED ✓" + (photo.isEmpty() ? "" : " + PHOTO (Pictures/CyberToolkit)");
    }

    private void deleteLast() {
        if (rows.isEmpty()) return;
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Delete last entry?")
                .setMessage(rows.get(rows.size() - 1)[4])
                .setPositiveButton("DELETE", (d, w) -> {
                    rows.remove(rows.size() - 1);
                    writeAll();
                    status = "DELETED";
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void share() {
        if (rows.isEmpty()) {
            act.toast("Nothing logged yet");
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "Site log " +
                new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
        i.putExtra(Intent.EXTRA_TEXT, csvText());
        act.startActivity(Intent.createChooser(i, "Share site log"));
    }

    // ------------------------------------------------------------------- csv

    private static String esc(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private String csvText() {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (String[] r : rows) {
            for (int i = 0; i < r.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(esc(r[i]));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private void writeAll() {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(csv), StandardCharsets.UTF_8)) {
            w.write(csvText());
        } catch (Exception e) {
            status = "SAVE FAILED: " + e.getMessage();
        }
    }

    private static String[] parse(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (ch == '"') {
                    q = false;
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                q = true;
            } else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        while (out.size() < 6) out.add("");
        return out.toArray(new String[0]);
    }

    private void load() {
        rows.clear();
        if (!csv.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(csv))) {
            String line = br.readLine(); // header
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) rows.add(parse(line));
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ draw

    @Override
    void onScroll(float dy) {
        scroll = Math.max(0, scroll - dy);
    }

    @Override
    void onTap(float x, float y) {
        for (int i = 0; i < rowRects.size(); i++) {
            if (rowRects.get(i).contains(x, y)) {
                String[] r = rows.get(rows.size() - 1 - i);
                if (r[1].isEmpty()) return;
                Uri u = Uri.parse("geo:" + r[1] + "," + r[2] + "?q=" + r[1] + "," + r[2] + "("
                        + Uri.encode(r[4].isEmpty() ? "Site" : r[4]) + ")");
                try {
                    act.startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception e) {
                    act.toast("No maps app found");
                }
                return;
            }
        }
    }

    @Override
    void drawBody(Canvas c, RectF a) {
        float w = a.width();
        float y = a.top + 28 * dp;
        text(c, rows.size() + " ENTRIES", 16 * dp, y, 16, Neon.GREEN, Paint.Align.LEFT, true);
        y += 22 * dp;
        boolean dots = busy && (System.currentTimeMillis() / 400) % 2 == 0;
        text(c, status + (dots ? " ●" : ""), 16 * dp, y, 12, busy ? Neon.YEL : Neon.CYAN, Paint.Align.LEFT, false);
        y += 18 * dp;
        text(c, "tap an entry to open it in Maps · drag to scroll", 16 * dp, y, 10, Neon.GREY,
                Paint.Align.LEFT, false);
        y += 14 * dp;
        rowRects.clear();
        float rh = 58 * dp;
        float listTop = y;
        scroll = Math.min(scroll, Math.max(0, rows.size() * rh - (a.bottom - listTop)));
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(rows.size() - 1 - i);
            float top = listTop + i * rh - scroll;
            RectF rr = new RectF(12 * dp, top + 4 * dp, w - 12 * dp, top + rh - 4 * dp);
            rowRects.add(rr);
            if (rr.bottom < listTop || rr.top > a.bottom) continue;
            frame(c, rr, Neon.alpha(Neon.CYAN, 0xA0));
            String note = r[4].isEmpty() ? "(no note)" : r[4];
            if (note.length() > 34) note = note.substring(0, 33) + "…";
            text(c, note, rr.left + 10 * dp, rr.top + 20 * dp, 13, Neon.WHITE, Paint.Align.LEFT, false);
            String loc = r[1].isEmpty() ? "no GPS" : r[1] + ", " + r[2] + " ±" + r[3] + "m";
            text(c, r[0].substring(Math.min(5, r[0].length())) + "  " + loc + (r[5].isEmpty() ? "" : "  ▣"),
                    rr.left + 10 * dp, rr.top + 40 * dp, 10, Neon.CYAN, Paint.Align.LEFT, false);
        }
        if (rows.isEmpty()) {
            text(c, "Tap NEW ENTRY on site to log location + note.", w / 2f, listTop + 40 * dp, 12,
                    Neon.GREY, Paint.Align.CENTER, false);
        }
    }
}
