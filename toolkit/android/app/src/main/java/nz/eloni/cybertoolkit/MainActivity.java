package nz.eloni.cybertoolkit;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CYBER//TOOLKIT - 16 sensor tools in one neon app.
 * Plain Android SDK, no libraries, no internet permission.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERM = 11;
    private static final int REQ_PHOTO = 12;
    private static final String CH_ALARM = "alarm";

    interface PhotoCallback {
        void done(boolean ok);
    }

    private MenuView menu;
    private Tool current;
    private boolean permInFlight;
    private final Set<String> deniedThisSession = new HashSet<>();
    private Uri photoUri;
    private PhotoCallback photoCb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        menu = new MenuView(this);
        setContentView(menu);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CH_ALARM, "Motion alarm",
                    NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
    }

    // --------------------------------------------------------------- navigation

    void open(int index) {
        Tool t = Tools.make(index, this);
        if (t == null) return;
        current = t;
        setContentView(t);
        t.start();
    }

    void showMenu() {
        if (current != null) {
            current.stop();
            current = null;
        }
        setBrightness(-1f);
        setContentView(menu);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (current != null) {
            if (!current.onBack()) showMenu();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (current != null) current.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (current != null) current.stop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // -------------------------------------------------------------- permissions

    /**
     * Runs ok when every permission is granted. Otherwise asks once; after the answer the
     * current tool is restarted so its onStart() sees the new state. Denied permissions are
     * not asked again this session (denied runs instead).
     */
    void needPerms(String[] perms, Runnable ok, Runnable denied) {
        List<String> missing = new ArrayList<>();
        for (String s : perms) {
            if (checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED) missing.add(s);
        }
        if (missing.isEmpty()) {
            if (ok != null) ok.run();
            return;
        }
        for (String s : missing) {
            if (deniedThisSession.contains(s)) {
                if (denied != null) denied.run();
                return;
            }
        }
        if (permInFlight) return;
        permInFlight = true;
        requestPermissions(missing.toArray(new String[0]), REQ_PERM);
    }

    boolean granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode != REQ_PERM) return;
        permInFlight = false;
        for (int i = 0; i < perms.length; i++) {
            if (results[i] != PackageManager.PERMISSION_GRANTED) deniedThisSession.add(perms[i]);
        }
        if (current != null) {
            current.stop();
            current.start();
        }
    }

    // ------------------------------------------------------------------ photos

    void takePhoto(String name, PhotoCallback cb) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) {
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CyberToolkit");
        }
        try {
            photoUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        } catch (Exception e) {
            photoUri = null;
        }
        if (photoUri == null) {
            cb.done(false);
            return;
        }
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        photoCb = cb;
        try {
            startActivityForResult(i, REQ_PHOTO);
        } catch (ActivityNotFoundException e) {
            deletePhoto();
            photoCb = null;
            cb.done(false);
        }
    }

    private void deletePhoto() {
        try {
            if (photoUri != null) getContentResolver().delete(photoUri, null, null);
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PHOTO) return;
        boolean ok = resultCode == RESULT_OK;
        if (!ok) deletePhoto();
        PhotoCallback cb = photoCb;
        photoCb = null;
        if (cb != null) cb.done(ok);
    }

    // ------------------------------------------------------------------ extras

    @SuppressWarnings("deprecation")
    void vibrate(long ms) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    @SuppressWarnings("deprecation")
    void vibratePattern(long[] pattern) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        v.vibrate(VibrationEffect.createWaveform(pattern, -1));
    }

    void notifyAlarm(String title, String text) {
        if (Build.VERSION.SDK_INT >= 33 && !granted("android.permission.POST_NOTIFICATIONS")) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        Notification n = new Notification.Builder(this, CH_ALARM)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build();
        nm.notify((int) (System.currentTimeMillis() % 100000), n);
    }

    void setBrightness(float level) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = level;
        getWindow().setAttributes(lp);
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }
}
