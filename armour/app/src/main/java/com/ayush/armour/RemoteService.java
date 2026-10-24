package com.ayush.armour;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps the phone remote and the assistant alive while Netflix or anything else is on screen. Started by
 * the home screen; a foreground service so Android does not stop it in the background.
 */
public final class RemoteService extends Service {
    private RemoteServer server;
    private String pairCode;
    private long pairCodeUntil, pairStartedAt, pairLockedUntil;
    /** Wrong guesses at the current code, and the times of all recent wrong guesses, whatever code they were for. */
    private int pairMisses;
    private final java.util.ArrayDeque<Long> pairMissTimes = new java.util.ArrayDeque<>();

    static void start(Context c) {
        c.startForegroundService(new Intent(c, RemoteService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Find the WiZ bulbs now, so the first "lights red" does not wait for a search.
        Net.IO.execute(() -> Lights.discover(this));
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("remote", "Phone remote", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "remote")
                .setContentTitle("Phone remote is on")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        server = new RemoteServer(this::handle, (path, headers) ->
                path.equals("/api/present") && Prefs.tokenValid(this, headers.get("x-token")) ? Present.incoming(this) : null);
        try (InputStream p12 = getResources().openRawResource(R.raw.tls)) {
            server.start(p12, "armourtls".toCharArray());
        } catch (Exception e) {
            Commander.toast(this, "Phone remote could not start: " + e.getMessage());
        }
        // Start the key injector early, so the TV's "Allow debugging?" prompt appears now, not mid-film.
        Net.IO.execute(() -> Injector.restart(this));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- HTTP routes -----------------------------------------------------------------------------

    private RemoteServer.Response handle(String method, String path, Map<String, String> headers, byte[] raw) {
        String body = new String(raw, StandardCharsets.UTF_8);
        if (method.equals("GET") && (path.equals("/") || path.startsWith("/?"))) return asset("phone.html", "text/html");
        if (method.equals("GET") && path.equals("/manifest.json")) return asset("manifest.json", "application/manifest+json");
        try {
            JSONObject in = body.isEmpty() ? new JSONObject() : new JSONObject(body);
            switch (path) {
                case "/api/pair/start":
                    return pairStart();
                case "/api/pair":
                    return pair(in.optString("code").trim());
                default:
                    break;
            }
            if (!Prefs.tokenValid(this, headers.get("x-token"))) {
                return RemoteServer.Response.json(401, "{\"error\":\"Not paired\"}");
            }
            switch (path) {
                case "/api/state":
                    // "layer" and "playing" let the phone switch its motion controls to fit what is on screen.
                    return ok(new JSONObject()
                            .put("remote", Injector.status)
                            .put("room", Presence.get(this).describeNow())
                            .put("camera", Presence.enabled(this))
                            .put("ai", Ai.ready(this))
                            .put("layer", MainActivity.layer())
                            .put("playing", videoPlaying())
                            .toString());
                case "/api/camera":
                    Presence.get(this).setEnabled(in.optBoolean("on", true));
                    return ok(new JSONObject().put("camera", Presence.enabled(this)).toString());
                case "/api/tilt":
                    MainActivity.tilt((float) in.optDouble("x"), (float) in.optDouble("y"));
                    return ok("{}");
                case "/api/key": {
                    int code = Commander.keyCode(in.optString("key"));
                    if (code < 0) return RemoteServer.Response.json(400, "{\"error\":\"unknown key\"}");
                    boolean done = in.optBoolean("long") ? Injector.send(this, "longkey " + code) : Commander.pressKey(this, code);
                    return ok(new JSONObject().put("ok", done).put("remote", Injector.status).toString());
                }
                case "/api/text": {
                    boolean done = Injector.send(this, "text " + in.optString("text").replace('\n', ' '));
                    return ok(new JSONObject().put("ok", done).toString());
                }
                case "/api/say":
                    return ok(say(in.optString("text")));
                case "/api/sky": {
                    // Preview the living wallpaper at another time of day for a minute.
                    Views.Sky.override = in.optInt("minute", -1);
                    Views.Sky.overrideUntil = System.currentTimeMillis() + 60_000;
                    return ok("{}");
                }
                case "/api/gesture":
                    return ok(new JSONObject().put("done", gesture(in.optString("name"), in.optInt("dir"))).toString());
                case "/api/throw":
                    return ok(new JSONObject().put("done", Thrown.receive(this, in)).toString());
                case "/api/present":
                    // The body is the file (already saved by the server); none reopens the last one.
                    return ok(new JSONObject().put("done", Present.receive(this, headers.get(RemoteServer.UPLOAD), headers.get("x-name"))).toString());
                case "/api/brief":
                    Commander.goHome(this, "brief");
                    return ok("{}");
                case "/api/settings": {
                    String key = in.optString("openrouter_key").trim();
                    if (!key.isEmpty()) Prefs.setApiKey(this, key);
                    String ts = in.optString("typesafe_key").trim();
                    if (!ts.isEmpty()) Prefs.setTypesafeKey(this, ts);
                    return ok(new JSONObject().put("ai", Ai.ready(this)).toString());
                }
                default:
                    return RemoteServer.Response.json(404, "{\"error\":\"not found\"}");
            }
        } catch (Exception e) {
            return RemoteServer.Response.json(500, "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}");
        }
    }

    /**
     * Shows a new six-digit code on the TV. Anyone on the Wi-Fi can ask, so codes are rate-limited, each
     * allows five guesses, and ten wrong guesses within ten minutes (across all codes, so asking for a fresh
     * code doesn't reset it) lock pairing for ten minutes.
     */
    private synchronized RemoteServer.Response pairStart() {
        long now = System.currentTimeMillis();
        if (now < pairLockedUntil) return RemoteServer.Response.json(429, "{\"error\":\"Too many wrong codes. Try again in a few minutes.\"}");
        if (now - pairStartedAt < 5_000) return RemoteServer.Response.json(429, "{\"error\":\"Wait a few seconds\"}");
        pairStartedAt = now;
        pairCode = String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1_000_000));
        pairCodeUntil = now + 120_000;
        pairMisses = 0;
        MainActivity.showPairCode(this, pairCode);
        return ok("{}");
    }

    private synchronized RemoteServer.Response pair(String code) throws Exception {
        long now = System.currentTimeMillis();
        boolean right = pairCode != null && now <= pairCodeUntil && now >= pairLockedUntil
                && java.security.MessageDigest.isEqual(pairCode.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8));
        if (!right) {
            // Only guesses at a live code count, so nobody can hold pairing locked without codes showing on the TV.
            if (pairCode == null || now > pairCodeUntil || now < pairLockedUntil) {
                return RemoteServer.Response.json(403, "{\"error\":\"Wrong or expired code\"}");
            }
            pairMissTimes.addLast(now);
            while (!pairMissTimes.isEmpty() && now - pairMissTimes.peekFirst() > 10 * 60_000) pairMissTimes.removeFirst();
            boolean lock = pairMissTimes.size() >= 10;
            if (lock) {
                pairMissTimes.clear();
                pairLockedUntil = now + 10 * 60_000;
            }
            if (++pairMisses >= 5 || lock) {
                pairCode = null;
                MainActivity.showPairCode(this, null);
            }
            return RemoteServer.Response.json(403, "{\"error\":\"Wrong or expired code\"}");
        }
        pairCode = null;
        MainActivity.showPairCode(this, null);
        return ok(new JSONObject().put("token", Prefs.newToken(this)).toString());
    }

    /** One assistant turn from the phone: ask, act, and speak (on the panel when the home screen is in front). */
    private String say(String text) throws Exception {
        if (text.trim().isEmpty()) return "{}";
        List<Apps.App> apps = Apps.load(this);
        Ai.Reply r = Ai.command(this, text, apps, TvInputs.names(this), Presence.describe(Presence.get(this).people()));
        String done = Commander.run(this, r.actions, apps);
        boolean scene = r.actions.toString().contains("\"scene\"");
        boolean opensSomething = scene || done.startsWith("Opened") || done.startsWith("Switched") || done.startsWith("Searching")
                || done.startsWith("Finding") || done.startsWith("Working");
        if (!MainActivity.showAnswer(text, r.say, opensSomething) && !r.say.isEmpty()) {
            Speaker.get(this).speak(r.say);
            if (!opensSomething) Commander.toast(this, r.say);
        }
        return new JSONObject().put("say", r.say).put("done", done).toString();
    }

    private long playingCheckedAt;
    private boolean playingCached;

    /** Is the app in front playing video? Cached for a few seconds: the dial asks on every step. */
    private boolean videoPlaying() {
        if (System.currentTimeMillis() - playingCheckedAt > 3000) {
            byte[] d = Injector.query(this, "playing");
            playingCached = d != null && d.length > 0 && !MainActivity.isInFront();
            playingCheckedAt = System.currentTimeMillis();
        }
        return playingCached;
    }

    /** Phone gestures: the dial, pinches, the two-finger dismiss, and the circle that starts listening. */
    private String gesture(String name, int dir) {
        switch (name) {
            case "dial": {
                if (videoPlaying()) {
                    android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
                    am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                            dir > 0 ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER,
                            android.media.AudioManager.FLAG_SHOW_UI);
                    int v = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100
                            / Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
                    return "Volume " + v;
                }
                Commander.pressKey(this, dir > 0 ? android.view.KeyEvent.KEYCODE_DPAD_RIGHT : android.view.KeyEvent.KEYCODE_DPAD_LEFT);
                MainActivity.flash(this, dir > 0 ? "DIAL ▶" : "◀ DIAL");
                return "Dial";
            }
            case "pinch_out":
                if (MainActivity.zoomPhoto(1)) return "Zoom in";
                MainActivity.flash(this, "ALL APPS");
                Commander.goHome(this, "apps");
                return "All apps";
            case "pinch_in":
                if (MainActivity.zoomPhoto(-1)) return "Zoom out";
                MainActivity.flash(this, "HOME");
                if (!MainActivity.closeTop()) Commander.goHome(this, null);
                return "Home";
            case "volume": {
                android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                        dir > 0 ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER,
                        android.media.AudioManager.FLAG_SHOW_UI);
                return "Volume " + am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100
                        / Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
            }
            case "scrub":
                // Video players seek with the D-pad; each step is one seek.
                Commander.pressKey(this, dir > 0 ? android.view.KeyEvent.KEYCODE_DPAD_RIGHT : android.view.KeyEvent.KEYCODE_DPAD_LEFT);
                return dir > 0 ? "Forward" : "Rewind";
            case "flip_down":
                Commander.pressKey(this, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE);
                MainActivity.flash(this, "PAUSED · PHONE FACE DOWN");
                playingCheckedAt = 0;
                return "Paused";
            case "flip_up":
                Commander.pressKey(this, android.view.KeyEvent.KEYCODE_MEDIA_PLAY);
                MainActivity.flash(this, "PLAYING");
                playingCheckedAt = 0;
                return "Playing";
            case "knock":
                Commander.pressKey(this, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                MainActivity.flash(this, "KNOCK · PLAY/PAUSE");
                playingCheckedAt = 0;
                return "Play/pause";
            case "dismiss":
                MainActivity.flash(this, "BACK");
                Commander.pressKey(this, android.view.KeyEvent.KEYCODE_BACK);
                return "Back";
            case "circle":
                MainActivity.flash(this, "LISTENING…");
                return "Listening";
            default:
                return "";
        }
    }

    private RemoteServer.Response ok(String json) {
        return RemoteServer.Response.json(200, json);
    }

    private RemoteServer.Response asset(String name, String type) {
        try (InputStream in = getAssets().open(name)) {
            String text = Net.read(in).replace("{{ROOM}}", android.text.Html.escapeHtml(Prefs.ROOM));
            return new RemoteServer.Response(200, type, text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return RemoteServer.Response.json(404, "{}");
        }
    }
}
