package com.ayush.armour;

import android.content.Context;
import android.content.Intent;

/**
 * Named moments the assistant can set off with one phrase: the lights, the music and the look of the TV
 * change together. "normal" puts everything back.
 */
final class Scenes {
    private Scenes() {
    }

    /** Starts scene {@code name}; returns a note for the phone. The slow parts run in the background. */
    static String run(Context c, String name) {
        switch (name) {
            case "daddy_home":
                return daddyHome(c);
            case "batman":
                return batman(c);
            case "movie_night":
                return movieNight(c);
            case "normal":
                return normal(c);
            default:
                return "I don't know the scene " + name + ".";
        }
    }

    /** "Wake up, Daddy's home": lights flash red then come up full, Highway to Hell, the welcome board. */
    private static String daddyHome(Context c) {
        MainActivity.applyLook(c, "armour");
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("show", "welcome").putExtra("line", "Good " + partOfDay() + ". Highway to Hell, as requested.");
        c.startActivity(i);
        Net.IO.execute(() -> {
            Lights.color(c, 0xFF0000, 100);
            Agent.sleep(350);
            Lights.white(c, 4200, 30);
            Agent.sleep(350);
            Lights.color(c, 0xFF0000, 100);
            Agent.sleep(350);
            Lights.white(c, 4200, 100);
        });
        Net.IO.execute(() -> playOverHome(c, "Highway to Hell AC/DC"));
        return "Welcome home. Highway to Hell coming up.";
    }

    /** "I am Batman": red lights, the Batman theme, and Armour turns Gotham red with the bat signal. */
    private static String batman(Context c) {
        MainActivity.applyLook(c, "batman");
        Commander.goHome(c, null);
        Net.IO.execute(() -> {
            Lights.save(c);
            Lights.color(c, 0xFF0000, 70);
        });
        Net.IO.execute(() -> playOverHome(c, "The Batman Michael Giacchino theme"));
        return "Gotham mode.";
    }

    /** "Movie night": lights down to candle-warm, the dim cinema look, and the streaming apps up front. */
    private static String movieNight(Context c) {
        MainActivity.applyLook(c, "cinema");
        Commander.goHome(c, null);
        Net.IO.execute(() -> {
            Lights.save(c);
            Lights.white(c, 2200, 10);
        });
        return "Lights down. Movie night.";
    }

    /** Back to the usual Armour look and the lights as they were before the scene. */
    private static String normal(Context c) {
        MainActivity.applyLook(c, "armour");
        Net.IO.execute(() -> Lights.restore(c));
        return "Back to normal.";
    }

    /**
     * Starts the music, then brings Armour back in front of Spotify so the scene's screen stays visible
     * while the song plays behind it.
     */
    private static void playOverHome(Context c, String query) {
        Spotify.play(c, query);
        Commander.goHome(c, null);
    }

    private static String partOfDay() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h < 5 ? "night" : h < 12 ? "morning" : h < 17 ? "afternoon" : h < 22 ? "evening" : "night";
    }
}
