package com.ayush.armour;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen presenting: a PDF, one page at a time (◀ ▶, up/down, OK or the media keys move), or a video
 * on a loop (OK pauses, ◀ ▶ skip ten seconds). Back stops. The pages either side are rendered ahead so a
 * slide change is instant.
 */
public final class PresentActivity extends Activity {
    private static final String TAG = "Armour";
    private final Handler main = new Handler(Looper.getMainLooper());
    /** PdfRenderer allows one page open at a time, so every render goes through this one thread. */
    private final ExecutorService render = Executors.newSingleThreadExecutor();
    private final Map<Integer, Bitmap> pages = new HashMap<>();
    private FrameLayout root;
    private ImageView page;
    private TextView counter;
    private VideoView video;
    private PdfRenderer pdf;
    private ParcelFileDescriptor fd;
    private int index, count, generation;
    private final Runnable hideCounter = () -> counter.animate().alpha(0f).setDuration(400).start();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        counter = Ui.mono(this, "", 16, Ui.GOLD, 3);
        counter.setPadding(Ui.px(16), Ui.px(8), Ui.px(16), Ui.px(8));
        counter.setBackground(Ui.box(0xCC0A0404, Ui.LINE, 1));
        counter.setAlpha(0f);
        open(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        open(i);
    }

    private void open(Intent i) {
        String path = i == null ? null : i.getStringExtra("file");
        if (path == null) {
            finish();
            return;
        }
        close();
        root.removeAllViews();
        File f = new File(path);
        if (Present.isVideo(f.getName())) {
            video = new VideoView(this);
            root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            video.setOnPreparedListener(mp -> {
                mp.setLooping(true);
                video.start();
            });
            video.setOnErrorListener((mp, what, extra) -> {
                showCounter("CAN'T PLAY THIS VIDEO");
                return true;
            });
            video.setVideoPath(path);
        } else {
            page = new ImageView(this);
            page.setScaleType(ImageView.ScaleType.FIT_CENTER);
            root.addView(page, new FrameLayout.LayoutParams(-1, -1));
            int gen = ++generation;
            render.execute(() -> {
                try {
                    fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                    pdf = new PdfRenderer(fd);
                    int n = pdf.getPageCount();
                    main.post(() -> {
                        if (gen != generation) return;
                        count = n;
                        index = 0;
                        go(0);
                    });
                } catch (Exception e) {
                    Log.w(TAG, "present: can't open " + path, e);
                    main.post(() -> showCounter("CAN'T OPEN THIS PDF"));
                }
            });
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = lp.bottomMargin = Ui.px(28);
        root.addView(counter, lp);
    }

    /** Shows page {@code i} (rendering it if it isn't ready) and renders its neighbours ahead. */
    private void go(int i) {
        if (count == 0) return;
        index = Math.max(0, Math.min(count - 1, i));
        Bitmap ready;
        synchronized (pages) {
            ready = pages.get(index);
        }
        if (ready != null) page.setImageBitmap(ready);
        showCounter((index + 1) + " / " + count + (index == count - 1 ? " · END" : ""));
        int gen = generation, want = index;
        render.execute(() -> {
            for (int p : new int[]{want, want + 1, want - 1}) {
                if (p < 0 || p >= count || gen != generation || pdf == null) continue;
                synchronized (pages) {
                    if (pages.containsKey(p)) continue;
                }
                Bitmap done = renderPage(p);
                if (done == null) continue;
                main.post(() -> {
                    if (gen != generation) return;
                    synchronized (pages) {
                        pages.put(p, done);
                        // Keep only this page and its neighbours in memory.
                        pages.keySet().removeIf(k -> Math.abs(k - index) > 1);
                    }
                    if (p == index) page.setImageBitmap(done);
                });
            }
        });
    }

    /** One page at the screen's size, on a white sheet (PDF pages are often transparent). Render thread only. */
    private Bitmap renderPage(int p) {
        try (PdfRenderer.Page pg = pdf.openPage(p)) {
            DisplayMetrics m = getResources().getDisplayMetrics();
            float scale = Math.min(m.widthPixels / (float) pg.getWidth(), m.heightPixels / (float) pg.getHeight());
            Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(pg.getWidth() * scale)),
                    Math.max(1, Math.round(pg.getHeight() * scale)), Bitmap.Config.ARGB_8888);
            b.eraseColor(Color.WHITE);
            pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return b;
        } catch (Exception e) {
            Log.w(TAG, "present: page " + p + " failed", e);
            return null;
        }
    }

    private void showCounter(String text) {
        counter.setText(text);
        counter.animate().cancel();
        counter.setAlpha(1f);
        main.removeCallbacks(hideCounter);
        main.postDelayed(hideCounter, 1800);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (video != null) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (video.isPlaying()) video.pause();
                    else video.start();
                    showCounter(video.isPlaying() ? "PLAYING" : "PAUSED");
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    video.seekTo(Math.min(video.getDuration(), video.getCurrentPosition() + 10_000));
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MEDIA_REWIND:
                    video.seekTo(Math.max(0, video.getCurrentPosition() - 10_000));
                    return true;
                default:
                    return super.onKeyDown(code, e);
            }
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_PAGE_DOWN: case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: case KeyEvent.KEYCODE_CHANNEL_DOWN:
                go(index + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_CHANNEL_UP:
                go(index - 1);
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    private void close() {
        generation++;
        count = 0;
        synchronized (pages) {
            pages.clear();
        }
        if (video != null) video.stopPlayback();
        video = null;
        page = null;
        render.execute(() -> {
            try {
                if (pdf != null) pdf.close();
                if (fd != null) fd.close();
            } catch (Exception ignored) {
            }
            pdf = null;
            fd = null;
        });
    }

    @Override
    protected void onDestroy() {
        close();
        render.shutdown();
        super.onDestroy();
    }
}
