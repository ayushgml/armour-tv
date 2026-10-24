package com.ayush.armour;

import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;

/**
 * The F7 night screen as the TV's screensaver, for when an app other than the home screen was left open.
 * Pick it in Settings, or: settings put secure screensaver_components com.ayush.armour/.NightDream
 */
public final class NightDream extends DreamService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Views.Night night;
    private int people = Presence.OFFLINE;
    private final Presence.Listener listener = new Presence.Listener() {
        @Override
        public void onPresence(int n) {
            boolean arrived = n > 0 && people == 0;
            people = n;
            // Someone sat down in an empty room: end the screensaver.
            if (arrived) finish();
        }

    };
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            night.update(Weather.cached(NightDream.this), people, Presence.get(NightDream.this).emptyForMs());
            handler.postDelayed(this, 30_000);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Ui.init(this);
        setInteractive(false);
        setFullscreen(true);
        night = new Views.Night(this);
        setContentView(night);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        Presence.get(this).addListener(listener);
        Presence.get(this).use();
        handler.post(tick);
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(tick);
        Presence.get(this).removeListener(listener);
        Presence.get(this).release();
        super.onDreamingStopped();
    }
}
