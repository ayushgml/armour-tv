package com.ayush.armour;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Play the latest Two Minute Papers video": look the video up on YouTube's results page and open it
 * directly, which starts it. Much faster than driving the TV app's on-screen keyboard.
 */
final class YouTube {
    static final String PKG = "com.google.android.youtube.tv";
    private static final Pattern FIRST = Pattern.compile(
            "\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\".*?\"title\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\"",
            Pattern.DOTALL);

    private YouTube() {
    }

    /** Blocking. {@code newest} sorts by upload date (for "the latest video from ..."). Returns what to say. */
    static String play(Context c, String query, boolean newest) {
        String id, title;
        try {
            String url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
                    + (newest ? "&sp=CAI%253D" : "");
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(8000);
            h.setReadTimeout(15000);
            h.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36");
            h.setRequestProperty("Accept-Language", "en-IN,en");
            String page = Net.read(h.getInputStream());
            h.disconnect();
            Matcher m = FIRST.matcher(page);
            if (!m.find()) return search(c, query);
            id = m.group(1);
            title = m.group(2).replace("\\u0026", "&").replace("\\\"", "\"");
        } catch (Exception e) {
            return search(c, query);
        }
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + id))
                .setPackage(PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            return search(c, query);
        }
        return "Playing " + title + " on YouTube.";
    }

    /** No result to open: show YouTube's own search results instead. */
    static String search(Context c, String query) {
        c.startActivity(new Intent(Intent.ACTION_SEARCH).setPackage(PKG).putExtra("query", query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return "Here's what YouTube has for " + query + ".";
    }
}
