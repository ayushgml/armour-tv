package com.ayush.armour;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/** Carries out the assistant's actions and the phone remote's buttons. */
final class Commander {
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Commander() {
    }

    /** Phone remote button names to Android key codes. */
    static int keyCode(String name) {
        switch (name) {
            case "up": return KeyEvent.KEYCODE_DPAD_UP;
            case "down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "ok": return KeyEvent.KEYCODE_DPAD_CENTER;
            case "back": return KeyEvent.KEYCODE_BACK;
            case "home": return KeyEvent.KEYCODE_HOME;
            case "menu": return KeyEvent.KEYCODE_MENU;
            case "playpause": return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            case "rewind": return KeyEvent.KEYCODE_MEDIA_REWIND;
            case "forward": return KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            case "volup": return KeyEvent.KEYCODE_VOLUME_UP;
            case "voldown": return KeyEvent.KEYCODE_VOLUME_DOWN;
            case "mute": return KeyEvent.KEYCODE_VOLUME_MUTE;
            case "power": return KeyEvent.KEYCODE_SLEEP;
            case "input": return KeyEvent.KEYCODE_TV_INPUT;
            default: return -1;
        }
    }

    /**
     * Presses a key in whatever app is in front. Without the injector (debugging switched off) the keys that
     * have a public API still work: media, volume and Home.
     */
    static boolean pressKey(Context c, int code) {
        if (Injector.key(c, code)) return true;
        AudioManager am = c.getSystemService(AudioManager.class);
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return true;
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                long t = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
                return true;
            case KeyEvent.KEYCODE_HOME:
                goHome(c, null);
                return true;
            default:
                return false;
        }
    }

    static void goHome(Context c, String extra) {
        goHome(c, extra, null);
    }

    /** Brings the home screen to the front, optionally opening a layer ("brief", "apps", "photo", "web"). */
    static void goHome(Context c, String extra, String url) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (extra != null) i.putExtra("show", extra);
        if (url != null) i.putExtra("url", url);
        c.startActivity(i);
    }

    /**
     * Runs the actions of one assistant reply; returns a short note of what was done for the phone. Actions
     * that take a while (play, spotify, do) run in the background, and the ones after them wait for them, so
     * "play Panchayat and turn on subtitles" happens in that order.
     */
    static String run(Context c, JSONArray actions, List<Apps.App> apps) {
        StringBuilder done = new StringBuilder();
        for (int i = 0; i < actions.length(); i++) {
            JSONObject a = actions.optJSONObject(i);
            if (a == null) continue;
            String type = a.optString("type");
            if (type.equals("play") || type.equals("spotify") || type.equals("youtube") || type.equals("do")) {
                JSONArray rest = new JSONArray();
                for (int j = i + 1; j < actions.length(); j++) rest.put(actions.opt(j));
                done.append(type.equals("play") ? "Finding " + a.optString("title")
                        : type.equals("spotify") ? "Finding " + a.optString("query") + " on Spotify"
                        : type.equals("youtube") ? "Finding " + a.optString("query") + " on YouTube"
                        : "Working on it").append(". ");
                Net.IO.execute(() -> {
                    String result = slow(c, a, apps);
                    Speaker.get(c).speak(result);
                    toast(c, result);
                    if (rest.length() > 0) run(c, rest, apps);
                });
                break;
            }
            if (type.equals("after")) {
                JSONArray later = a.optJSONArray("actions");
                long ms = Math.round(a.optDouble("minutes", 0) * 60_000);
                if (later != null && ms > 0) {
                    main.postAtTime(() -> run(c, later, Apps.load(c)), TIMER, SystemClock.uptimeMillis() + ms);
                    done.append("Timer set for ").append(minutes(ms)).append(". ");
                }
                continue;
            }
            if (type.equals("cancel_timers")) {
                main.removeCallbacksAndMessages(TIMER);
                done.append("Timers cancelled. ");
                continue;
            }
            try {
                switch (type) {
                    case "open_app": {
                        Apps.App app = Apps.find(apps, a.optString("app"));
                        if (app != null && Apps.launch(c, app.pkg)) done.append("Opened ").append(app.label).append(". ");
                        else done.append("Could not find ").append(a.optString("app")).append(". ");
                        break;
                    }
                    case "youtube_search":
                        YouTube.search(c, a.optString("query"));
                        done.append("Searching YouTube. ");
                        break;
                    case "input": {
                        Intent in = TvInputs.byName(c, a.optString("name"));
                        if (in != null) {
                            c.startActivity(in);
                            done.append("Switched to ").append(a.optString("name").toUpperCase(Locale.ROOT)).append(". ");
                        }
                        break;
                    }
                    case "volume": {
                        AudioManager am = c.getSystemService(AudioManager.class);
                        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                        int level = a.has("level") ? a.optInt("level") * max / 100
                                : cur + a.optInt("change") * max / 100;
                        level = Math.max(0, Math.min(max, level));
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_SHOW_UI);
                        done.append("Volume ").append(level * 100 / Math.max(1, max)).append(". ");
                        break;
                    }
                    case "mute":
                        pressKey(c, KeyEvent.KEYCODE_VOLUME_MUTE);
                        done.append("Mute. ");
                        break;
                    case "media":
                        media(c, a.optString("key"), a.optInt("seconds"));
                        done.append("Media ").append(a.optString("key").replace('_', ' ')).append(". ");
                        break;
                    case "key": {
                        int code = keyCode(a.optString("key"));
                        pressKey(c, code > 0 ? code : KeyEvent.KEYCODE_HOME);
                        done.append("Pressed ").append(a.optString("key")).append(". ");
                        break;
                    }
                    case "briefing":
                        goHome(c, "brief");
                        break;
                    case "show_apps":
                        goHome(c, "apps");
                        break;
                    case "lights":
                        done.append(Lights.apply(c, a.optString("set", "on"), a.optInt("brightness", 0))).append(' ');
                        break;
                    case "scene":
                        done.append(Scenes.run(c, a.optString("name"))).append(' ');
                        break;
                    case "sleep":
                        pressKey(c, KeyEvent.KEYCODE_SLEEP);
                        done.append("Screen off. ");
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                done.append("Could not ").append(type).append(". ");
            }
        }
        return done.toString().trim();
    }

    /** Token for the "after N minutes" timers, so "cancel the timer" can drop them all. */
    private static final Object TIMER = new Object();

    private static String minutes(long ms) {
        long m = Math.round(ms / 60_000.0);
        return m >= 60 && m % 60 == 0 ? (m / 60) + (m == 60 ? " hour" : " hours") : m + (m == 1 ? " minute" : " minutes");
    }

    /** Blocking: the actions that operate other apps. Returns the sentence to say when finished. */
    private static String slow(Context c, JSONObject a, List<Apps.App> apps) {
        Agent.Progress progress = step -> toast(c, step);
        switch (a.optString("type")) {
            case "play":
                return Player.play(c, a.optString("title"), progress);
            case "spotify":
                return Spotify.play(c, a.optString("query"));
            case "youtube":
                return YouTube.play(c, a.optString("query"), a.optBoolean("newest"));
            default: {
                String pkg = "";
                Apps.App app = a.optString("app").isEmpty() ? null : Apps.find(apps, a.optString("app"));
                if (app != null) {
                    if (!Apps.launch(c, app.pkg)) return "I couldn't open " + app.label + ".";
                    pkg = app.pkg;
                }
                // Let the app (or whatever the step before opened) settle before reading the screen.
                Agent.sleep(4000);
                String goal = a.optString("goal");
                boolean ok = Agent.run(c, goal, pkg, progress, false);
                return ok ? "Done." : "I couldn't manage that one on screen.";
            }
        }
    }

    /**
     * Media keys. When the home screen is in front and music plays behind it, the key would go to Armour, so
     * then the playing app's session is told directly.
     */
    static void media(Context c, String key, int seconds) {
        NowPlaying n = MainActivity.isInFront() ? NowPlaying.get(c) : null;
        if (n != null) {
            MediaController.TransportControls t = n.controller.getTransportControls();
            switch (key) {
                case "next": t.skipToNext(); return;
                case "previous": t.skipToPrevious(); return;
                case "pause": t.pause(); return;
                case "play": t.play(); return;
                case "stop": t.stop(); return;
                case "play_pause": if (n.playing) t.pause(); else t.play(); return;
                default: break;
            }
        }
        switch (key) {
            case "next": pressKey(c, KeyEvent.KEYCODE_MEDIA_NEXT); break;
            case "previous": pressKey(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
            case "pause": pressKey(c, KeyEvent.KEYCODE_MEDIA_PAUSE); break;
            case "play": pressKey(c, KeyEvent.KEYCODE_MEDIA_PLAY); break;
            case "stop": pressKey(c, KeyEvent.KEYCODE_MEDIA_STOP); break;
            case "forward":
            case "rewind": {
                // Most TV players skip about 10 seconds per press.
                int presses = Math.max(1, Math.min(12, (seconds > 0 ? seconds : 10) / 10));
                int code = key.equals("forward") ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
                for (int i = 0; i < presses; i++) pressKey(c, code);
                break;
            }
            default: pressKey(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
    }

    static void toast(Context c, String msg) {
        main.post(() -> Toast.makeText(c.getApplicationContext(), msg, Toast.LENGTH_LONG).show());
    }
}
