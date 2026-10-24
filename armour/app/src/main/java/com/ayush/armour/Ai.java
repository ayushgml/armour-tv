package com.ayush.armour;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The assistant: DeepSeek v4.1 Flash through OpenRouter, answering in a small JSON command format. */
final class Ai {
    static final class Reply {
        String say = "";
        JSONArray actions = new JSONArray();
        /** Briefing only: two one-line headlines for the card. */
        JSONArray headlines = new JSONArray();
    }

    private static final String RULES =
            "You are the voice assistant built into a living-room TV. Speak like a calm, slightly witty butler: "
            + "short, warm, natural sentences meant to be read aloud (no markdown, no lists, no emoji). "
            + "Reply with ONLY a JSON object: {\"say\": string, \"actions\": array}. "
            + "Allowed actions: "
            + "{\"type\":\"open_app\",\"app\":<one of the installed app names>}, "
            + "{\"type\":\"youtube_search\",\"query\":string}, "
            + "{\"type\":\"input\",\"name\":<one of the input names>}, "
            + "{\"type\":\"volume\",\"level\":0-100} or {\"type\":\"volume\",\"change\":-100..100}, "
            + "{\"type\":\"mute\"}, "
            + "{\"type\":\"media\",\"key\":\"pause\"|\"play\"|\"play_pause\"|\"next\"|\"previous\"|\"stop\"}, "
            + "{\"type\":\"media\",\"key\":\"forward\"|\"rewind\",\"seconds\":number}, "
            + "{\"type\":\"key\",\"key\":\"home\"|\"back\"|\"up\"|\"down\"|\"left\"|\"right\"|\"ok\"}, "
            + "{\"type\":\"briefing\"}, {\"type\":\"show_apps\"}, {\"type\":\"sleep\"} (turns the TV off), "
            + "{\"type\":\"play\",\"title\":<official title of the film or show>}, "
            + "{\"type\":\"spotify\",\"query\":<song, artist, album, playlist or mood>}, "
            + "{\"type\":\"youtube\",\"query\":string,\"newest\":bool} (plays the top YouTube video), "
            + "{\"type\":\"do\",\"app\":<installed app name, or omit for the app in front>,\"goal\":string}, "
            + "{\"type\":\"after\",\"minutes\":number,\"actions\":array} and {\"type\":\"cancel_timers\"}, "
            + "{\"type\":\"lights\",\"set\":<colour name or #rrggbb, \"warm\", \"white\", \"cool\", \"candle\", \"on\" or "
            + "\"off\">,\"brightness\":10-100 (optional)} for the room's smart lights, "
            + "{\"type\":\"scene\",\"name\":\"daddy_home\"|\"batman\"|\"movie_night\"|\"normal\"}. "
            + "Scenes: daddy_home is for any playful 'I'm home' entrance like 'wake up, daddy's home', 'honey I'm "
            + "home' or 'Jarvis, I'm back' (it plays Highway to Hell and shows the welcome board); batman for 'I am "
            + "Batman' or similar; movie_night for 'movie night', 'cinema mode', 'lights down for a film'; normal for "
            + "'normal mode', 'back to normal', 'I'm done being Batman'. A scene does its own music and lights, so do not "
            + "add those actions for it. Reply to a scene in character (a Batman scene gets a gravelly one-liner; "
            + "daddy_home gets a butler's welcome), one short sentence. "
            + "Actions run in order, each waiting for the one before, so one request can hold several steps. "
            + "For 'play', 'put on' or 'watch' followed by a film or show, use the play action with the exact official "
            + "title (fix spelling, e.g. 'zindagi na milegi dobara' -> 'Zindagi Na Milegi Dobara'); the TV finds which "
            + "app has it, so do not also open an app, and say you are finding it. "
            + "For music (a song, singer, album, playlist, genre or mood like 'something chill') use spotify with a "
            + "good search query (e.g. 'romantic hindi songs' -> 'Romantic Hindi'). To play a particular YouTube video, "
            + "channel's video or music video, use youtube (newest true for 'the latest'); youtube_search only shows "
            + "results to browse. 'Change the song', 'skip' or 'next' is media next; 'pause'/'resume' are media pause/play. "
            + "Use do for any other task inside an app that needs moving around the screen (turn on subtitles, open "
            + "the next episode, change the audio language, like a video); write the goal as a clear instruction. "
            + "Use after for anything later: 'turn off the TV in 30 minutes' is after 30 with sleep; 'pause in 10' is "
            + "after 10 with media pause. "
            + "Use actions only when the user asks the TV to do something; for questions just answer in \"say\" "
            + "(at most 3 sentences). For a recommendation, name real titles that are likely on the installed apps "
            + "and open that app. If the user asks for a briefing or 'brief me', return the briefing action. "
            + "Never invent facts about the user's schedule.";

    private Ai() {
    }

    static boolean ready(Context c) {
        return !Prefs.apiKey(c).isEmpty();
    }

    /** Blocking. {@code inputs} are the TV's input names, {@code room} what the camera sees. */
    static Reply command(Context c, String userText, List<Apps.App> apps, List<String> inputs, String room) throws Exception {
        StringBuilder names = new StringBuilder();
        for (Apps.App a : apps) names.append(names.length() > 0 ? ", " : "").append(a.label);
        Weather.Now w = Weather.cached(c);
        String state = "Now: " + new SimpleDateFormat("EEEE d MMMM, h:mm a", Locale.ENGLISH).format(new Date())
                + ". City: " + Prefs.CITY + ". User: " + Prefs.NAME + ". "
                + (w.ok() ? "Weather: " + w.temp + "°C, " + w.sky + ", AQI " + w.aqi + ". " : "")
                + "Room camera: " + room + ". "
                + "Now playing: " + playing(c) + ". "
                + "Installed apps: " + names + ". Inputs: " + String.join(", ", inputs) + ", Live TV.";
        JSONObject out = json(c, Prefs.MODEL, RULES + "\n" + state, userText, null, 1500);
        Reply r = new Reply();
        r.say = out.optString("say", "");
        JSONArray a = out.optJSONArray("actions");
        if (a != null) r.actions = a;
        return r;
    }

    private static String playing(Context c) {
        NowPlaying n = NowPlaying.get(c);
        return n == null ? "nothing" : n.describe(c);
    }

    /** Blocking. The spoken evening/morning briefing from weather, headlines and the room. */
    static Reply briefing(Context c, Weather.Now w, List<String> headlines, String room) throws Exception {
        StringBuilder h = new StringBuilder();
        for (int i = 0; i < Math.min(15, headlines.size()); i++) h.append("- ").append(headlines.get(i)).append('\n');
        String sys = "You write a spoken TV briefing for " + Prefs.NAME + " in " + Prefs.CITY + ". "
                + "Reply with ONLY JSON: {\"say\": string, \"headlines\": [string, string]}. "
                + "\"say\": 3 to 5 short sentences to be read aloud: greet by name for the time of day, the weather "
                + "now and for the next hours, the air quality in plain words, then the two most important AI stories "
                + "from the list in your own words (the list is the last day of AI news and repeats stories across "
                + "outlets; pick two different stories, prefer model releases, research and big company moves). No markdown. "
                + "\"headlines\": those two stories as very short titles (max 8 words each), without the source name.";
        String user = "Time: " + new SimpleDateFormat("EEEE d MMMM, h:mm a", Locale.ENGLISH).format(new Date()) + "\n"
                + (w.ok() ? "Weather now " + w.temp + "°C " + w.sky + ", today high " + w.high + " low " + w.low
                + ", next hours " + w.tonight() + ". US AQI " + w.aqi + " (" + w.aqiWord() + ").\n" : "Weather unavailable.\n")
                + "Room camera: " + room + "\nAI news headlines:\n" + h;
        JSONObject out = json(c, Prefs.MODEL, sys, user, null, 2000);
        Reply r = new Reply();
        r.say = out.optString("say", "");
        JSONArray hl = out.optJSONArray("headlines");
        if (hl != null) r.headlines = hl;
        return r;
    }

    /** One JSON-answer call to {@code model} on OpenRouter; {@code imageUrl} (a data: URL) is optional. */
    static JSONObject json(Context c, String model, String system, String user, String imageUrl, int maxTokens) throws Exception {
        String key = Prefs.apiKey(c);
        if (key.isEmpty()) throw new Exception("No OpenRouter key. Add it from the phone page, under Settings.");
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("max_tokens", maxTokens)
                .put("response_format", new JSONObject().put("type", "json_object"))
                // Short thinking: these are quick TV decisions, and long reasoning can eat the whole budget.
                .put("reasoning", new JSONObject().put("effort", "low"))
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", system))
                        .put(new JSONObject().put("role", "user").put("content", imageUrl == null ? user
                                : new JSONArray().put(new JSONObject().put("type", "text").put("text", user))
                                .put(new JSONObject().put("type", "image_url")
                                        .put("image_url", new JSONObject().put("url", imageUrl))))));
        String res = Net.request("https://openrouter.ai/api/v1/chat/completions", body.toString(), key);
        JSONObject msg = new JSONObject(res).getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        String content = msg.isNull("content") ? "" : msg.optString("content", "").trim();
        if (content.isEmpty()) throw new Exception("empty answer from " + model);
        // Some models wrap JSON in a code fence despite response_format.
        int s = content.indexOf('{'), e = content.lastIndexOf('}');
        if (s >= 0 && e > s) content = content.substring(s, e + 1);
        return new JSONObject(content);
    }
}
