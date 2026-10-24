package com.ayush.armour;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.net.Uri;

/**
 * "Play Arijit Singh on Spotify". Spotify's TV app plays the top result of a spotify:search: link, but only
 * once it is already running: from cold it just shows the results. So start it first, then send the link,
 * and check its media session to know the new song is really playing.
 */
final class Spotify {
    static final String PKG = "com.spotify.tv.android";

    private Spotify() {
    }

    static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Blocking, up to about half a minute. Returns the sentence to say. */
    static String play(Context c, String query) {
        if (!installed(c)) return "Spotify isn't installed on this TV.";
        NowPlaying before = current(c);
        if (before == null) {
            Apps.launch(c, PKG);
            Agent.sleep(7000);
        }
        String oldTitle = before == null ? "" : before.title;
        for (int attempt = 0; attempt < 2; attempt++) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query)))
                    .setPackage(PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                c.startActivity(i);
            } catch (Exception e) {
                return "I couldn't open Spotify.";
            }
            for (int t = 0; t < 10; t++) {
                Agent.sleep(1000);
                NowPlaying n = current(c);
                // A new song, or the same one started again from the top (asked for twice in a row).
                if (n != null && n.playing && (!n.title.equals(oldTitle) || n.position < 4000)) {
                    return "Playing " + n.title + (n.artist.isEmpty() ? "" : " by " + n.artist) + " on Spotify.";
                }
            }
        }
        NowPlaying n = current(c);
        if (n != null && n.playing) return "Playing " + n.title + " on Spotify.";
        return "I searched Spotify for " + query + ", but it didn't start. Pick a song from the results.";
    }

    /** Spotify's own session, playing or not; null when it has none. */
    static NowPlaying current(Context c) {
        NowPlaying n = NowPlaying.get(c);
        if (n != null && n.pkg.equals(PKG)) return n;
        // NowPlaying prefers whatever is playing; Spotify may be the paused one behind it.
        try {
            MediaSessionManager m = c.getSystemService(MediaSessionManager.class);
            for (MediaController mc : m.getActiveSessions(new ComponentName(c, MediaListener.class))) {
                if (PKG.equals(mc.getPackageName()) && mc.getMetadata() != null) return NowPlaying.of(mc);
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
