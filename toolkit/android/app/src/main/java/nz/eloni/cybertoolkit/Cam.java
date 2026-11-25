package nz.eloni.cybertoolkit;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.Collections;

/** Minimal Camera2 wrapper: one YUV ImageReader stream, torch, zoom and manual controls. */
final class Cam {

    interface FrameListener {
        void onFrame(Image img);
    }

    interface Setup {
        void apply(CaptureRequest.Builder b);
    }

    private final CameraManager cm;
    String id;
    CameraCharacteristics ch;
    int orientation = 90;
    boolean front;
    Size size;
    volatile String status = "opening camera…";
    volatile boolean ready;
    volatile long skewNs, expNs;
    volatile int iso;

    private HandlerThread th;
    private Handler h;
    private CameraDevice dev;
    private CameraCaptureSession sess;
    private ImageReader reader;
    private CaptureRequest.Builder req;
    private volatile FrameListener listener;
    private volatile boolean torch;
    private volatile float zoom = 1f;
    private volatile Setup extra;

    Cam(Context c) {
        cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
    }

    @SuppressLint("MissingPermission")
    boolean open(boolean wantFront, int w, int hgt, FrameListener l) {
        listener = l;
        front = wantFront;
        try {
            int want = wantFront ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
            for (String cid : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(cid);
                Integer f = c.get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == want) {
                    id = cid;
                    ch = c;
                    break;
                }
            }
            if (id == null) {
                status = "no " + (wantFront ? "front" : "rear") + " camera";
                return false;
            }
            Integer o = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            orientation = o == null ? 90 : o;
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            long target = (long) w * hgt;
            Size best = null;
            for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
                long a = (long) s.getWidth() * s.getHeight();
                if (best == null || Math.abs(a - target) < Math.abs((long) best.getWidth() * best.getHeight() - target)) {
                    best = s;
                }
            }
            size = best;
            th = new HandlerThread("cam");
            th.start();
            h = new Handler(th.getLooper());
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    FrameListener fl = listener;
                    if (img != null && fl != null) fl.onFrame(img);
                } catch (Exception ignored) {
                } finally {
                    if (img != null) img.close();
                }
            }, h);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    dev = d;
                    startSession();
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    d.close();
                    dev = null;
                    ready = false;
                    status = "camera in use by another app";
                }

                @Override
                public void onError(CameraDevice d, int e) {
                    d.close();
                    dev = null;
                    ready = false;
                    status = "camera error " + e;
                }
            }, h);
            return true;
        } catch (Exception e) {
            status = "camera: " + e.getMessage();
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private void startSession() {
        try {
            dev.createCaptureSession(Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            sess = s;
                            try {
                                req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                req.addTarget(reader.getSurface());
                                ready = true;
                                status = "";
                                update();
                            } catch (Exception e) {
                                status = "camera: " + e.getMessage();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            status = "camera setup failed";
                        }
                    }, h);
        } catch (Exception e) {
            status = "camera: " + e.getMessage();
        }
    }

    private final CameraCaptureSession.CaptureCallback cb = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
            Long sk = res.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
            if (sk != null) skewNs = sk;
            Long ex = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            if (ex != null) expNs = ex;
            Integer is = res.get(CaptureResult.SENSOR_SENSITIVITY);
            if (is != null) iso = is;
        }
    };

    void setup(Setup s) {
        extra = s;
        update();
    }

    void setTorch(boolean on) {
        torch = on;
        update();
    }

    void setZoom(float z) {
        zoom = z;
        update();
    }

    void update() {
        Handler hh = h;
        if (hh == null) return;
        hh.post(() -> {
            if (!ready || sess == null || req == null) return;
            try {
                req.set(CaptureRequest.FLASH_MODE, torch ? CaptureRequest.FLASH_MODE_TORCH
                        : CaptureRequest.FLASH_MODE_OFF);
                applyZoom();
                Setup s = extra;
                if (s != null) s.apply(req);
                sess.setRepeatingRequest(req.build(), cb, h);
            } catch (Exception e) {
                status = "camera: " + e.getMessage();
            }
        });
    }

    private void applyZoom() {
        if (Build.VERSION.SDK_INT >= 30) {
            Range<Float> r = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (r != null) {
                req.set(CaptureRequest.CONTROL_ZOOM_RATIO, Neon.clamp(zoom, r.getLower(), r.getUpper()));
                return;
            }
        }
        Rect a = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Float mx = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        if (a == null) return;
        float z = Neon.clamp(zoom, 1, mx == null ? 1 : mx);
        int cw = (int) (a.width() / z), chh = (int) (a.height() / z);
        req.set(CaptureRequest.SCALER_CROP_REGION,
                new Rect(a.centerX() - cw / 2, a.centerY() - chh / 2, a.centerX() + cw / 2, a.centerY() + chh / 2));
    }

    float maxZoom() {
        if (ch == null) return 1;
        if (Build.VERSION.SDK_INT >= 30) {
            Range<Float> r = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (r != null) return r.getUpper();
        }
        Float mx = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        return mx == null ? 1 : mx;
    }

    boolean manualSensor() {
        if (ch == null) return false;
        int[] caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (caps == null) return false;
        for (int c : caps) {
            if (c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) return true;
        }
        return false;
    }

    Range<Long> expRange() {
        return ch == null ? null : ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
    }

    Range<Integer> isoRange() {
        return ch == null ? null : ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
    }

    void close() {
        ready = false;
        listener = null;
        try {
            if (sess != null) sess.close();
        } catch (Exception ignored) {
        }
        try {
            if (dev != null) dev.close();
        } catch (Exception ignored) {
        }
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {
        }
        if (th != null) th.quitSafely();
        sess = null;
        dev = null;
        reader = null;
        th = null;
        h = null;
    }

    // ---------------------------------------------------------------- pixels

    /** YUV_420_888 to ARGB at 1/step scale into out (length >= (w/step)*(h/step)). */
    static void toArgb(Image img, int[] out, int step, boolean gray) {
        Image.Plane[] pl = img.getPlanes();
        ByteBuffer Y = pl[0].getBuffer(), U = pl[1].getBuffer(), V = pl[2].getBuffer();
        int yRow = pl[0].getRowStride(), yPix = pl[0].getPixelStride();
        int uvRow = pl[1].getRowStride(), uvPix = pl[1].getPixelStride();
        int W = img.getWidth() / step, H = img.getHeight() / step;
        int o = 0;
        for (int y = 0; y < H; y++) {
            int sy = y * step;
            int yBase = sy * yRow, uvBase = (sy >> 1) * uvRow;
            for (int x = 0; x < W; x++) {
                int sx = x * step;
                int yy = Y.get(yBase + sx * yPix) & 0xff;
                if (gray) {
                    out[o++] = 0xff000000 | (yy << 16) | (yy << 8) | yy;
                    continue;
                }
                int uvi = uvBase + (sx >> 1) * uvPix;
                int u = (U.get(uvi) & 0xff) - 128, v = (V.get(uvi) & 0xff) - 128;
                int r = yy + ((91881 * v) >> 16);
                int g = yy - ((22554 * u + 46802 * v) >> 16);
                int b = yy + ((116130 * u) >> 16);
                r = r < 0 ? 0 : (r > 255 ? 255 : r);
                g = g < 0 ? 0 : (g > 255 ? 255 : g);
                b = b < 0 ? 0 : (b > 255 ? 255 : b);
                out[o++] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    /** Mean luma over a centred box covering frac of each dimension. */
    static float meanY(Image img, float frac) {
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer Y = p.getBuffer();
        int rs = p.getRowStride(), ps = p.getPixelStride();
        int w = img.getWidth(), h = img.getHeight();
        int x0 = (int) (w * (1 - frac) / 2), x1 = w - x0, y0 = (int) (h * (1 - frac) / 2), y1 = h - y0;
        long sum = 0;
        int n = 0;
        for (int y = y0; y < y1; y += 4) {
            for (int x = x0; x < x1; x += 4) {
                sum += Y.get(y * rs + x * ps) & 0xff;
                n++;
            }
        }
        return n == 0 ? 0 : sum / (float) n;
    }
}
