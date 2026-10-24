package com.ayush.armour;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Where can I watch X?" for India, from JustWatch's public catalogue: which streaming service has a title,
 * and the title's page on it, matched against the apps installed on this TV.
 */
final class Watch {
    static final class Offer {
        String title, year, service, url, pkg;
        boolean included;   // part of a subscription, free, or with ads (not rent/buy)
    }

    private static final String QUERY = "query($q:String!){popularTitles(country:IN, first:4, filter:{searchQuery:$q})"
            + "{edges{node{... on MovieOrShow{objectType content(country:IN,language:\"en\"){title originalReleaseYear}"
            + " offers(country:IN, platform:WEB){monetizationType standardWebURL package{clearName technicalName}}}}}}}";

    private Watch() {
    }

    /** JustWatch service names to the TV app that plays them. */
    static String appFor(String service) {
        String s = service.toLowerCase(Locale.ROOT);
        if (s.contains("netflix")) return "com.netflix.ninja";
        if (s.contains("amazon") || s.contains("prime")) return "com.amazon.amazonvideo.livingroom";
        if (s.contains("hotstar")) return "in.startv.hotstar";
        if (s.contains("zee5")) return "com.graymatrix.did";
        if (s.contains("sony")) return "com.sonyliv";
        if (s.contains("jio")) return "in.startv.hotstar";   // JioCinema content moved into JioHotstar
        if (s.contains("apple")) return "com.apple.atve.androidtv.appletv";
        if (s.contains("youtube")) return "com.google.android.youtube.tv";
        if (s.contains("mx player")) return "com.mxtech.videoplayer.television";
        if (s.contains("google play")) return "com.google.android.videos";
        return null;
    }

    /** Offers for the best-matching title, installed apps only, included-with-subscription first. Blocking. */
    static List<Offer> find(Context c, String title) throws Exception {
        JSONObject body = new JSONObject().put("query", QUERY).put("variables", new JSONObject().put("q", title));
        JSONObject res = new JSONObject(Net.request("https://apis.justwatch.com/graphql", body.toString(), null));
        JSONArray edges = res.getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");
        List<Offer> out = new ArrayList<>();
        String want = squash(title);
        // Prefer the title whose name matches what was asked; else JustWatch's top result.
        JSONObject node = null;
        for (int i = 0; i < edges.length(); i++) {
            JSONObject n = edges.getJSONObject(i).getJSONObject("node");
            if (!n.has("content")) continue;
            if (node == null) node = n;
            if (squash(n.getJSONObject("content").optString("title")).equals(want)) {
                node = n;
                break;
            }
        }
        if (node == null) return out;
        JSONArray offers = node.optJSONArray("offers");
        if (offers == null) return out;
        String name = node.getJSONObject("content").optString("title");
        String year = node.getJSONObject("content").optString("originalReleaseYear");
        for (int i = 0; i < offers.length(); i++) {
            JSONObject o = offers.getJSONObject(i);
            Offer f = new Offer();
            f.title = name;
            f.year = year;
            f.service = o.getJSONObject("package").optString("clearName");
            f.url = o.optString("standardWebURL");
            String m = o.optString("monetizationType");
            f.included = m.equals("FLATRATE") || m.equals("FREE") || m.equals("ADS");
            f.pkg = appFor(f.service);
            if (f.pkg == null || c.getPackageManager().getLeanbackLaunchIntentForPackage(f.pkg) == null) continue;
            boolean dup = false;
            for (Offer e : out) dup |= e.pkg.equals(f.pkg) && e.included == f.included;
            if (!dup) out.add(f);
        }
        out.sort((a, b) -> a.included != b.included ? (a.included ? -1 : 1) : dockRank(a.pkg) - dockRank(b.pkg));
        return out;
    }

    private static int dockRank(String pkg) {
        for (int i = 0; i < Prefs.DOCK.length; i++) if (Prefs.DOCK[i].equals(pkg)) return i;
        return 99;
    }

    /**
     * An intent that opens the title inside the app. Netflix's TV app starts playback from a /watch/ link;
     * the others open the title's page, where the agent presses Play. Null when the app takes no links.
     */
    static Intent deepLink(Context c, Offer o) {
        String url = o.url;
        if (o.pkg.equals("com.netflix.ninja")) {
            Matcher m = Pattern.compile("/title/(\\d+)").matcher(url);
            if (m.find()) url = "https://www.netflix.com/watch/" + m.group(1);
        }
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(o.pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (o.pkg.equals("com.netflix.ninja")) i.putExtra("source", "30");
        return c.getPackageManager().resolveActivity(i, 0) != null ? i : null;
    }

    private static String squash(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
