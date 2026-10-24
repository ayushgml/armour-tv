package com.ayush.armour;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;

/** Everything the launcher remembers. The OpenRouter key lives here and nowhere else on the TV. */
final class Prefs {
    static final String MODEL = "deepseek/deepseek-v4.1-flash";
    // From personal.properties at build time (see app/build.gradle).
    static final String CITY = BuildConfig.CITY;
    static final double LAT = BuildConfig.LAT, LON = BuildConfig.LON;
    static final String NAME = BuildConfig.OWNER_NAME, ROOM = BuildConfig.ROOM_NAME;
    /** Dock order the user picked; the rest of the apps follow by how often they are opened. */
    static final String[] DOCK = {
            "com.netflix.ninja", "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom", "in.startv.hotstar"};
    static final String LIVE_TV = "com.mediatek.wwtv.tvcenter";

    private Prefs() {
    }

    static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences("armour", Context.MODE_PRIVATE);
    }

    static String apiKey(Context c) {
        importKeyFile(c);
        return get(c).getString("openrouter_key", "");
    }

    static void setApiKey(Context c, String key) {
        get(c).edit().putString("openrouter_key", key.trim()).apply();
    }

    /**
     * The install script drops the key in the app's external files folder (adb can write there, other apps
     * cannot read it); move it into private storage and delete the file straight away.
     */
    static void importKeyFile(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return;
        File f = new File(dir, "openrouter.key");
        if (!f.exists()) return;
        try {
            String key = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            if (!key.isEmpty()) setApiKey(c, key);
        } catch (Exception ignored) {
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** Optional TypeSafe key: lets the on-screen agent use Jev 1.14 for its next-step decisions. */
    static String typesafeKey(Context c) {
        return get(c).getString("typesafe_key", "");
    }

    static void setTypesafeKey(Context c, String key) {
        get(c).edit().putString("typesafe_key", key.trim()).apply();
    }

    /** "ring" (F, the default) or "rail" (F3). */
    static String layout(Context c) {
        return get(c).getString("layout", "ring");
    }

    static void setLayout(Context c, String layout) {
        get(c).edit().putString("layout", layout).apply();
    }

    /** Secret the phone sends with every request once paired. */
    static boolean tokenValid(Context c, String token) {
        String t = get(c).getString("tokens", "");
        if (token == null || token.length() < 16) return false;
        for (String s : t.split(",")) if (s.equals(token)) return true;
        return false;
    }

    static String newToken(Context c) {
        String token = random(24);
        String t = get(c).getString("tokens", "");
        get(c).edit().putString("tokens", t.isEmpty() ? token : t + "," + token).apply();
        return token;
    }

    /** Shared secret between the app and the key injector it starts through adb. */
    static String injectorSecret(Context c) {
        String s = get(c).getString("injector_secret", "");
        if (s.isEmpty()) {
            s = random(24);
            get(c).edit().putString("injector_secret", s).apply();
        }
        return s;
    }

    static String random(int n) {
        String abc = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(abc.charAt(r.nextInt(abc.length())));
        return b.toString();
    }
}
