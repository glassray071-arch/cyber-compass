package nz.eloni.cybertoolkit;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;

/** Registry of all tools shown on the menu. */
final class Tools {
    private Tools() {}

    static final String[] NAMES = {
            "WI-FI RADAR", "COMPASS", "LEVEL / ANGLE", "METAL DETECT",
            "VIBRATION", "LIGHT METER", "MOTION ALARM", "SITE LOGGER",
            "SOUND METER", "SONAR", "SEISMOGRAPH", "BAROMETER",
            "GYRO TACHO", "TORCH/STROBE", "MEASURE WALK", "SENSORS"
    };

    static final String[] DESCS = {
            "networks + distance", "tilt-compensated", "bubble, slope, zero", "studs, screws, rebar",
            "Hz, RPM, severity", "lux min/max/avg", "tamper siren", "GPS + note + photo",
            "dB, pitch, 50Hz hum", "echo rangefinder", "quake detector", "altitude + floors",
            "RPM + turn count", "SOS, morse, strobe", "pace out distance", "every sensor, live"
    };

    static final String[] GLYPHS = {
            "((•))", "N▲", "─●─", "⊕",
            "∿∿∿", "☼", "⚠", "⌖",
            "dB", ")))", "/\\/\\", "hPa",
            "RPM", "✺", "↔", "{ }"
    };

    static final int[] COLORS = {
            Neon.GREEN, Neon.CYAN, Neon.YEL, Neon.MAG,
            Neon.ORANGE, Neon.YEL, Neon.RED, Neon.CYAN,
            Neon.MAG, Neon.GREEN, Neon.ORANGE, Neon.CYAN,
            Neon.GREEN, Neon.YEL, Neon.MAG, Neon.CYAN
    };

    static Tool make(int i, MainActivity a) {
        switch (i) {
            case 0: return new WifiRadarTool(a);
            case 1: return new CompassTool(a);
            case 2: return new LevelTool(a);
            case 3: return new MetalTool(a);
            case 4: return new VibrationTool(a);
            case 5: return new LightTool(a);
            case 6: return new AlarmTool(a);
            case 7: return new LoggerTool(a);
            case 8: return new SoundTool(a);
            case 9: return new SonarTool(a);
            case 10: return new SeismoTool(a);
            case 11: return new BaroTool(a);
            case 12: return new TachoTool(a);
            case 13: return new TorchTool(a);
            case 14: return new StepTool(a);
            case 15: return new ExplorerTool(a);
            default: return null;
        }
    }

    /** False when the phone lacks the hardware this tool needs. */
    static boolean available(int i, Context c) {
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        PackageManager pm = c.getPackageManager();
        switch (i) {
            case 0: return pm.hasSystemFeature(PackageManager.FEATURE_WIFI);
            case 1:
            case 3: return sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null;
            case 5: return sm.getDefaultSensor(Sensor.TYPE_LIGHT) != null;
            case 7: return pm.hasSystemFeature(PackageManager.FEATURE_LOCATION);
            case 8:
            case 9: return pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE);
            case 11: return sm.getDefaultSensor(Sensor.TYPE_PRESSURE) != null;
            case 12: return sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;
            case 13: return pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
            case 15: return true;
            default: return sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null;
        }
    }
}
