package com.ayush.armour;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the phone flicks at the TV. A photo becomes a hologram on the home screen; a YouTube or streaming
 * link opens in its app; any other web page opens in a panel.
 */
final class Thrown {
    private Thrown() {
    }

    static File photoFile(Context c) {
        return new File(c.getCacheDir(), "thrown.jpg");
    }

    /** Returns a short note for the phone. */
    static String receive(Context c, JSONObject in) throws Exception {
        String kind = in.optString("kind");
        if (kind.equals("image")) {
            byte[] jpg = Base64.decode(in.optString("data"), Base64.DEFAULT);
            if (jpg.length == 0) return "Empty photo";
            Files.write(photoFile(c).toPath(), jpg);
            Commander.goHome(c, "photo");
            return "Photo is on the TV.";
        }
        if (!kind.equals("url")) return "Nothing to throw";
        String url = in.optString("url").trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "Not a web link";
        Uri u = Uri.parse(url);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);

        String yt = youtubeId(url);
        if (yt != null) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + yt))
                    .setPackage("com.google.android.youtube.tv").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (start(c, i)) return "Playing on YouTube.";
        }
        String pkg = host.contains("netflix") ? "com.netflix.ninja"
                : host.contains("primevideo") || host.contains("amazon") ? "com.amazon.amazonvideo.livingroom"
                : host.contains("hotstar") ? "in.startv.hotstar"
                : host.contains("zee5") ? "com.graymatrix.did"
                : host.contains("sonyliv") ? "com.sonyliv"
                : host.contains("spotify") ? "com.spotify.tv.android" : null;
        if (pkg != null) {
            String link = url;
            Matcher m = Pattern.compile("netflix\\.com/(?:[a-z-]+/)?title/(\\d+)").matcher(url);
            if (m.find()) link = "https://www.netflix.com/watch/" + m.group(1);
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (start(c, i)) return "Opened in " + Apps.labelOf(c, pkg, "the app") + ".";
        }
        Commander.goHome(c, "web", url);
        return "Opened on the TV.";
    }

    private static boolean start(Context c, Intent i) {
        if (c.getPackageManager().resolveActivity(i, 0) == null) return false;
        try {
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** youtu.be/ID, youtube.com/watch?v=ID, /shorts/ID, /live/ID. */
    static String youtubeId(String url) {
        Matcher m = Pattern.compile("(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|live/|embed/))([A-Za-z0-9_-]{11})")
                .matcher(url);
        return m.find() ? m.group(1) : null;
    }
}
