package com.ayush.armour;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;

import java.util.List;

/** What the TV is playing right now, from Android's media sessions (needs {@link MediaListener} enabled). */
final class NowPlaying {
    String pkg = "", title = "", artist = "";
    boolean playing;
    /** Playback position in ms, estimated to now, and the song's length (0 when unknown). */
    long position, duration;
    Bitmap art;
    MediaController controller;

    private NowPlaying() {
    }

    /** The playing session if there is one, else the most recent paused one with a title; null when none. */
    static NowPlaying get(Context c) {
        List<MediaController> list;
        try {
            MediaSessionManager m = c.getSystemService(MediaSessionManager.class);
            list = m.getActiveSessions(new ComponentName(c, MediaListener.class));
        } catch (Exception e) {
            return null;
        }
        NowPlaying best = null;
        for (MediaController mc : list) {
            NowPlaying n = of(mc);
            if (n == null || n.title.isEmpty()) continue;
            if (n.playing) return n;
            if (best == null) best = n;
        }
        return best;
    }

    static NowPlaying of(MediaController mc) {
        MediaMetadata md = mc.getMetadata();
        if (md == null) return null;
        NowPlaying n = new NowPlaying();
        n.controller = mc;
        n.pkg = mc.getPackageName();
        n.title = nz(md.getString(MediaMetadata.METADATA_KEY_TITLE));
        n.artist = nz(md.getString(MediaMetadata.METADATA_KEY_ARTIST));
        if (n.artist.isEmpty()) n.artist = nz(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
        n.duration = Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION));
        n.art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (n.art == null) n.art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        PlaybackState s = mc.getPlaybackState();
        n.playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        if (s != null) {
            long since = android.os.SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime();
            n.position = s.getPosition() + (n.playing ? (long) (since * s.getPlaybackSpeed()) : 0);
        }
        return n;
    }

    /** "Kesariya by Pritam on Spotify (playing)", for the assistant's picture of the room. */
    String describe(Context c) {
        return title + (artist.isEmpty() ? "" : " by " + artist) + " on " + Apps.labelOf(c, pkg, pkg)
                + (playing ? " (playing)" : " (paused)");
    }

    private static String nz(CharSequence s) {
        return s == null ? "" : s.toString().trim();
    }
}
