package com.ayush.armour;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.FaceDetector;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The webcam, read on the TV only: no picture is stored or sent anywhere. From a 320-wide feed it
 * <ul>
 * <li>counts faces about every two seconds (who is in the room),</li>
 * <li>measures how bright the room is, from the same look.</li>
 * </ul>
 * With no camera plugged in it reports offline and starts by itself when one is connected. It runs only
 * while the home screen or the night screen is showing; other apps get the processor to themselves.
 */
final class Presence {
    interface Listener {
        /** Number of people seen, or {@link #OFFLINE}. Called on the main thread. */
        void onPresence(int people);

        /** Room brightness 0 (dark) .. 1 (bright), smoothed, every few seconds. */
        default void onLight(float level) {
        }
    }

    static final int OFFLINE = -1;
    private static Presence instance;

    private final Context ctx;
    private final CameraManager cm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private HandlerThread thread;
    private Handler bg;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private boolean running;
    /** An openCamera call is in flight: a second one would evict the first (seen as a connect/evict loop). */
    private boolean opening;
    private long lastFace, lastLightReport;
    private final int[] recent = new int[3];
    private int recentIdx;
    private volatile int people = OFFLINE;
    /** When someone was last seen; the night screen and walk-away pause use it. */
    private volatile long lastSeen = System.currentTimeMillis();
    private volatile float light = -1f;


    private final CameraManager.AvailabilityCallback availability = new CameraManager.AvailabilityCallback() {
        @Override
        public void onCameraAvailable(String id) {
            if (running && camera == null && !opening) open();
        }
    };

    static Presence get(Context c) {
        if (instance == null) instance = new Presence(c.getApplicationContext());
        return instance;
    }

    private Presence(Context c) {
        ctx = c;
        cm = c.getSystemService(CameraManager.class);
    }

    int people() {
        return people;
    }

    float light() {
        return light;
    }

    long emptyForMs() {
        return people > 0 ? 0 : System.currentTimeMillis() - lastSeen;
    }

    /** "2 people", "Room empty", "Camera off" or "Camera offline". */
    static String describe(int n) {
        if (n == OFFLINE) return "Camera offline";
        if (n == 0) return "Room empty";
        return n == 1 ? "1 person" : n + " people";
    }

    String describeNow() {
        if (!enabled(ctx)) return "Camera off";
        return running ? describe(people) : "Camera idle (another app is open)";
    }

    static boolean enabled(Context c) {
        return Prefs.get(c).getBoolean("camera_on", true);
    }

    /** The Camera switch on the phone page. */
    void setEnabled(boolean on) {
        Prefs.get(ctx).edit().putBoolean("camera_on", on).apply();
        if (on && users > 0) startCamera();
        else if (!on) stopCamera();
    }

    /** How many screens (home, night) want the camera right now. */
    private int users;

    /** A screen that shows the room starts using the camera; pair with {@link #release()}. */
    void use() {
        users++;
        startCamera();
    }

    void release() {
        users = Math.max(0, users - 1);
        if (users == 0) stopCamera();
    }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
        final int n = people;
        main.post(() -> l.onPresence(n));
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void startCamera() {
        if (running || !enabled(ctx)) return;
        running = true;
        thread = new HandlerThread("presence");
        thread.start();
        bg = new Handler(thread.getLooper());
        if (cm != null) cm.registerAvailabilityCallback(availability, bg);
        bg.post(this::open);
    }

    private void stopCamera() {
        if (!running) return;
        running = false;
        if (cm != null) cm.unregisterAvailabilityCallback(availability);
        Handler h = bg;
        HandlerThread t = thread;
        if (h != null) h.post(() -> {
            closeCamera();
            t.quitSafely();
        });
        report(OFFLINE);
    }

    private void open() {
        if (!running || camera != null || opening) return;
        try {
            if (cm == null || ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                report(OFFLINE);
                return;
            }
            String[] ids = cm.getCameraIdList();
            if (ids.length == 0) {
                report(OFFLINE);
                return;
            }
            String id = ids[0];
            StreamConfigurationMap map = cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = new Size(640, 480);
            if (map != null) {
                Size best = null;
                for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
                    if (s.getWidth() < 320) continue;
                    if (best == null || s.getWidth() * s.getHeight() < best.getWidth() * best.getHeight()) best = s;
                }
                if (best != null) size = best;
            }
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onImage, bg);
            opening = true;
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    opening = false;
                    if (!running) {
                        d.close();
                        return;
                    }
                    camera = d;
                    startSession();
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    opening = false;
                    d.close();
                    camera = null;
                    report(OFFLINE);
                }

                @Override
                public void onError(CameraDevice d, int error) {
                    opening = false;
                    d.close();
                    camera = null;
                    report(OFFLINE);
                }
            }, bg);
        } catch (Exception e) {
            opening = false;
            report(OFFLINE);
        }
    }

    @SuppressWarnings("deprecation")
    private void startSession() {
        try {
            camera.createCaptureSession(Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                b.addTarget(reader.getSurface());
                                // One look every two seconds needs very few frames: ask for the camera's
                                // slowest rate, so the TV is not converting 30 frames a second for nothing.
                                android.util.Range<Integer> slow = slowestFps();
                                if (slow != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, slow);
                                s.setRepeatingRequest(b.build(), null, bg);
                            } catch (Exception e) {
                                report(OFFLINE);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            report(OFFLINE);
                        }
                    }, bg);
        } catch (Exception e) {
            report(OFFLINE);
        }
    }

    private android.util.Range<Integer> slowestFps() {
        try {
            android.util.Range<Integer>[] ranges = cm.getCameraCharacteristics(camera.getId())
                    .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            android.util.Range<Integer> best = null;
            if (ranges != null) for (android.util.Range<Integer> r : ranges) {
                if (best == null || r.getUpper() < best.getUpper()) best = r;
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private void onImage(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            long now = System.currentTimeMillis();
            if (now - lastFace >= 2000) {
                lastFace = now;
                sampleLight(img, now);
                int faces = countFaces(img);
                recent[recentIdx++ % recent.length] = faces;
                // Smooth over the last three looks: one missed detection should not empty the room.
                int n = 0;
                for (int f : recent) n = Math.max(n, f);
                if (n > 0) lastSeen = now;
                report(n);
            }
        } catch (Exception ignored) {
        } finally {
            img.close();
        }
    }

    /** Room brightness from a coarse 40×30 sample of the picture, smoothed over the last minute or so. */
    private void sampleLight(Image img, long now) {
        Image.Plane y = img.getPlanes()[0];
        ByteBuffer buf = y.getBuffer();
        int w = img.getWidth(), h = img.getHeight(), stride = y.getRowStride();
        long sum = 0;
        for (int row = 0; row < 30; row++) {
            int base = (row * h / 30) * stride;
            for (int col = 0; col < 40; col++) sum += buf.get(base + col * w / 40) & 0xFF;
        }
        float mean = sum / 1200f / 255f;
        light = light < 0 ? mean : light * 0.8f + mean * 0.2f;
        if (now - lastLightReport > 5000) {
            lastLightReport = now;
            final float l = light;
            main.post(() -> {
                for (Listener li : listeners) li.onLight(l);
            });
        }
    }

    // ---- faces -----------------------------------------------------------------------------------

    /** Grey 320-wide copy of the luma plane, then Android's built-in face finder. */
    private static int countFaces(Image img) {
        Image.Plane y = img.getPlanes()[0];
        ByteBuffer buf = y.getBuffer();
        int srcW = img.getWidth(), srcH = img.getHeight(), stride = y.getRowStride();
        int step = Math.max(1, srcW / 320);
        int w = (srcW / step) & ~1, h = srcH / step;
        int[] px = new int[w * h];
        for (int row = 0; row < h; row++) {
            int base = row * step * stride;
            for (int col = 0; col < w; col++) {
                int g = buf.get(base + col * step) & 0xFF;
                px[row * w + col] = 0xFF000000 | (g << 16) | (g << 8) | g;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.RGB_565);
        FaceDetector.Face[] found = new FaceDetector.Face[6];
        int n = new FaceDetector(w, h, found.length).findFaces(bmp, found);
        bmp.recycle();
        return n;
    }

    private void closeCamera() {
        try {
            if (session != null) session.close();
            if (camera != null) camera.close();
            if (reader != null) reader.close();
        } catch (Exception ignored) {
        }
        session = null;
        camera = null;
        reader = null;
    }

    private void report(int n) {
        people = n;
        main.post(() -> {
            for (Listener l : listeners) l.onPresence(n);
        });
    }
}
