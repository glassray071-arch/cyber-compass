package nz.eloni.cybercompass;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

/**
 * CYBER//COMPASS - native Android version.
 * Uses the fused rotation-vector sensor when available (smooth, gyro-assisted),
 * otherwise accelerometer + magnetometer. No libraries, no internet.
 */
public class MainActivity extends Activity implements SensorEventListener, CompassView.Callbacks {

    private static final int REQ_LOCATION = 7;

    private SensorManager sm;
    private Sensor rotationSensor, accelSensor, magSensor;
    private boolean useRotation;
    private CompassView view;
    private SharedPreferences prefs;

    private final float[] rotM = new float[9];
    private final float[] orient = new float[3];
    private float[] grav, geo;
    private LocationManager lm;
    private LocationListener pendingListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = getSharedPreferences("cybercompass", MODE_PRIVATE);
        view = new CompassView(this);
        view.setCallbacks(this);
        view.trueMode = prefs.getBoolean("trueMode", false);
        view.declination = prefs.getFloat("declination", Float.NaN);
        view.lat = prefs.getFloat("lat", Float.NaN);
        view.lon = prefs.getFloat("lon", Float.NaN);
        setContentView(view);
        findMagneticPole();
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            fetchLocation();
        }

        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotationSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        useRotation = rotationSensor != null;

        if (magSensor == null) {
            view.error = "NO MAGNETOMETER FOUND ON THIS DEVICE";
        } else if (!useRotation && accelSensor == null) {
            view.error = "NO ACCELEROMETER FOUND ON THIS DEVICE";
        }
        String mag = magSensor != null ? magSensor.getName() : "?";
        view.source = useRotation ? "FUSED ROTATION + " + mag
                : (accelSensor != null ? accelSensor.getName() : "?") + " + " + mag;
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (magSensor != null) {
            sm.registerListener(this, magSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        if (useRotation) {
            sm.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        } else if (accelSensor != null) {
            sm.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sm.unregisterListener(this);
        stopLocation();
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

    // ------------------------------------------------------------------ sensors

    private static float[] lowPass(float[] old, float[] in, float a) {
        if (old == null) return new float[]{in[0], in[1], in[2]};
        for (int i = 0; i < 3; i++) old[i] = old[i] + a * (in[i] - old[i]);
        return old;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        int type = e.sensor.getType();
        if (type == Sensor.TYPE_MAGNETIC_FIELD) {
            geo = lowPass(geo, e.values, 0.2f);
            float x = e.values[0], y = e.values[1], z = e.values[2];
            view.field = (float) Math.sqrt(x * x + y * y + z * z);
            if (!useRotation) computeFromAccelMag();
        } else if (type == Sensor.TYPE_ACCELEROMETER) {
            grav = lowPass(grav, e.values, 0.2f);
            computeFromAccelMag();
        } else if (type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotM, e.values);
            SensorManager.getOrientation(rotM, orient);
            publish();
        }
    }

    private void computeFromAccelMag() {
        if (grav == null || geo == null) return;
        if (SensorManager.getRotationMatrix(rotM, null, grav, geo)) {
            SensorManager.getOrientation(rotM, orient);
            publish();
        }
    }

    private void publish() {
        float az = (float) Math.toDegrees(orient[0]);
        float pitch = (float) Math.toDegrees(orient[1]);
        float roll = (float) Math.toDegrees(orient[2]);
        view.update(az, pitch, roll);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) view.accuracy = accuracy;
    }

    // ------------------------------------------------- true north / declination

    @Override
    public void onTrueModeChanged(boolean on) {
        prefs.edit().putBoolean("trueMode", on).apply();
        if (on) ensureLocation();
    }

    @Override
    public void onGlobeModeChanged(boolean on) {
        if (on) ensureLocation();
    }

    private void ensureLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            fetchLocation();
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        }
    }

    /**
     * Finds the north magnetic (dip) pole with the phone's own World Magnetic Model:
     * the point where the field's inclination is closest to straight down.
     */
    private void findMagneticPole() {
        new Thread(() -> {
            long now = System.currentTimeMillis();
            float bestLat = 90, bestLon = 0, best = -999;
            for (int la = 70; la <= 89; la++) {
                for (int lo = -180; lo < 180; lo += 3) {
                    float inc = new GeomagneticField(la, lo, 0, now).getInclination();
                    if (inc > best) { best = inc; bestLat = la; bestLon = lo; }
                }
            }
            float cLat = bestLat, cLon = bestLon;
            for (float la = cLat - 1.5f; la <= Math.min(89.9f, cLat + 1.5f); la += 0.1f) {
                for (float lo = cLon - 8f; lo <= cLon + 8f; lo += 0.25f) {
                    float inc = new GeomagneticField(la, lo, 0, now).getInclination();
                    if (inc > best) { best = inc; bestLat = la; bestLon = lo; }
                }
            }
            if (bestLon > 180) bestLon -= 360;
            if (bestLon < -180) bestLon += 360;
            view.magPoleLon = bestLon;
            view.magPoleLat = bestLat;
        }, "mag-pole").start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_LOCATION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            fetchLocation();
        } else if (Float.isNaN(view.lat)) {
            view.showToast("LOCATION DENIED - NEEDED ONCE FOR TRUE N / GLOBE", 5000);
        }
    }

    private void fetchLocation() {
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return;
        Location best = null;
        String[] providers = {LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER,
                LocationManager.PASSIVE_PROVIDER};
        for (String p : providers) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException | IllegalArgumentException ignored) {
            }
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < 7L * 24 * 3600 * 1000) {
            applyLocation(best);
            return;
        }
        String provider = null;
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) provider = LocationManager.NETWORK_PROVIDER;
        else if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) provider = LocationManager.GPS_PROVIDER;
        if (provider == null) {
            if (best != null) applyLocation(best);
            else view.showToast("TURN ON LOCATION FOR TRUE N / GLOBE", 5000);
            return;
        }
        view.showToast("ACQUIRING LOCATION...", 4000);
        stopLocation();
        pendingListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                applyLocation(location);
                stopLocation();
            }
            @Override public void onStatusChanged(String p, int s, Bundle b) { }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
        };
        try {
            lm.requestLocationUpdates(provider, 0L, 0f, pendingListener, Looper.getMainLooper());
        } catch (SecurityException ignored) {
        }
    }

    private void applyLocation(Location l) {
        GeomagneticField gf = new GeomagneticField((float) l.getLatitude(), (float) l.getLongitude(),
                (float) l.getAltitude(), System.currentTimeMillis());
        float d = gf.getDeclination();
        view.declination = d;
        view.lat = (float) l.getLatitude();
        view.lon = (float) l.getLongitude();
        prefs.edit().putFloat("declination", d).putFloat("lat", view.lat).putFloat("lon", view.lon).apply();
        view.showToast(String.format(java.util.Locale.US, "DECLINATION %+.1f° LOCKED", d), 4000);
    }

    private void stopLocation() {
        if (lm != null && pendingListener != null) {
            try {
                lm.removeUpdates(pendingListener);
            } catch (Exception ignored) {
            }
        }
        pendingListener = null;
    }
}
