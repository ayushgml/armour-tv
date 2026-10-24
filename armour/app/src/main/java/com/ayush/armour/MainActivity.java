package com.ayush.armour;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The Armour home screen. One activity, layers on top of each other: the home (F ring or F3 rail), and
 * over it All apps (F4), the assistant (F5), Inputs (F6) and the night screen (F7).
 */
public final class MainActivity extends Activity {
    private static final long MINUTE = 60_000L, NIGHT_AFTER = 5 * MINUTE, SLEEP_AFTER_EMPTY = 10 * MINUTE;
    private static MainActivity visible;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root, home, apps, assistant, inputs, pairBanner, photo, web, welcome;
    private android.webkit.WebView webView;
    private TextView flashLabel;
    private Views.Night night;
    private Views.Sky sky;
    private List<Apps.App> appList = new ArrayList<>();
    private Weather.Now weather;
    private int people = Presence.OFFLINE;
    private long lastKey = System.currentTimeMillis();
    private boolean appsAz;

    // ring (F)
    private int ringIndex;
    private final Tile[] ringSlots = new Tile[5];
    private TextView ringName, ringSub, assistPill;
    private final List<View> ringParts = new ArrayList<>();
    // music changer (M1), over the ring while Spotify has a song
    private MusicPanel music;
    private boolean musicHidden;
    // rail (F3)
    private ImageView heroImage;
    private TextView heroTitle, heroSub;
    // shared header bits
    private TextView clock, clockSuffix, statTemp, statAir, statRoom, railStats;
    // assistant (F5)
    private TextView aStatus, aYouSaid, aText;
    private LinearLayout aCards, aButtons;
    private List<String> aSentences = new ArrayList<>();
    private final View[] aBars = new View[5];

    private final Presence.Listener presence = new Presence.Listener() {
        @Override
        public void onPresence(int n) {
            boolean arrived = n > 0 && people <= 0;
            people = n;
            updateStats();
            // Someone sat down while the night screen was up: wake up. Only on arrival: with people
            // already on the sofa the night screen would otherwise close the moment it opened.
            if (arrived && night != null) hideNight();
        }

        @Override
        public void onLight(float level) {
            roomLight(level);
        }
    };

    /**
     * A dark room (lights off for a film) dims the home screen and pulls the sky towards night; a lit
     * room brings it back. The camera's picture brightness is the light meter.
     */
    private void roomLight(float level) {
        float dark = Math.max(0, Math.min(1, (0.22f - level) / 0.14f));
        Views.Sky.roomDark = dark;
        if (home != null) home.animate().alpha(1f - 0.3f * dark).setDuration(1500).start();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            maybeNight();
            handler.postDelayed(this, 15_000);
        }
    };
    private final Runnable barsAnim = new Runnable() {
        int phase;

        @Override
        public void run() {
            phase++;
            int[] h = {10, 24, 16, 28, 12};
            for (int i = 0; i < aBars.length; i++) {
                if (aBars[i] == null) continue;
                ViewGroup.LayoutParams lp = aBars[i].getLayoutParams();
                lp.height = Ui.px(h[(i + phase) % h.length]);
                aBars[i].setLayoutParams(lp);
            }
            handler.postDelayed(this, 140);
        }
    };

    private final Runnable musicTick = new Runnable() {
        @Override
        public void run() {
            updateMusic();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);
        weather = Weather.cached(this);
        RemoteService.start(this);
        Speaker.get(this);
        buildHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = this;
        appList = Apps.load(this);
        bindHome();
        updateClock();
        updateStats();
        handler.post(tick);
        musicHidden = false;
        handler.post(musicTick);
        lastKey = System.currentTimeMillis();
        Presence.get(this).addListener(presence);
        Presence.get(this).use();
        Net.IO.execute(() -> {
            Weather.Now w = Weather.refresh(this);
            runOnUiThread(() -> {
                weather = w;
                if (sky != null) sky.setSun(w.sunrise, w.sunset);
                updateStats();
            });
        });
        handleShow(getIntent());
        setIntent(new Intent());
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = null;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(musicTick);
        Presence.get(this).removeListener(presence);
        Presence.get(this).release();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    private void handleShow(Intent i) {
        String show = i == null ? null : i.getStringExtra("show");
        if ("brief".equals(show)) startBriefing();
        else if ("apps".equals(show)) showApps();
        else if ("photo".equals(show)) showPhoto();
        else if ("inputs".equals(show)) showInputs();
        else if ("web".equals(show)) showWeb(i.getStringExtra("url"));
        else if ("welcome".equals(show)) showWelcome(i.getStringExtra("line"));
    }

    // ---- keys ------------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        lastKey = System.currentTimeMillis();
        if (night != null) {
            if (e.getAction() == KeyEvent.ACTION_UP) hideNight();
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_MENU
                && apps == null && assistant == null && inputs == null) {
            toggleLayout();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (welcome != null) closeWelcome();
        else if (web != null && webView != null && webView.canGoBack()) webView.goBack();
        else if (web != null) closeWeb();
        else if (photo != null) closeLayer(photo, () -> {
            photo = null;
            photoFrame = null;
        });
        else if (assistant != null) closeAssistant();
        else if (inputs != null) closeLayer(inputs, () -> inputs = null);
        else if (apps != null) closeLayer(apps, () -> apps = null);
        // Home: Back goes nowhere.
    }

    private void closeLayer(View layer, Runnable clear) {
        root.removeView(layer);
        clear.run();
        focusHome();
    }

    // ---- home ------------------------------------------------------------------------------------

    private void buildHome() {
        if (home != null) root.removeView(home);
        if (music != null) music.stop();
        music = null;
        ringParts.clear();
        home = new FrameLayout(this);
        root.addView(home, 0, new FrameLayout.LayoutParams(-1, -1));
        if (Prefs.layout(this).equals("rail")) buildRail();
        else buildRing();
    }

    private void toggleLayout() {
        Prefs.setLayout(this, Prefs.layout(this).equals("rail") ? "ring" : "rail");
        buildHome();
        bindHome();
        updateClock();
        updateStats();
        focusHome();
    }

    private void focusHome() {
        if (music != null && music.getVisibility() == View.VISIBLE) music.focus();
        else if (ringSlots[2] != null && ringSlots[2].isAttachedToWindow()) ringSlots[2].requestFocus();
        else if (home.findFocus() == null) {
            View first = home.findViewWithTag("rail0");
            if (first != null) first.requestFocus();
        }
    }

    /** F: header, briefing pill, the curved ring of apps with the focused one raised in the middle. */
    private void buildRing() {
        railStats = null;
        heroImage = null;
        home.addView(sky(), new FrameLayout.LayoutParams(-1, -1));
        home.addView(Ui.mono(this, "WELCOME HOME, " + Prefs.NAME.toUpperCase(Locale.ROOT), 13, Ui.GOLD, 3), Ui.at(60, 40, -1, -1));
        LinearLayout time = new LinearLayout(this);
        time.setGravity(Gravity.BOTTOM);
        clock = Ui.text(this, "", 60, Ui.TEXT, 200, 0);
        clockSuffix = Ui.text(this, "", 20, Ui.MUTED, 300, 0);
        clockSuffix.setPadding(Ui.px(10), 0, 0, Ui.px(8));
        time.addView(clock);
        time.addView(clockSuffix);
        home.addView(time, Ui.at(60, 60, -1, -1));

        LinearLayout stats = new LinearLayout(this);
        statTemp = stat(stats, "OUTSIDE");
        statAir = stat(stats, "AIR");
        statRoom = stat(stats, "ROOM");
        home.addView(stats, Ui.atRight(60, 40));

        LinearLayout top = new LinearLayout(this);
        top.addView(Ui.flat(this, "INPUTS", 12, Ui.DIM));
        top.addView(Ui.flat(this, "SETTINGS", 12, Ui.DIM));
        top.addView(Ui.flat(this, "LAYOUT", 12, Ui.DIM));
        top.getChildAt(0).setOnClickListener(v -> showInputs());
        top.getChildAt(1).setOnClickListener(v -> openSettings());
        top.getChildAt(2).setOnClickListener(v -> toggleLayout());
        for (int i = 0; i < 3; i++) ((TextView) top.getChildAt(i)).setTypeface(Ui.mono());
        home.addView(top, Ui.atRight(50, 158));

        assistPill = Ui.text(this, "", 18, Ui.TEXT, 400, 0);
        assistPill.setFocusable(true);
        assistPill.setClickable(true);
        assistPill.setGravity(Gravity.CENTER);
        assistPill.setPadding(Ui.px(22), Ui.px(12), Ui.px(22), Ui.px(12));
        assistPill.setBackground(Ui.box(Ui.PANEL, Ui.LINE, 1));
        assistPill.setOnFocusChangeListener((v, f) -> v.setBackground(f ? Ui.box(Ui.PANEL2, Ui.GOLD, 2) : Ui.box(Ui.PANEL, Ui.LINE, 1)));
        // With the music changer put away, the pill offers it back instead of the briefing.
        assistPill.setOnClickListener(v -> {
            if (musicHidden && Spotify.current(this) != null) {
                musicHidden = false;
                updateMusic();
                focusHome();
            } else startBriefing();
        });
        FrameLayout.LayoutParams pill = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        pill.topMargin = Ui.px(214);
        home.addView(assistPill, pill);
        setPillText();

        ringName = Ui.text(this, "", 50, Ui.TEXT, 600, 8);
        ringName.setGravity(Gravity.CENTER);
        home.addView(ringName, Ui.at(0, 398, 1280, -1));
        ringSub = Ui.mono(this, "", 13, Ui.GOLD, 3);
        ringSub.setGravity(Gravity.CENTER);
        home.addView(ringSub, Ui.at(0, 458, 1280, -1));

        float[][] slots = {{166, 560, 150, 88}, {338, 530, 170, 100}, {530, 490, 220, 128}, {772, 530, 170, 100}, {964, 560, 150, 88}};
        // Outer tiles fade back so the raised middle one reads as selected.
        float[] alphas = {0.55f, 0.8f, 1f, 0.8f, 0.55f};
        for (int i = 0; i < 5; i++) {
            Tile t = new Tile(this);
            t.setAlpha(alphas[i]);
            t.setTag(alphas[i]);
            home.addView(t, Ui.at(slots[i][0], slots[i][1], slots[i][2], slots[i][3]));
            ringSlots[i] = t;
        }
        Tile center = ringSlots[2];
        center.setFocusable(true);
        center.setClickable(true);
        center.setOnFocusChangeListener((v, f) -> ((Tile) v).setSelectedStyle(f));
        center.setOnClickListener(v -> {
            if (!appList.isEmpty()) Apps.launch(this, appList.get(ringIndex).pkg);
        });
        center.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) rotate(-1);
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) rotate(1);
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN) showApps();
            else if (code == KeyEvent.KEYCODE_DPAD_UP) assistPill.requestFocus();
            else return false;
            return true;
        });
        assistPill.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) center.requestFocus();
            else if (code == KeyEvent.KEYCODE_DPAD_UP) top.getChildAt(0).requestFocus();
            else return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
            return true;
        });
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            top.getChildAt(i).setOnKeyListener((v, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (music.getVisibility() == View.VISIBLE) music.focus();
                    else assistPill.requestFocus();
                } else if (code == KeyEvent.KEYCODE_DPAD_UP) return true;
                else if (code == KeyEvent.KEYCODE_DPAD_LEFT) return idx == 0;
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return idx == 2;
                else return false;
                return true;
            });
        }

        TextView hint = Ui.mono(this, "◀ ▶ ROTATE THE RING · ▼ ALL APPS · MENU SWITCHES LAYOUT", 12, Ui.DIM, 3);
        hint.setGravity(Gravity.CENTER);
        home.addView(hint, Ui.at(0, 672, 1280, -1));
        ringParts.add(assistPill);
        ringParts.add(ringName);
        ringParts.add(ringSub);
        ringParts.add(hint);
        Collections.addAll(ringParts, ringSlots);

        music = new MusicPanel(this, top.getChildAt(0), () -> {
            musicHidden = true;
            updateMusic();
            focusHome();
        });
        music.setVisibility(View.GONE);
        home.addView(music, new FrameLayout.LayoutParams(-1, -1));
        updateMusic();
        center.post(this::focusHome);
    }

    /**
     * Shows the music changer in place of the ring while Spotify has a song (unless it was put away with
     * Down), and keeps its song and progress current. The ring comes back when Spotify's session ends.
     */
    private void updateMusic() {
        if (music == null) return;
        NowPlaying n = Spotify.current(this);
        boolean show = n != null && !musicHidden;
        boolean was = music.getVisibility() == View.VISIBLE;
        if (show) music.update(n);
        if (show == was) {
            if (!show && musicHidden && assistPill != null) setPillText();
            return;
        }
        boolean onTop = welcome == null && photo == null && web == null && assistant == null && inputs == null && apps == null;
        music.setVisibility(show ? View.VISIBLE : View.GONE);
        for (View v : ringParts) v.setVisibility(show ? View.GONE : View.VISIBLE);
        if (!show) {
            music.stop();
            bindRing();
            setPillText();
        } else {
            music.setAlpha(0f);
            music.animate().alpha(1f).setDuration(350).start();
        }
        if (onTop) focusHome();
    }

    private Views.Sky sky() {
        sky = new Views.Sky(this);
        if (weather != null) sky.setSun(weather.sunrise, weather.sunset);
        return sky;
    }

    private TextView stat(LinearLayout row, String label) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.END);
        col.setPadding(Ui.px(22), 0, 0, 0);
        col.addView(Ui.mono(this, label, 12, Ui.MUTED, 2));
        TextView v = Ui.text(this, "—", 30, Ui.TEXT, 300, 0);
        v.setPadding(0, Ui.px(4), 0, 0);
        col.addView(v);
        row.addView(col);
        return v;
    }

    private void setPillText() {
        SpannableStringBuilder s = new SpannableStringBuilder();
        String lead = Ui.theme.equals("cinema") ? "Movie night.  " : Ui.theme.equals("batman") ? "Gotham mode.  " : "Briefing ready.  ";
        s.append(lead, new ForegroundColorSpan(Ui.MUTED), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        NowPlaying n = musicHidden ? Spotify.current(this) : null;
        if (n != null) {
            s.clear();
            s.append("Now playing.  ", new ForegroundColorSpan(Ui.MUTED), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.append(n.title + ". Press OK to change the music.");
        } else if (!Ai.ready(this)) s.append("Add the OpenRouter key on the phone page.");
        else if (Ui.theme.equals("cinema")) s.append("Say what you'd like to watch, or pick an app.");
        else if (Ui.theme.equals("batman")) s.append("Say “back to normal” to leave the cave.");
        else s.append("Press OK, or hold the mic on your phone.");
        assistPill.setText(s);
    }

    private void rotate(int dir) {
        if (appList.isEmpty()) return;
        ringIndex = (ringIndex + dir + appList.size()) % appList.size();
        bindRing();
        for (int i = 0; i < 5; i++) {
            Tile t = ringSlots[i];
            float target = (float) t.getTag();
            t.setTranslationX(Ui.px(dir * 26));
            t.setAlpha(target * 0.5f);
            t.animate().translationX(0).alpha(target).setDuration(180).start();
        }
        ringName.setAlpha(0.3f);
        ringName.animate().alpha(1f).setDuration(220).start();
    }

    private void bindRing() {
        int n = appList.size();
        for (int i = 0; i < 5; i++) {
            ringSlots[i].bind(n == 0 ? null : appList.get(((ringIndex + i - 2) % n + n) % n));
            // With fewer than five apps, hide the slots that would repeat.
            ringSlots[i].setVisibility(n >= 5 || i == 2 || Math.abs(i - 2) <= (n - 1) / 2 ? View.VISIBLE : View.INVISIBLE);
        }
        if (n == 0) return;
        Apps.App a = appList.get(ringIndex);
        ringName.setText(a.title());
        ringSub.setText(Apps.lastOpenedLine(a));
    }

    /** F3: the side rail of apps and a large preview of the focused one. */
    private void buildRail() {
        ringSlots[2] = null;
        assistPill = null;
        statTemp = statAir = statRoom = null;
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setTag("rail");
        home.addView(sky(), new FrameLayout.LayoutParams(-1, -1));
        // The rail is see-through so the living sky shows behind it too.
        rail.setBackground(Ui.box(0xD9120505, 0, 0));
        rail.setPadding(0, Ui.px(36), 0, Ui.px(30));
        home.addView(rail, Ui.at(0, 0, 300, 720));
        home.addView(Ui.line(this, Ui.LINE), Ui.at(299, 0, 1, 720));
        clock = Ui.text(this, "", 48, Ui.TEXT, 200, 0);
        clock.setPadding(Ui.px(32), 0, 0, 0);
        rail.addView(clock);
        clockSuffix = null;
        railStats = Ui.mono(this, "", 12, Ui.MUTED, 2);
        railStats.setPadding(Ui.px(32), Ui.px(6), 0, Ui.px(22));
        rail.addView(railStats);

        FrameLayout right = new FrameLayout(this);
        home.addView(right, Ui.at(372, 0, 852, 720));
        TextView greet = Ui.mono(this, "GOOD " + partOfDay().toUpperCase(Locale.ROOT) + ", " + Prefs.NAME.toUpperCase(Locale.ROOT), 12, Ui.GOLD, 3);
        greet.setGravity(Gravity.END);
        right.addView(greet, Ui.at(0, 40, 852, -1));
        FrameLayout hero = new FrameLayout(this);
        hero.setBackground(Ui.box(Ui.PANEL2, Ui.GOLD, 1));
        heroImage = new ImageView(this);
        heroImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        heroImage.setPadding(Ui.px(40), Ui.px(30), Ui.px(40), Ui.px(30));
        hero.addView(heroImage, new FrameLayout.LayoutParams(-1, -1));
        right.addView(hero, Ui.at(0, 74, 852, 330));
        // Gold L-shaped corners on the top-left and bottom-right of the preview, as in the mockup.
        right.addView(Ui.line(this, Ui.GOLD), Ui.at(-1, 73, 28, 3));
        right.addView(Ui.line(this, Ui.GOLD), Ui.at(-1, 73, 3, 28));
        right.addView(Ui.line(this, Ui.GOLD), Ui.at(825, 402, 28, 3));
        right.addView(Ui.line(this, Ui.GOLD), Ui.at(850, 377, 3, 28));
        heroTitle = Ui.text(this, "", 44, Ui.TEXT, 600, 2);
        right.addView(heroTitle, Ui.at(0, 428, 852, -1));
        heroSub = Ui.mono(this, "", 14, Ui.MUTED, 2);
        right.addView(heroSub, Ui.at(0, 486, 852, -1));
        LinearLayout actions = new LinearLayout(this);
        TextView open = Ui.button(this, "OPEN", 18, 3);
        TextView ask = Ui.button(this, "ASK ASSISTANT", 17, 2);
        actions.addView(open, new LinearLayout.LayoutParams(-2, Ui.px(54)));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, Ui.px(54));
        gap.leftMargin = Ui.px(14);
        actions.addView(ask, gap);
        right.addView(actions, Ui.at(0, 528, -1, -1));
        open.setOnClickListener(v -> {
            Object tag = heroTitle.getTag();
            if (tag instanceof String) Apps.launch(this, (String) tag);
        });
        ask.setOnClickListener(v -> startBriefing());
        heroTitle.setTag(null);
    }

    /** Fills the rail with the dock apps, then All apps, Inputs, Settings and Layout at the bottom. */
    private void bindRail() {
        LinearLayout rail = home.findViewWithTag("rail");
        while (rail.getChildCount() > 2) rail.removeViewAt(2);
        List<Apps.App> dock = new ArrayList<>();
        for (Apps.App a : appList) if (a.docked) dock.add(a);
        if (dock.isEmpty()) dock = appList.subList(0, Math.min(4, appList.size()));
        int idx = 0;
        for (Apps.App a : dock) rail.addView(railItem(a.title(), 18, Ui.MUTED, a, "rail" + idx++), new LinearLayout.LayoutParams(-1, Ui.px(58)));
        rail.addView(railItem("ALL APPS", 16, Ui.DIM, null, "railAll"), new LinearLayout.LayoutParams(-1, Ui.px(54)));
        View spacer = new View(this);
        rail.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));
        rail.addView(railItem("INPUTS", 15, Ui.DIM, null, "railInputs"), new LinearLayout.LayoutParams(-1, Ui.px(46)));
        rail.addView(railItem("SETTINGS", 15, Ui.DIM, null, "railSettings"), new LinearLayout.LayoutParams(-1, Ui.px(46)));
        rail.addView(railItem("LAYOUT", 15, Ui.DIM, null, "railLayout"), new LinearLayout.LayoutParams(-1, Ui.px(46)));
        View first = rail.findViewWithTag("rail0");
        if (first != null) first.post(first::requestFocus);
    }

    private TextView railItem(String label, float size, int color, Apps.App app, String tag) {
        TextView t = Ui.text(this, label, size, color, 400, 3);
        t.setTag(tag);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(Ui.px(32), 0, Ui.px(16), 0);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnFocusChangeListener((v, f) -> {
            TextView tv = (TextView) v;
            tv.setBackground(f ? Ui.box(Ui.RED, Ui.GOLD, 2) : null);
            tv.setTextColor(f ? 0xFFFFFFFF : color);
            tv.setTypeface(Ui.exo(f ? 700 : 400));
            if (f && app != null) showHero(app);
        });
        t.setOnClickListener(v -> {
            if (app != null) Apps.launch(this, app.pkg);
            else if (tag.equals("railAll")) showApps();
            else if (tag.equals("railInputs")) showInputs();
            else if (tag.equals("railSettings")) openSettings();
            else if (tag.equals("railLayout")) toggleLayout();
        });
        t.setOnKeyListener((v, code, e) -> e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT);
        return t;
    }

    private void showHero(Apps.App a) {
        Drawable d = Apps.banner(this, a.pkg);
        heroImage.setImageDrawable(d);
        heroTitle.setText(a.label);
        heroTitle.setTag(a.pkg);
        heroSub.setText((a.docked ? "IN DOCK · " : "") + Apps.lastOpenedLine(a));
    }

    private void bindHome() {
        if (Prefs.layout(this).equals("rail")) bindRail();
        else {
            if (ringIndex >= appList.size()) ringIndex = 0;
            bindRing();
            setPillText();
        }
    }

    private void updateClock() {
        Date now = new Date();
        if (clock == null) return;
        clock.setText(new SimpleDateFormat("h:mm", Locale.ENGLISH).format(now));
        if (clockSuffix != null) {
            clockSuffix.setText(new SimpleDateFormat("a · EEE d MMM", Locale.ENGLISH).format(now).toUpperCase(Locale.ROOT));
        }
        updateStats();
    }

    private void updateStats() {
        String temp = weather != null && weather.ok() ? weather.temp + "°" : "—";
        String aqi = weather != null && weather.aqi >= 0 ? String.valueOf(weather.aqi) : "—";
        String room = people == Presence.OFFLINE ? "OFFLINE" : people == 0 ? "EMPTY" : String.valueOf(people);
        if (statTemp != null) {
            statTemp.setText(temp);
            statAir.setText(aqi);
            statRoom.setText(people == Presence.OFFLINE ? "CAMERA OFFLINE" : room);
            statRoom.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(people == Presence.OFFLINE ? 16 : 30));
            statRoom.setTypeface(people == Presence.OFFLINE ? Ui.mono() : Ui.exo(300));
            statRoom.setTextColor(people == Presence.OFFLINE ? Ui.DIM : Ui.TEXT);
            statRoom.setPadding(0, Ui.px(people == Presence.OFFLINE ? 12 : 4), 0, 0);
        }
        if (railStats != null) {
            String d = new SimpleDateFormat("EEE d MMM", Locale.ENGLISH).format(new Date()).toUpperCase(Locale.ROOT);
            railStats.setText(d + " · " + temp + " · AQI " + aqi + "\n"
                    + Presence.describe(people).toUpperCase(Locale.ROOT));
            railStats.setLineSpacing(Ui.px(6), 1f);
        }
    }

    private static String partOfDay() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h < 5 ? "night" : h < 12 ? "morning" : h < 17 ? "afternoon" : h < 22 ? "evening" : "night";
    }

    private void openSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    // ---- F4 all apps -----------------------------------------------------------------------------

    private void showApps() {
        if (apps != null) root.removeView(apps);
        apps = new FrameLayout(this);
        apps.setBackgroundColor(Ui.BG);
        apps.setClickable(true);
        root.addView(apps, new FrameLayout.LayoutParams(-1, -1));
        apps.addView(Ui.text(this, "ALL APPS", 34, Ui.TEXT, 600, 6), Ui.at(60, 40, -1, -1));
        apps.addView(Ui.mono(this, appList.size() + " INSTALLED · " + (appsAz ? "A–Z" : "SORTED BY MOST USED")
                + "   ·   PHONE REMOTE  https://" + ipAddress() + ":" + RemoteServer.HTTPS_PORT, 13, Ui.MUTED, 2), Ui.at(60, 88, -1, -1));
        LinearLayout sorts = new LinearLayout(this);
        TextView most = Ui.button(this, "MOST USED", 14, 2), az = Ui.button(this, "A–Z", 14, 2);
        sorts.addView(most, new LinearLayout.LayoutParams(-2, Ui.px(40)));
        LinearLayout.LayoutParams g = new LinearLayout.LayoutParams(-2, Ui.px(40));
        g.leftMargin = Ui.px(10);
        sorts.addView(az, g);
        apps.addView(sorts, Ui.atRight(60, 44));
        apps.addView(Ui.line(this, Ui.LINE), Ui.at(60, 118, 1160, 1));
        most.setOnClickListener(v -> {
            appsAz = false;
            showApps();
        });
        az.setOnClickListener(v -> {
            appsAz = true;
            showApps();
        });

        List<Apps.App> list = new ArrayList<>(appList);
        if (appsAz) Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, Ui.px(14), 0, Ui.px(40));
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        apps.addView(scroll, Ui.at(60, 132, 1160, 540));
        LinearLayout row = null;
        View firstTile = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % 5 == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, Ui.px(150));
                rp.bottomMargin = Ui.px(18);
                rows.addView(row, rp);
            }
            Apps.App a = list.get(i);
            View t = appTile(a, i < 5, most);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -1, 1f);
            if (i % 5 != 4) tp.rightMargin = Ui.px(18);
            row.addView(t, tp);
            if (i == 0) firstTile = t;
        }
        // Pad the last row so tiles keep their width.
        if (row != null) {
            for (int k = row.getChildCount(); k < 5; k++) {
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -1, 1f);
                if (k != 4) tp.rightMargin = Ui.px(18);
                row.addView(new View(this), tp);
            }
        }
        TextView hint = Ui.mono(this, "BACK RETURNS HOME · SAY “OPEN F1” ON YOUR PHONE", 12, Ui.DIM, 3);
        apps.addView(hint, Ui.at(60, 682, -1, -1));
        if (firstTile != null) {
            View focusTarget = ((ViewGroup) firstTile).getChildAt(0);
            focusTarget.post(focusTarget::requestFocus);
        }
    }

    /** The app's banner logo in a framed tile, with its name written underneath. */
    private View appTile(Apps.App a, boolean firstRow, View upTarget) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        Tile tile = new Tile(this);
        tile.bind(a);
        tile.setFocusable(true);
        tile.setClickable(true);
        col.addView(tile, new LinearLayout.LayoutParams(-1, Ui.px(118)));
        TextView caption = Ui.mono(this, a.title() + (a.docked ? "  · IN DOCK" : ""), 12, Ui.MUTED, 2);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, Ui.px(10), 0, 0);
        caption.setSingleLine(true);
        col.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        tile.setOnFocusChangeListener((v, f) -> {
            tile.setSelectedStyle(f);
            caption.setTextColor(f ? Ui.GOLD : Ui.MUTED);
            v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(140).start();
        });
        tile.setOnClickListener(v -> Apps.launch(this, a.pkg));
        if (firstRow) tile.setOnKeyListener((v, code, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_UP) {
                upTarget.requestFocus();
                return true;
            }
            return false;
        });
        return col;
    }

    // ---- F5 assistant ----------------------------------------------------------------------------

    private void openAssistant() {
        if (assistant != null) root.removeView(assistant);
        assistant = new FrameLayout(this);
        assistant.setBackgroundColor(0xFF050202);
        assistant.setClickable(true);
        root.addView(assistant, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout bars = new LinearLayout(this);
        bars.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < aBars.length; i++) {
            View bar = new View(this);
            bar.setBackgroundColor(Ui.GOLD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.px(4), Ui.px(12));
            lp.rightMargin = Ui.px(4);
            bars.addView(bar, lp);
            aBars[i] = bar;
        }
        assistant.addView(bars, Ui.at(60, 40, 60, 28));
        aStatus = Ui.mono(this, "", 13, Ui.GOLD, 3);
        assistant.addView(aStatus, Ui.at(110, 47, -1, -1));
        aYouSaid = Ui.mono(this, "", 13, Ui.MUTED, 2);
        aYouSaid.setGravity(Gravity.END);
        assistant.addView(aYouSaid, Ui.at(560, 47, 660, -1));
        aText = Ui.text(this, "", 38, Ui.TEXT, 300, 0);
        aText.setLineSpacing(0, 1.25f);
        aText.setIncludeFontPadding(true);
        assistant.addView(aText, Ui.at(60, 104, 1100, 300));
        aCards = new LinearLayout(this);
        assistant.addView(aCards, Ui.at(60, 420, 1160, 140));
        aButtons = new LinearLayout(this);
        assistant.addView(aButtons, Ui.at(60, 632, -1, 52));
        TextView backHint = Ui.mono(this, "BACK RETURNS TO HOME", 12, Ui.DIM, 2);
        assistant.addView(backHint, Ui.atRight(60, 652));
        Speaker.get(this).setListener(this::onSentence);
    }

    private void closeAssistant() {
        Speaker.get(this).stop();
        Speaker.get(this).setListener(null);
        handler.removeCallbacks(barsAnim);
        root.removeView(assistant);
        assistant = null;
        focusHome();
    }

    private void setStatus(String s, boolean animate) {
        aStatus.setText(s);
        handler.removeCallbacks(barsAnim);
        if (animate) handler.post(barsAnim);
    }

    private void addCard(String label, String big, String small, boolean highlight) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(Ui.px(20), Ui.px(18), Ui.px(20), Ui.px(18));
        c.setBackground(highlight ? Ui.box(Ui.PANEL2, Ui.GOLD, 2) : Ui.box(Ui.RAIL, Ui.LINE, 1));
        c.addView(Ui.mono(this, label, 12, highlight ? Ui.GOLD : Ui.MUTED, 2));
        TextView b = Ui.text(this, big, big.length() > 22 ? 17 : 26, Ui.TEXT, 300, 0);
        b.setPadding(0, Ui.px(10), 0, Ui.px(8));
        b.setLineSpacing(Ui.px(4), 1f);
        c.addView(b);
        c.addView(Ui.text(this, small, 15, Ui.MUTED, 400, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        if (aCards.getChildCount() < 2) lp.rightMargin = Ui.px(18);
        aCards.addView(c, lp);
    }

    private TextView addButton(String label, boolean primary, View.OnClickListener click) {
        TextView b = Ui.button(this, label, 16, 2);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -1);
        lp.rightMargin = Ui.px(12);
        aButtons.addView(b, lp);
        if (primary) b.post(b::requestFocus);
        return b;
    }

    /** F5 for "brief me": weather, room and headlines, written by the AI and read aloud. */
    private void startBriefing() {
        openAssistant();
        aYouSaid.setText("YOU SAID: “BRIEF ME”");
        setStatus("PREPARING YOUR BRIEFING…", true);
        aText.setText("");
        aText.setTextColor(Ui.SPOKEN);
        addButton("STOP", true, v -> {
            Speaker.get(this).stop();
            setStatus("STOPPED", false);
        });
        addButton("CLOSE", false, v -> closeAssistant());
        Weather.Now w0 = weather;
        String room = Presence.describe(people);
        Net.IO.execute(() -> {
            Weather.Now w = Weather.refresh(this);
            List<String> news = News.headlines();
            Ai.Reply r;
            String err = null;
            try {
                r = Ai.briefing(this, w, news, room);
            } catch (Exception e) {
                r = new Ai.Reply();
                err = e.getMessage();
                r.say = fallbackBriefing(w, news);
            }
            final Ai.Reply reply = r;
            final String error = err;
            runOnUiThread(() -> {
                if (assistant == null) return;
                weather = w == null ? w0 : w;
                aCards.removeAllViews();
                addCard("WEATHER", w.ok() ? w.temp + "° · " + w.sky : "Unavailable",
                        w.ok() ? w.tonight() + " · AQI " + w.aqi : "No connection", false);
                addCard("ROOM", Presence.describe(people), people == Presence.OFFLINE ? "Plug in the webcam to use presence" : "Seen by the webcam", false);
                StringBuilder hl = new StringBuilder();
                for (int i = 0; i < reply.headlines.length() && i < 2; i++) hl.append(i > 0 ? "\n" : "").append(reply.headlines.optString(i));
                if (hl.length() == 0 && !news.isEmpty()) hl.append(shortHeadline(news.get(0)));
                addCard("AI NEWS", hl.length() > 0 ? hl.toString() : "No AI news right now", "Last 24 hours · Google News", true);
                if (error != null) aYouSaid.setText("AI UNAVAILABLE · SHORT BRIEFING");
                speakOnPanel(reply.say);
            });
        });
    }

    private static String shortHeadline(String h) {
        int dash = h.lastIndexOf(" - ");
        return dash > 0 ? h.substring(0, dash) : h;
    }

    private String fallbackBriefing(Weather.Now w, List<String> news) {
        StringBuilder s = new StringBuilder("Good " + partOfDay() + ", " + Prefs.NAME + ". ");
        if (w.ok()) s.append("It is ").append(w.temp).append(" degrees and ").append(w.sky).append(". ");
        if (!news.isEmpty()) s.append("Top story: ").append(shortHeadline(news.get(0))).append('.');
        return s.toString();
    }

    private void speakOnPanel(String say) {
        aSentences = Speaker.sentences(say);
        // Shrink long answers so the whole text fits above the cards.
        float size = say.length() < 200 ? 38 : say.length() < 320 ? 32 : say.length() < 450 ? 27 : 23;
        aText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(size));
        renderSentences(-1);
        setStatus("SPEAKING · PRESS OK TO STOP", true);
        Speaker.get(this).speakSentences(aSentences);
    }

    private void onSentence(int index) {
        if (assistant == null) return;
        if (index < 0) {
            setStatus("DONE", false);
            renderSentences(Integer.MAX_VALUE);
        } else {
            if (!aStatus.getText().toString().startsWith("SPEAKING")) setStatus("SPEAKING · PRESS OK TO STOP", true);
            renderSentences(index);
        }
    }

    /** The sentence being read is white; the rest stays muted. */
    private void renderSentences(int current) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        for (int i = 0; i < aSentences.size(); i++) {
            int start = s.length();
            s.append(aSentences.get(i)).append(' ');
            int color = current == Integer.MAX_VALUE ? Ui.TEXT : i == current ? 0xFFFFFFFF : Ui.SPOKEN;
            s.setSpan(new ForegroundColorSpan(color), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        aText.setText(s);
    }

    /** From the phone: show the question and answer on the panel when the home screen is in front. */
    static boolean showAnswer(String question, String say, boolean leftHome) {
        MainActivity a = visible;
        if (a == null || leftHome) return false;
        a.runOnUiThread(() -> {
            a.openAssistant();
            a.aYouSaid.setText("YOU SAID: “" + question.toUpperCase(Locale.ROOT) + "”");
            a.addButton("STOP", true, v -> {
                Speaker.get(a).stop();
                a.setStatus("STOPPED", false);
            });
            a.addButton("CLOSE", false, v -> a.closeAssistant());
            a.speakOnPanel(say.isEmpty() ? "Done." : say);
        });
        return true;
    }

    // ---- F6 inputs -------------------------------------------------------------------------------

    private void showInputs() {
        if (inputs != null) root.removeView(inputs);
        inputs = new FrameLayout(this);
        inputs.setBackgroundColor(Ui.BG);
        inputs.setClickable(true);
        root.addView(inputs, new FrameLayout.LayoutParams(-1, -1));
        Views.Wires wires = new Views.Wires(this);
        inputs.addView(wires, new FrameLayout.LayoutParams(-1, -1));
        inputs.addView(Ui.text(this, "INPUTS", 34, Ui.TEXT, 600, 6), Ui.at(60, 40, -1, -1));
        inputs.addView(Ui.mono(this, "ROUTE A SOURCE TO THE SCREEN", 13, Ui.MUTED, 2), Ui.at(60, 88, -1, -1));

        LinearLayout core = new LinearLayout(this);
        core.setOrientation(LinearLayout.VERTICAL);
        core.setGravity(Gravity.CENTER);
        core.setBackground(Ui.box(Ui.PANEL2, Ui.GOLD, 2));
        core.addView(Ui.mono(this, "SCREEN", 12, Ui.GOLD, 3));
        TextView coreLabel = Ui.text(this, "HOME", 26, Ui.TEXT, 600, 3);
        coreLabel.setPadding(0, Ui.px(8), 0, Ui.px(8));
        core.addView(coreLabel);
        TextView coreSub = Ui.mono(this, "PICK A SOURCE", 12, Ui.MUTED, 2);
        core.addView(coreSub);
        inputs.addView(core, Ui.at(100, 280, 280, 160));

        List<String[]> rows = new ArrayList<>();   // label, status
        List<Runnable> actions = new ArrayList<>();
        for (TvInputs.Source s : TvInputs.list(this)) {
            String st = s.state == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED ? "● SIGNAL"
                    : s.state == android.media.tv.TvInputManager.INPUT_STATE_DISCONNECTED ? "NO SIGNAL" : "";
            rows.add(new String[]{s.label, st});
            actions.add(() -> startSafe(TvInputs.open(s.id)));
        }
        Intent live = TvInputs.liveTv(this);
        if (live != null) {
            rows.add(new String[]{"LIVE TV", "ANTENNA / CABLE"});
            actions.add(() -> startSafe(live));
        }
        if (getPackageManager().getLeanbackLaunchIntentForPackage("com.ionitech.airscreen") != null) {
            rows.add(new String[]{"AIRPLAY / SCREEN MIRROR", "AIRSCREEN"});
            actions.add(() -> Apps.launch(this, "com.ionitech.airscreen"));
        }
        int n = Math.min(rows.size(), 7);
        float step = 76, h = 60, top = 360 - (n * step - (step - h)) / 2f;
        float[] centers = new float[n];
        TextView firstRow = null;
        for (int i = 0; i < n; i++) {
            final int idx = i;
            float y = top + i * step;
            centers[i] = y + h / 2f;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.px(24), 0, Ui.px(24), 0);
            row.setFocusable(true);
            row.setClickable(true);
            row.setBackground(Ui.box(Ui.PANEL, Ui.LINE, 1));
            TextView label = Ui.text(this, rows.get(i)[0], 19, Ui.MUTED, 400, 2);
            TextView status = Ui.mono(this, rows.get(i)[1], 12, Ui.MUTED, 1);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(status);
            row.setOnFocusChangeListener((v, f) -> {
                v.setBackground(f ? Ui.box(Ui.RED, Ui.GOLD, 2) : Ui.box(Ui.PANEL, Ui.LINE, 1));
                label.setTextColor(f ? 0xFFFFFFFF : Ui.MUTED);
                label.setTypeface(Ui.exo(f ? 700 : 400));
                status.setTextColor(f ? 0xFFFFFFFF : Ui.MUTED);
                if (f) {
                    wires.active = idx;
                    wires.invalidate();
                    coreLabel.setText(rows.get(idx)[0].length() > 12 ? rows.get(idx)[0].substring(0, 12) : rows.get(idx)[0]);
                    coreSub.setText("OK ROUTES IT");
                }
            });
            row.setOnClickListener(v -> {
                root.removeView(inputs);
                inputs = null;
                actions.get(idx).run();
            });
            row.setOnKeyListener((v, code, e) -> e.getAction() == KeyEvent.ACTION_DOWN
                    && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT));
            inputs.addView(row, Ui.at(560, y, 620, h));
            if (i == 0) firstRow = label;
        }
        wires.rowCenters = centers;
        if (n == 0) {
            coreSub.setText("NO SOURCES FOUND");
        } else {
            View first = (View) firstRow.getParent();
            first.post(first::requestFocus);
        }
        inputs.addView(Ui.mono(this, "▲ ▼ PICK · OK ROUTES · OR SAY “SWITCH TO HDMI 1”", 12, Ui.DIM, 3), Ui.at(60, 672, -1, -1));
    }

    private void startSafe(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            Commander.toast(this, "That source is not available");
        }
    }

    // ---- F7 night --------------------------------------------------------------------------------

    private void maybeNight() {
        if (night != null) {
            night.update(weather, people, Presence.get(this).emptyForMs());
            // Camera says the room has been empty for ten minutes: put the TV to sleep.
            if (people == 0 && Presence.get(this).emptyForMs() > SLEEP_AFTER_EMPTY) {
                Net.IO.execute(() -> Commander.pressKey(this, KeyEvent.KEYCODE_SLEEP));
            }
            return;
        }
        if (assistant != null || System.currentTimeMillis() - lastKey < NIGHT_AFTER) return;
        night = new Views.Night(this);
        root.addView(night, new FrameLayout.LayoutParams(-1, -1));
        night.setAlpha(0f);
        night.animate().alpha(1f).setDuration(1200).start();
        night.post(() -> night.update(weather, people, Presence.get(this).emptyForMs()));
    }

    private void hideNight() {
        if (night == null) return;
        root.removeView(night);
        night = null;
        lastKey = System.currentTimeMillis();
    }

    // ---- phone gestures: confirmation flash, thrown photos and pages --------------------------------

    static boolean isInFront() {
        return visible != null;
    }

    /** A short gold confirmation at the top of the screen; a toast when another app is in front. */
    static void flash(Context c, String text) {
        MainActivity a = visible;
        if (a == null) {
            Commander.toast(c, text);
            return;
        }
        a.runOnUiThread(() -> {
            if (a.flashLabel == null) {
                a.flashLabel = Ui.mono(a, "", 18, Ui.GOLD, 6);
                a.flashLabel.setGravity(Gravity.CENTER);
                a.flashLabel.setPadding(Ui.px(28), Ui.px(12), Ui.px(28), Ui.px(12));
                a.flashLabel.setBackground(Ui.box(0xE6120505, Ui.GOLD, 1));
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
                lp.topMargin = Ui.px(24);
                a.root.addView(a.flashLabel, lp);
            }
            a.flashLabel.bringToFront();
            a.flashLabel.setText(text);
            a.flashLabel.animate().cancel();
            a.flashLabel.setAlpha(1f);
            a.flashLabel.setTranslationY(-Ui.px(8));
            a.flashLabel.animate().translationY(0).setDuration(160).withEndAction(() ->
                    a.flashLabel.animate().alpha(0f).setStartDelay(700).setDuration(300).start()).start();
        });
    }

    // ---- themes (scenes) ---------------------------------------------------------------------------

    /**
     * Switches the whole look ("armour", "batman", "cinema"): the palette changes and the home screen is
     * rebuilt with it. Remembered, so it survives a restart until the next scene changes it.
     */
    static void applyLook(Context c, String theme) {
        Prefs.get(c).edit().putString("theme", theme).apply();
        Ui.setTheme(theme);
        MainActivity a = visible;
        if (a == null) return;
        a.runOnUiThread(() -> {
            a.root.setBackgroundColor(Ui.BG);
            a.buildHome();
            a.bindHome();
            a.updateClock();
            a.updateStats();
            a.focusHome();
        });
    }

    // ---- welcome dashboard ("Daddy's home") -----------------------------------------------------------

    private TextView wPlaying, wArtist, wLights, wRoom;
    private ImageView wArt;
    private final Runnable welcomeTick = new Runnable() {
        @Override
        public void run() {
            if (welcome == null) return;
            NowPlaying n = NowPlaying.get(MainActivity.this);
            wPlaying.setText(n == null ? "Nothing yet" : n.title);
            wArtist.setText(n == null ? "Starting the music…" : n.artist + (n.playing ? "" : " · paused"));
            if (n != null && n.art != null) wArt.setImageBitmap(n.art);
            wRoom.setText(Presence.describe(people));
            handler.postDelayed(this, 2000);
        }
    };
    private final Runnable welcomeTimeout = this::closeWelcome;

    /** The "welcome home" board: greeting, weather, room, lights, the song that just started and AI news. */
    private void showWelcome(String line) {
        if (welcome != null) root.removeView(welcome);
        welcome = new FrameLayout(this);
        welcome.setBackgroundColor((Ui.BG & 0x00FFFFFF) | 0xF4000000);
        welcome.setClickable(true);
        welcome.setFocusable(true);
        root.addView(welcome, new FrameLayout.LayoutParams(-1, -1));
        welcome.requestFocus();

        String when = new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date());
        welcome.addView(Ui.mono(this, "ALL SYSTEMS ONLINE · " + when, 13, Ui.GOLD, 4), Ui.at(60, 44, -1, -1));
        TextView hello = Ui.text(this, "Welcome home, " + Prefs.NAME + ".", 64, Ui.TEXT, 200, 0);
        welcome.addView(hello, Ui.at(60, 70, -1, -1));
        TextView sub = Ui.text(this, line == null ? "Good " + partOfDay() + ". Everything is ready." : line, 21, Ui.MUTED, 300, 0);
        welcome.addView(sub, Ui.at(62, 152, 1100, -1));

        Weather.Now w = weather;
        String sunset = w == null ? "" : String.format(Locale.ENGLISH, "%d:%02d PM", (w.sunset / 60) % 12 == 0 ? 12 : (w.sunset / 60) % 12, w.sunset % 60);
        List<View> cards = new ArrayList<>();
        cards.add(welcomeCard(60, 214, 340, 196, "OUTSIDE · " + Prefs.CITY.toUpperCase(Locale.ROOT),
                w != null && w.ok() ? w.temp + "°  " + w.sky : "—",
                w != null && w.aqi >= 0 ? "Air " + w.aqi + ", " + w.aqiWord() + " · sunset " + sunset : "", null));
        TextView[] room = new TextView[1];
        cards.add(welcomeCard(420, 214, 340, 196, "IN THE ROOM", Presence.describe(people), "Camera watching the door", room));
        wRoom = room[0];
        TextView[] lights = new TextView[1];
        cards.add(welcomeCard(780, 214, 440, 196, "LIGHTS", Lights.bulbs(this).isEmpty() ? "Looking for bulbs…" : "Checking…",
                "WiZ, on this Wi-Fi", lights));
        wLights = lights[0];

        // Now playing: cover on the left, song and artist beside it.
        FrameLayout np = new FrameLayout(this);
        np.setBackground(Ui.box(Ui.PANEL2, Ui.GOLD, 2));
        welcome.addView(np, Ui.at(60, 430, 520, 210));
        np.addView(Ui.mono(this, "NOW PLAYING", 12, Ui.GOLD, 3), Ui.at(196, 22, -1, -1));
        wArt = new ImageView(this);
        wArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wArt.setBackgroundColor(Ui.RED);
        np.addView(wArt, Ui.at(18, 18, 160, 172));
        wPlaying = Ui.text(this, "…", 30, Ui.TEXT, 600, 0);
        wPlaying.setMaxLines(2);
        np.addView(wPlaying, Ui.at(196, 50, 300, -1));
        wArtist = Ui.text(this, "", 17, Ui.MUTED, 300, 0);
        wArtist.setMaxLines(2);
        np.addView(wArtist, Ui.at(196, 132, 300, -1));
        cards.add(np);

        TextView[] news = new TextView[1];
        cards.add(welcomeCard(600, 430, 620, 210, "WHILE YOU WERE OUT · AI", "Fetching the latest…", "", news));
        TextView newsText = news[0];
        newsText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(17));
        newsText.setLineSpacing(Ui.px(8), 1f);
        welcome.addView(Ui.mono(this, "BACK TO CLOSE", 12, Ui.DIM, 2), Ui.atRight(60, 664));

        // Arrive one after another, like systems coming online.
        hello.setAlpha(0f);
        hello.setTranslationX(-Ui.px(30));
        hello.animate().alpha(1f).translationX(0).setDuration(500).start();
        for (int i = 0; i < cards.size(); i++) {
            View v = cards.get(i);
            v.setAlpha(0f);
            v.setTranslationY(Ui.px(24));
            v.animate().alpha(1f).translationY(0).setStartDelay(250 + 110L * i).setDuration(380).start();
        }

        handler.removeCallbacks(welcomeTick);
        handler.post(welcomeTick);
        handler.removeCallbacks(welcomeTimeout);
        handler.postDelayed(welcomeTimeout, 90_000);
        Net.IO.execute(() -> {
            Agent.sleep(2500);   // let a scene's light flash finish before reading the bulbs
            String l = Lights.status(this);
            List<String> h = News.headlines();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < Math.min(3, h.size()); i++) b.append(i > 0 ? "\n" : "").append("▸ ").append(shortHeadline(h.get(i)));
            runOnUiThread(() -> {
                if (welcome == null) return;
                wLights.setText(l == null ? "No bulbs found" : l);
                newsText.setText(b.length() == 0 ? "No AI news in the last day." : b.toString());
            });
        });
    }

    /** A dashboard card at design position (x, y); {@code big} returns its main line for updates. */
    private View welcomeCard(float x, float y, float w, float h, String label, String main, String small, TextView[] big) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(Ui.px(22), Ui.px(20), Ui.px(22), Ui.px(18));
        c.setBackground(Ui.box(Ui.RAIL, Ui.LINE2, 1));
        c.addView(Ui.mono(this, label, 12, Ui.GOLD, 3));
        TextView b = Ui.text(this, main, main.length() > 18 ? 26 : 38, Ui.TEXT, 300, 0);
        b.setPadding(0, Ui.px(14), 0, Ui.px(10));
        c.addView(b);
        if (!small.isEmpty()) c.addView(Ui.text(this, small, 15, Ui.MUTED, 400, 0));
        if (big != null) big[0] = b;
        welcome.addView(c, Ui.at(x, y, w, h));
        return c;
    }

    private void closeWelcome() {
        if (welcome == null) return;
        handler.removeCallbacks(welcomeTick);
        handler.removeCallbacks(welcomeTimeout);
        View v = welcome;
        welcome = null;
        v.animate().alpha(0f).setDuration(300).withEndAction(() -> root.removeView(v)).start();
        focusHome();
    }

    /** What is on screen, for the phone: "home", "photo", "apps", "assistant", "inputs", "web", or "app". */
    static String layer() {
        MainActivity a = visible;
        if (a == null) return "app";
        if (a.welcome != null) return "welcome";
        if (a.photo != null) return "photo";
        if (a.web != null) return "web";
        if (a.assistant != null) return "assistant";
        if (a.inputs != null) return "inputs";
        if (a.apps != null) return "apps";
        return "home";
    }

    private View photoFrame;
    private float photoZoom = 1f;

    /** The phone's tilt, -1..1 on each axis: pans a zoomed photo, otherwise moves the sky's layers. */
    static void tilt(float x, float y) {
        MainActivity a = visible;
        if (a == null) return;
        a.runOnUiThread(() -> {
            if (a.photoFrame != null) {
                // Pan up to the edges of the zoomed photo; at 1x a small drift that still feels alive.
                float rangeX = Ui.px(60) + a.photoFrame.getWidth() * (a.photoZoom - 1) / 2f;
                float rangeY = Ui.px(40) + a.photoFrame.getHeight() * (a.photoZoom - 1) / 2f;
                a.photoFrame.animate().translationX(-x * rangeX).translationY(-y * rangeY).setDuration(120).start();
            } else {
                Views.Sky.tilt(x, y);
            }
        });
    }

    /** Pinch on the phone while a photo is open: zoom in (+1) or out (-1). False when no photo is open. */
    static boolean zoomPhoto(int dir) {
        MainActivity a = visible;
        if (a == null || a.photoFrame == null) return false;
        if (dir < 0 && a.photoZoom <= 1f) return false;   // pinch in at 1x closes the photo instead
        a.runOnUiThread(() -> {
            a.photoZoom = Math.max(1f, Math.min(3f, a.photoZoom + dir * 0.5f));
            a.photoFrame.animate().scaleX(a.photoZoom).scaleY(a.photoZoom).setDuration(220).start();
            flash(a, "ZOOM " + (Math.round(a.photoZoom * 10) / 10f) + "×");
        });
        return true;
    }

    /** Pinch in: closes the top layer if there is one. */
    static boolean closeTop() {
        MainActivity a = visible;
        if (a == null) return false;
        boolean any = a.web != null || a.photo != null || a.assistant != null || a.inputs != null || a.apps != null;
        if (any) a.runOnUiThread(a::onBackPressed);
        return any;
    }

    /** A thrown photo, flying in from the top into a gold frame like a hologram. */
    private void showPhoto() {
        java.io.File f = Thrown.photoFile(this);
        if (!f.exists()) return;
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = Math.max(1, Math.max(o.outWidth / 1600, o.outHeight / 900));
        o.inJustDecodeBounds = false;
        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        if (bmp == null) return;
        if (photo != null) root.removeView(photo);
        photo = new FrameLayout(this);
        photo.setBackgroundColor(0xF2050202);
        photo.setClickable(true);
        photo.setFocusable(true);
        root.addView(photo, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(Ui.box(Ui.PANEL2, Ui.GOLD, 2));
        frame.setPadding(Ui.px(6), Ui.px(6), Ui.px(6), Ui.px(6));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setAdjustViewBounds(true);
        img.setImageBitmap(bmp);
        frame.addView(img, new FrameLayout.LayoutParams(-2, -2));
        // Fit inside 1120×560 design px, keeping the photo's shape.
        float scale = Math.min(1120f / bmp.getWidth(), 560f / bmp.getHeight());
        int w = Ui.px(bmp.getWidth() * scale), h = Ui.px(bmp.getHeight() * scale);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w + Ui.px(12), h + Ui.px(12), Gravity.CENTER);
        lp.topMargin = -Ui.px(20);
        photo.addView(frame, lp);
        TextView cap = Ui.mono(this, "FROM YOUR PHONE · BACK TO CLOSE", 12, Ui.MUTED, 3);
        cap.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        cp.bottomMargin = Ui.px(28);
        photo.addView(cap, cp);
        frame.setAlpha(0f);
        frame.setTranslationY(-Ui.px(420));
        frame.setScaleX(0.55f);
        frame.setScaleY(0.55f);
        frame.setRotationX(18f);
        photoFrame = frame;
        photoZoom = 1f;
        frame.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).rotationX(0f).setDuration(650)
                .setInterpolator(new android.view.animation.OvershootInterpolator(0.9f)).start();
        photo.requestFocus();
    }

    /** Any other thrown link: a full-screen web panel. Arrows scroll, OK follows links, Back goes back. */
    private void showWeb(String url) {
        if (url == null) return;
        closeWeb();
        web = new FrameLayout(this);
        web.setBackgroundColor(Ui.BG);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        TextView bar = Ui.mono(this, url.replaceFirst("^https?://", "").toUpperCase(Locale.ROOT), 12, Ui.GOLD, 2);
        bar.setSingleLine(true);
        bar.setEllipsize(android.text.TextUtils.TruncateAt.END);
        web.addView(bar, Ui.at(40, 14, 1200, -1));
        webView = new android.webkit.WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new android.webkit.WebViewClient());
        webView.setBackgroundColor(0xFFFFFFFF);
        web.addView(webView, Ui.at(0, 40, 1280, 680));
        webView.loadUrl(url);
        webView.requestFocus();
    }

    private void closeWeb() {
        if (web == null) return;
        if (webView != null) webView.destroy();
        webView = null;
        root.removeView(web);
        web = null;
        focusHome();
    }

    // ---- pairing ---------------------------------------------------------------------------------

    /** Shows (or with null hides) the four-digit code a phone needs to pair. */
    static void showPairCode(Context c, String code) {
        MainActivity a = visible;
        if (a == null) {
            if (code != null) Commander.toast(c, "Phone pairing code: " + code);
            return;
        }
        a.runOnUiThread(() -> {
            if (a.pairBanner != null) a.root.removeView(a.pairBanner);
            a.pairBanner = null;
            if (code == null) return;
            a.hideNight();
            FrameLayout b = new FrameLayout(a);
            b.setBackground(Ui.box(Ui.PANEL2, Ui.GOLD, 2));
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            TextView label = Ui.mono(a, "PHONE PAIRING CODE", 13, Ui.GOLD, 3);
            label.setGravity(Gravity.CENTER);
            col.addView(label);
            TextView digits = Ui.text(a, code, 64, 0xFFFFFFFF, 300, 18);
            digits.setGravity(Gravity.CENTER);
            digits.setPadding(Ui.px(18), Ui.px(8), 0, 0);
            col.addView(digits);
            b.addView(col, new FrameLayout.LayoutParams(-1, -1));
            a.root.addView(b, Ui.at(440, 250, 400, 180));
            a.pairBanner = b;
            a.handler.postDelayed(() -> {
                if (a.pairBanner == b) {
                    a.root.removeView(b);
                    a.pairBanner = null;
                }
            }, 120_000);
        });
    }

    static String ipAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress ad : Collections.list(ni.getInetAddresses())) {
                    if (ad instanceof Inet4Address) return ad.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "tv-ip";
    }
}
