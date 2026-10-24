package com.ayush.armour;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Installed TV apps, the dock first and the rest by how often they were opened from here. */
final class Apps {
    static final class App {
        final String pkg;
        final String label;
        final boolean docked;
        int launches;
        long lastOpened;

        App(String pkg, String label, boolean docked) {
            this.pkg = pkg;
            this.label = label;
            this.docked = docked;
        }

        /** Big wordmark-style name used on tiles: "Prime Video" → "PRIME VIDEO". */
        String title() {
            return label.toUpperCase(Locale.ROOT);
        }
    }

    /** Never shown as tiles: ourselves, the old launcher, Live TV (it is in Inputs) and setting screens. */
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            "com.ayush.armour", "com.gathin.tvhome", Prefs.LIVE_TV, "com.android.tv.settings",
            "org.liskovsoft.androidtv.rukeyboard"));

    private Apps() {
    }

    static List<App> load(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        SharedPreferences p = Prefs.get(c);
        List<String> dock = Arrays.asList(Prefs.DOCK);
        List<App> docked = new ArrayList<>(), rest = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (HIDDEN.contains(pkg) || !seen.add(pkg)) continue;
            String label = String.valueOf(ri.loadLabel(pm));
            App a = new App(pkg, cleanLabel(pkg, label), dock.contains(pkg));
            a.launches = p.getInt("n_" + pkg, 0);
            a.lastOpened = p.getLong("t_" + pkg, 0);
            (a.docked ? docked : rest).add(a);
        }
        docked.sort((a, b) -> dock.indexOf(a.pkg) - dock.indexOf(b.pkg));
        rest.sort((a, b) -> a.launches != b.launches ? b.launches - a.launches : a.label.compareToIgnoreCase(b.label));
        docked.addAll(rest);
        return docked;
    }

    /** Shorter names where the store label is long or vague. */
    private static String cleanLabel(String pkg, String label) {
        switch (pkg) {
            case "com.amazon.amazonvideo.livingroom": return "Prime Video";
            case "com.google.android.youtube.tvmusic": return "YT Music";
            case "com.jio.media.stb.ondemand": return "JioCinema";
            case "com.formulaone.production": return "F1 TV";
            case "com.graymatrix.did": return "ZEE5";
            case "com.android.vending": return "Play Store";
            case "com.tianci.localmedia": return "USB Media";
            case "com.google.android.videos": return "Google TV";
            case "com.mxtech.videoplayer.television": return "MX Player";
            case "com.boost.airplay.receiver": return "AirMirror";
            default: return label.length() > 14 ? label.substring(0, 14).trim() : label;
        }
    }

    /** The launcher's name for an installed app ("Prime Video"), or {@code fallback}. */
    static String labelOf(Context c, String pkg, String fallback) {
        for (App a : load(c)) if (a.pkg.equals(pkg)) return a.label;
        return fallback;
    }

    static Drawable banner(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            Drawable d = pm.getApplicationBanner(pkg);
            return d != null ? d : pm.getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean launch(Context c, String pkg) {
        Intent i = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            return false;
        }
        SharedPreferences p = Prefs.get(c);
        p.edit().putInt("n_" + pkg, p.getInt("n_" + pkg, 0) + 1)
                .putLong("t_" + pkg, System.currentTimeMillis()).apply();
        return true;
    }

    /** Best match for a spoken app name ("hotstar", "prime", "you tube"). */
    static App find(List<App> apps, String name) {
        if (name == null) return null;
        String n = squash(name);
        if (n.isEmpty()) return null;
        for (App a : apps) if (squash(a.label).equals(n)) return a;
        for (App a : apps) if (squash(a.label).contains(n) || n.contains(squash(a.label))) return a;
        for (App a : apps) if (a.pkg.toLowerCase(Locale.ROOT).contains(n)) return a;
        return null;
    }

    private static String squash(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** "Opened 2 h ago" style line for the hero and the ring. */
    static String lastOpenedLine(App a) {
        if (a.lastOpened == 0) return "PRESS OK TO OPEN";
        long mins = (System.currentTimeMillis() - a.lastOpened) / 60000;
        String ago;
        if (mins < 1) ago = "JUST NOW";
        else if (mins < 60) ago = mins + " MIN AGO";
        else if (mins < 48 * 60) ago = (mins / 60) + " H AGO";
        else ago = (mins / 1440) + " DAYS AGO";
        return "LAST OPENED " + ago;
    }
}
