package com.ayush.armour;

import android.content.Context;
import android.content.Intent;

import java.util.List;

/**
 * "Play Zindagi Na Milegi Dobara": find which installed app has it (JustWatch), open the title inside that
 * app, and let the agent press Play or get past a profile chooser until the film is actually playing.
 */
final class Player {
    private Player() {
    }

    /** Blocking, up to about a minute. Returns the sentence to say when it is done. */
    static String play(Context c, String title, Agent.Progress progress) {
        List<Watch.Offer> offers;
        try {
            offers = Watch.find(c, title);
        } catch (Exception e) {
            offers = java.util.Collections.emptyList();
        }
        if (offers.isEmpty()) {
            return searchAndPlay(c, title, null, progress);
        }
        Watch.Offer o = offers.get(0);
        String app = Apps.labelOf(c, o.pkg, o.service);
        if (!o.included) progress.step(o.title + " is only to rent or buy on " + app);
        progress.step("Found " + o.title + " on " + app);
        Intent link = Watch.deepLink(c, o);
        if (link == null) return searchAndPlay(c, o.title, o.pkg, progress);
        try {
            c.startActivity(link);
        } catch (Exception e) {
            return searchAndPlay(c, o.title, o.pkg, progress);
        }
        Agent.sleep(5000);
        if (Agent.isPlaying(c, o.pkg)) return "Playing " + o.title + " on " + app + ".";
        // A title page usually opens with Play or Resume already selected: try one OK before thinking.
        progress.step("Pressing Play");
        Injector.key(c, android.view.KeyEvent.KEYCODE_DPAD_CENTER);
        Agent.sleep(5000);
        if (Agent.isPlaying(c, o.pkg)) return "Playing " + o.title + " on " + app + ".";
        boolean ok = Agent.run(c, "Start playing the film or show \"" + o.title + "\" (" + o.year + ") that is open in " + app
                + ". Press its Play, Resume or Watch button.", o.pkg, progress);
        return ok ? "Playing " + o.title + " on " + app + "."
                : "I opened " + o.title + " on " + app + ", but couldn't start it myself. Press Play on the remote.";
    }

    /** No catalogue match or no link: open the app (or YouTube) and search for the title like a person would. */
    private static String searchAndPlay(Context c, String title, String pkg, Agent.Progress progress) {
        String target = pkg != null ? pkg : "com.google.android.youtube.tv";
        if (!Apps.launch(c, target)) return "I couldn't find " + title + " on your apps.";
        Agent.sleep(4000);
        boolean ok = Agent.run(c, "Find \"" + title + "\" using this app's search and start playing it.", target, progress);
        return ok ? "Playing " + title + "." : "I couldn't find " + title + " on your apps.";
    }
}
