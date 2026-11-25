package nz.eloni.cybertoolkit;

import android.Manifest;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Base for camera tools: permission, Camera2 lifecycle, live preview bitmap, snapshots. */
public abstract class CamTool extends Tool {

    protected Cam cam;
    protected final Object lock = new Object();
    protected Bitmap bmp;
    protected int[] px;
    protected int bw, bh;
    protected volatile boolean frozen;
    protected String camStatus = "";
    protected boolean front;
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix m = new Matrix();

    public CamTool(MainActivity a) {
        super(a);
    }

    // ---- hooks for subclasses
    int reqW() { return 640; }

    int reqH() { return 480; }

    int step() { return 1; }

    boolean gray() { return false; }

    boolean wantPreview() { return true; }

    /** Analysis on the camera thread (before preview conversion). */
    void onCamFrame(Image img) {}

    /** Edit preview pixels on the camera thread (inside the lock). */
    void postProcess(int[] px, int w, int h) {}

    /** Camera device opened - apply torch / zoom / manual controls here. */
    void onCamOpened() {}

    void onToolStart() {}

    void onToolStop() {}

    @Override
    final void onStart() {
        onToolStart();
        String[] perms = Build.VERSION.SDK_INT < 29
                ? new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}
                : new String[]{Manifest.permission.CAMERA};
        act.needPerms(perms, this::openCam, () -> camStatus = "CAMERA PERMISSION DENIED");
    }

    @Override
    final void onStop() {
        closeCam();
        onToolStop();
    }

    private void closeCam() {
        if (cam != null) cam.close();
        cam = null;
    }

    protected void reopen(boolean useFront) {
        front = useFront;
        closeCam();
        synchronized (lock) {
            bmp = null;
        }
        if (running && act.granted(Manifest.permission.CAMERA)) openCam();
    }

    private void openCam() {
        cam = new Cam(act);
        final Cam c = cam;
        boolean ok = c.open(front, reqW(), reqH(), img -> {
            onCamFrame(img);
            if (!wantPreview() || frozen) return;
            int s = step();
            int w = img.getWidth() / s, h = img.getHeight() / s;
            synchronized (lock) {
                if (bmp == null || bw != w || bh != h) {
                    bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    px = new int[w * h];
                    bw = w;
                    bh = h;
                }
                Cam.toArgb(img, px, s, gray());
                postProcess(px, w, h);
                bmp.setPixels(px, 0, w, 0, 0, w, h);
            }
        });
        if (!ok) camStatus = c.status;
        onCamOpened();
    }

    protected String status() {
        if (!camStatus.isEmpty()) return camStatus;
        return cam == null ? "starting camera…" : cam.status;
    }

    private void buildMatrix(RectF dst, boolean crop) {
        int rot = cam == null ? 90 : cam.orientation;
        boolean swap = rot % 180 != 0;
        float iw = swap ? bh : bw, ih = swap ? bw : bh;
        float sc = crop ? Math.max(dst.width() / iw, dst.height() / ih) : Math.min(dst.width() / iw, dst.height() / ih);
        m.reset();
        m.postTranslate(-bw / 2f, -bh / 2f);
        m.postRotate(rot);
        if (front) m.postScale(-1, 1);
        m.postScale(sc, sc);
        m.postTranslate(dst.centerX(), dst.centerY());
    }

    /** Draws the live (or frozen) preview into dst. Returns false if no frame yet. */
    protected boolean drawPreview(Canvas c, RectF dst, boolean crop) {
        synchronized (lock) {
            if (bmp == null) {
                text(c, status(), dst.centerX(), dst.centerY(), 13, Neon.YEL, Paint.Align.CENTER, false);
                return false;
            }
            buildMatrix(dst, crop);
            c.save();
            c.clipRect(dst);
            c.drawBitmap(bmp, m, bmpPaint);
            c.restore();
        }
        frame(c, dst, frozen ? Neon.MAG : Neon.CYAN);
        return true;
    }

    /** Saves the current frame (upright) to Pictures/CyberToolkit. */
    protected String saveSnapshot(String prefix) {
        Bitmap copy;
        synchronized (lock) {
            if (bmp == null) return null;
            Matrix r = new Matrix();
            r.postRotate(cam == null ? 90 : cam.orientation);
            if (front) r.postScale(-1, 1);
            copy = Bitmap.createBitmap(bmp, 0, 0, bw, bh, r, true);
        }
        String name = prefix + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CyberToolkit");
        try {
            Uri u = act.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) return null;
            try (OutputStream os = act.getContentResolver().openOutputStream(u)) {
                if (os == null) return null;
                copy.compress(Bitmap.CompressFormat.JPEG, 90, os);
            }
            return name;
        } catch (Exception e) {
            return null;
        }
    }
}
