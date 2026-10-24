package com.ayush.armour;

import android.content.Context;
import android.graphics.Rect;
import android.util.Base64;
import android.util.Log;
import android.util.Xml;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Operates TV apps the way a person does with the remote: read the screen, decide the next step, move the
 * focus, press OK. It reads the app's view tree first (exact and cheap); apps that draw their own UI get a
 * small screenshot sent to a vision model instead. Finished when Android reports the app is playing video.
 */
final class Agent {
    interface Progress {
        void step(String what);
    }

    static final class El {
        String id, text, desc, cls, res;
        Rect r;
        boolean focused, focusable, editable;

        String describe() {
            String label = !text.isEmpty() ? text : !desc.isEmpty() ? desc : res;
            return "\"" + label + "\"" + (editable ? " [text field]" : "") + (focused ? " [FOCUSED]" : "")
                    + " at " + r.centerX() + "," + r.centerY() + " size " + r.width() + "x" + r.height();
        }
    }

    private static final String TAG = "Armour";
    /** Apps that draw their own UI with no readable view tree (Prime Video): go straight to screenshots. */
    private static final java.util.Set<String> noTree = new java.util.HashSet<>();
    /** The app the current task runs in, and the screen Jev saw last (it compares the two). One task at a time. */
    private static String curApp = "";
    private static byte[] lastShot;

    private Agent() {
    }

    /** Blocking, up to about a minute. Returns true once {@code pkg} is playing. */
    static boolean run(Context c, String goal, String pkg, Progress progress) {
        return run(c, goal, pkg, progress, true);
    }

    /**
     * Blocking, up to about a minute. With {@code untilPlaying} the goal is a video and playback ends it;
     * otherwise (turn on subtitles, open the next episode) the model says when the goal is reached.
     */
    static boolean run(Context c, String goal, String pkg, Progress progress, boolean untilPlaying) {
        List<String> history = new ArrayList<>();
        String app = front(c);
        curApp = app;
        lastShot = null;
        for (int step = 0; step < 16; step++) {
            if (untilPlaying && isPlaying(c, pkg)) return true;
            // A task inside an app ends if that app is left (a stray Back out of a video, say).
            if (!untilPlaying && !app.isEmpty() && step > 0 && !app.equals(front(c))) {
                Log.i(TAG, "agent left " + app + ", stopping");
                return false;
            }
            List<El> els = noTree.contains(pkg) ? new ArrayList<>() : elements(c);
            if (els.size() < 2) noTree.add(pkg);
            JSONObject act;
            long t0 = System.currentTimeMillis();
            try {
                if (els.size() >= 2) act = decideFromTree(c, goal, els, history);
                else act = decideFromScreenshot(c, goal, history);
            } catch (Exception e) {
                Log.w(TAG, "agent decision failed", e);
                sleep(1500);
                continue;
            }
            String a = act.optString("action");
            history.add(a + (act.has("id") ? " " + label(els, act.optString("id")) : "")
                    + (act.has("key") ? " " + act.optString("key") + (act.optInt("times", 1) > 1 ? " x" + act.optInt("times") : "") : "") + (act.has("text") ? " '" + act.optString("text") + "'" : ""));
            Log.i(TAG, "agent step " + step + " (" + (System.currentTimeMillis() - t0) + " ms): " + act);
            switch (a) {
                case "done":
                    if (!untilPlaying) return act.optBoolean("playing", true);
                    return isPlaying(c, pkg) || act.optBoolean("playing");
                case "wait":
                    sleep(2000);
                    break;
                case "type":
                    progress.step("Typing “" + act.optString("text") + "”");
                    Injector.send(c, "text " + act.optString("text"));
                    sleep(1500);
                    break;
                case "key":
                    press(c, act.optString("key"), Math.max(1, Math.min(6, act.optInt("times", 1))));
                    sleep(600);
                    break;
                case "select": {
                    El target = byId(els, act.optString("id"));
                    if (target == null) break;
                    progress.step("Selecting " + (target.text.isEmpty() ? target.desc : target.text));
                    moveTo(c, els, target);
                    break;
                }
                default:
                    sleep(1000);
            }
        }
        return untilPlaying && isPlaying(c, pkg);
    }

    static String front(Context c) {
        byte[] d = Injector.query(c, "front");
        return d == null ? "" : new String(d, StandardCharsets.UTF_8).trim();
    }

    static boolean isPlaying(Context c, String pkg) {
        byte[] d = Injector.query(c, "playing");
        return d != null && new String(d, StandardCharsets.UTF_8).contains(pkg);
    }

    // ---- reading the screen ----------------------------------------------------------------------

    static List<El> elements(Context c) {
        List<El> out = new ArrayList<>();
        byte[] xml = Injector.query(c, "dump");
        if (xml == null) return out;
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new StringReader(new String(xml, StandardCharsets.UTF_8)));
            int n = 0;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e != XmlPullParser.START_TAG || !"node".equals(p.getName())) continue;
                El el = new El();
                el.text = attr(p, "text");
                el.desc = attr(p, "content-desc");
                el.cls = attr(p, "class");
                String res = attr(p, "resource-id");
                el.res = res.contains("/") ? res.substring(res.indexOf('/') + 1) : res;
                el.focused = "true".equals(attr(p, "focused"));
                el.focusable = "true".equals(attr(p, "focusable"));
                el.editable = el.cls.endsWith("EditText");
                el.r = bounds(attr(p, "bounds"));
                if (el.r == null || el.r.width() < 8 || el.r.height() < 8) continue;
                boolean useful = !el.text.isEmpty() || !el.desc.isEmpty() || el.focused || (el.focusable && !el.res.isEmpty());
                if (!useful) continue;
                el.id = "e" + n++;
                out.add(el);
                if (out.size() >= 70) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "ui dump parse failed", e);
        }
        return out;
    }

    private static String attr(XmlPullParser p, String name) {
        String v = p.getAttributeValue(null, name);
        return v == null ? "" : v.trim();
    }

    private static Rect bounds(String b) {
        String[] n = b.replaceAll("[\\[\\]]", ",").split(",+");
        try {
            List<Integer> v = new ArrayList<>();
            for (String s : n) if (!s.isEmpty()) v.add(Integer.parseInt(s));
            return v.size() == 4 ? new Rect(v.get(0), v.get(1), v.get(2), v.get(3)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static El byId(List<El> els, String id) {
        for (El e : els) if (e.id.equals(id)) return e;
        return null;
    }

    private static String label(List<El> els, String id) {
        El e = byId(els, id);
        return e == null ? id : (e.text.isEmpty() ? e.desc : e.text);
    }

    // ---- deciding --------------------------------------------------------------------------------

    private static final String AGENT_RULES = "You operate an Android TV app with a remote control (D-pad up/down/left/right, ok, back) "
            + "to reach a goal. Reply with ONLY JSON, one of: "
            + "{\"action\":\"select\",\"id\":<element id>} to move the focus to that element and press OK; "
            + "{\"action\":\"key\",\"key\":\"up|down|left|right|ok|back\"}; "
            + "{\"action\":\"type\",\"text\":string} when a search text field is focused; "
            + "{\"action\":\"wait\"} while loading; {\"action\":\"done\",\"playing\":bool} when the goal is reached (for a video: it is playing) with playing true, "
            + "or with playing false when the goal is impossible. "
            + "Prefer buttons like Play, Resume, Watch now, Start watching. On a 'Who's watching' profile screen pick the first profile. "
            + "To find a title: open Search, type the title, then select the matching result. Never open settings or buy/rent.";

    private static JSONObject decideFromTree(Context c, String goal, List<El> els, List<String> history) throws Exception {
        StringBuilder screen = new StringBuilder();
        for (El e : els) screen.append(e.id).append(": ").append(e.describe()).append('\n');
        JSONObject jev = decideWithJev(c, goal, els, history);
        if (jev != null) return jev;
        String user = "Goal: " + goal + "\nSteps so far: " + history + "\nScreen elements:\n" + screen;
        return Ai.json(c, Prefs.MODEL, AGENT_RULES, user, null, 700);
    }

    /**
     * Jev 1.14 (TypeSafe System One): one Choice over the on-screen elements plus a few remote actions. Fast
     * and calibrated; when its confidence is low the caller falls back to the language model.
     */
    private static JSONObject decideWithJev(Context c, String goal, List<El> els, List<String> history) {
        try {
            JSONObject criteria = new JSONObject();
            for (El e : els) criteria.put(e.id, "Select " + e.describe());
            criteria.put("back", "Press Back: this screen is a dead end for the goal");
            criteria.put("down", "Press Down: what is needed is probably further down, not listed yet");
            criteria.put("wait", "The screen is still loading or showing an intro");
            criteria.put("done", "The goal is already reached (for a video: it is playing)");
            JSONObject state = new JSONObject().put("goal", goal).put("steps_so_far", new JSONArray(history));
            JSONObject q = new JSONObject().put("type", "choice")
                    .put("instructions", "Which remote action moves this TV app closest to `goal`? Prefer Play, Resume or "
                            + "Watch buttons; on a profile chooser pick the first profile; never buy or rent.")
                    .put("criteria", criteria);
            JSONObject res = jev(c, state, new JSONObject().put("next", q));
            if (res == null) return null;
            JSONObject ans = res.getJSONObject("answers").getJSONObject("next");
            if (ans.optDouble("confidence", 0) < 0.55) return null;
            String choice = ans.getString("choice");
            if (choice.startsWith("e")) return new JSONObject().put("action", "select").put("id", choice);
            if (choice.equals("done")) return new JSONObject().put("action", "done").put("playing", true);
            if (choice.equals("wait")) return new JSONObject().put("action", "wait");
            return new JSONObject().put("action", "key").put("key", choice);
        } catch (Exception e) {
            Log.w(TAG, "jev failed, using the language model", e);
            return null;
        }
    }

    /**
     * One Jev decision. With a TypeSafe key it goes to TypeSafe; otherwise through OpenRouter's decisions
     * endpoint with the OpenRouter key. {@code state} is an object, or for OpenRouter images an array of text
     * and image_url parts. Returns null when no key works.
     */
    private static JSONObject jev(Context c, Object state, JSONObject questions) throws Exception {
        String ts = Prefs.typesafeKey(c);
        if (!ts.isEmpty() && state instanceof JSONObject) {
            JSONObject body = new JSONObject().put("model", "jev-1.14.0").put("state", state).put("questions", questions);
            return new JSONObject(Net.request("https://api.typesafe.ai/v1/systemone", body.toString(), ts));
        }
        String or = Prefs.apiKey(c);
        if (or.isEmpty()) return null;
        JSONObject body = new JSONObject().put("model", "typesafe/jev-1.14").put("state", state).put("questions", questions);
        return new JSONObject(Net.request("https://openrouter.ai/api/alpha/decisions", body.toString(), or));
    }

    /**
     * Jev 1.14 looking at a full-quality screenshot (about 1.5 s): one request finds the highlighted item, the
     * item to reach, and what kind of step is needed. The arrow presses are worked out from the two boxes, so
     * no language model is involved. Null only when text has to be typed (or Jev fails): the caller asks DeepSeek.
     */
    private static JSONObject jevFromScreenshot(Context c, String goal, List<String> history) {
        try {
            long t0 = System.currentTimeMillis();
            byte[] png = Injector.query(c, "shotfull");
            if (png == null || png.length == 0) return null;
            long t1 = System.currentTimeMillis();
            String appName = curApp.isEmpty() ? "an Android TV app" : Apps.labelOf(c, curApp, curApp);
            StringBuilder steps = new StringBuilder();
            for (int i = 0; i < history.size(); i++) steps.append(i + 1).append(". ").append(history.get(i)).append('\n');
            JSONArray state = new JSONArray()
                    .put("Goal the viewer asked for: " + goal)
                    .put("App in front: " + appName + (curApp.isEmpty() ? "" : " (" + curApp + ")") + ".")
                    .put(TV_RULES)
                    .put(appHints(curApp))
                    .put("Remote keys pressed so far for this goal, oldest first:\n"
                            + (steps.length() == 0 ? "none yet, this is the first look\n" : steps.toString())
                            + "OK was pressed on something: " + (pressedOk(history) ? "yes" : "no") + ".");
            if (lastShot != null) {
                state.put("PREVIOUS screen, just before the last key press (compare it with the current screen to see "
                        + "where the highlight moved, or whether the last press changed anything):")
                        .put(image(lastShot));
            }
            state.put("CURRENT screen, now:").put(image(png));
            lastShot = png;
            JSONObject criteria = new JSONObject()
                    .put("select", "Something visible on the current screen has to be highlighted and pressed with OK "
                            + "(the controls are showing, or a menu, list or button row is on screen)")
                    .put("done", "The goal's result is plainly visible on the current screen: for captions, caption text "
                            + "is drawn over the video or the CC button shows as switched on; for a setting, it shows as "
                            + "on; for a video, it is playing full screen. Highlighting the right button is NOT done, "
                            + "and OK has to have been pressed on something")
                    .put("wait", "The current screen is loading, blank, black, or showing an intro or advert")
                    .put("type", "A search box or on-screen keyboard is open and the goal needs text typed into it")
                    .put("reveal", "A video is playing full screen with no controls, buttons or menus visible, and they "
                            + "are needed for the goal");
            JSONObject questions = new JSONObject()
                    .put("status", new JSONObject().put("type", "choice")
                            .put("instructions", "Looking at the CURRENT screen, which kind of step brings the TV closer to "
                                    + "the goal? Never buy, rent, subscribe or change account settings.")
                            .put("criteria", criteria))
                    .put("focus", new JSONObject().put("type", "box").put("instructions",
                            "On the CURRENT screen: the one item that has the remote's focus, the item OK would press "
                                    + "right now. It is drawn differently from the items around it: white or filled "
                                    + "background where the others are dark, an outline or glow, a larger size, or brighter "
                                    + "text. If the previous screen is shown, the focus is usually the item that changed "
                                    + "appearance after the last arrow press."))
                    .put("target", new JSONObject().put("type", "box").put("instructions",
                            "On the CURRENT screen: the button, icon or item that should be pressed with OK next to reach "
                                    + "the goal (for example the CC button to turn on captions)."))
                    .put("move", new JSONObject().put("type", "choice").put("instructions",
                            "On the CURRENT screen, find the focused item and the item that should be pressed next for the "
                                    + "goal. Which single remote key moves the focus one step closer to that item, or "
                                    + "presses it?")
                            .put("criteria", new JSONObject()
                                    .put("ok", "The focused item IS the item to press: press OK")
                                    .put("left", "The item to press is further left than the focused item")
                                    .put("right", "The item to press is further right than the focused item")
                                    .put("up", "The item to press is higher up than the focused item")
                                    .put("down", "The item to press is lower down than the focused item")));
            JSONObject res = jev(c, state, questions);
            if (res == null) return null;
            JSONObject ans = res.getJSONObject("answers");
            JSONObject status = ans.getJSONObject("status");
            Log.i(TAG, "jev screen: shot " + (t1 - t0) + " ms (" + png.length / 1024 + " KB), jev "
                    + (System.currentTimeMillis() - t1) + " ms: " + ans);
            // Jev's best guess beats handing over: DeepSeek's vision takes 8-10 s a step and is no more accurate
            // here. Only "done" needs to be clear-cut; an unsure one means keep going (often: press OK on it).
            String next = status.getString("choice");
            // Moving the highlight never finishes anything: after the first look, "done" needs an OK press first
            // (a busy picture, like a video full of text, can look like a finished goal).
            if (next.equals("done") && (status.optDouble("confidence", 0) < 0.7 || (!history.isEmpty() && !pressedOk(history)))) {
                next = "select";
            }
            switch (next) {
                case "done":
                    return new JSONObject().put("action", "done").put("playing", true);
                case "wait":
                    return new JSONObject().put("action", "wait");
                case "reveal":
                    return new JSONObject().put("action", "key").put("key", "up");
                case "type":
                    return null;
                default:
                    break;
            }
            JSONObject f = ans.getJSONObject("focus"), t = ans.getJSONObject("target");
            JSONObject move = ans.getJSONObject("move");
            boolean boxesSure = f.optDouble("confidence", 0) >= 0.3 && t.optDouble("confidence", 0) >= 0.3;
            if (!boxesSure) {
                // The highlight is hard to box on some screens; Jev's direct sense of direction still works.
                return new JSONObject().put("action", "key").put("key", move.getString("choice"));
            }
            JSONObject fb = f.getJSONObject("box"), tb = t.getJSONObject("box");
            // Boxes are fractions of the screen; compare in 16:9 units so up/down and left/right weigh the same.
            double fx = (fb.getDouble("left") + fb.getDouble("right")) / 2 * 16, fy = (fb.getDouble("top") + fb.getDouble("bottom")) / 2 * 9;
            double tx = (tb.getDouble("left") + tb.getDouble("right")) / 2 * 16, ty = (tb.getDouble("top") + tb.getDouble("bottom")) / 2 * 9;
            boolean onIt = inside(tx / 16, ty / 9, fb) || inside(fx / 16, fy / 9, tb);
            if (onIt) return new JSONObject().put("action", "key").put("key", "ok");
            double dx = tx - fx, dy = ty - fy;
            boolean horizontal = Math.abs(dx) >= Math.abs(dy);
            // How many items lie between, judging by the size of the focused one; capped, then looked at again.
            double item = horizontal ? (fb.getDouble("right") - fb.getDouble("left")) * 16 : (fb.getDouble("bottom") - fb.getDouble("top")) * 9;
            int times = (int) Math.max(1, Math.min(3, Math.round(Math.abs(horizontal ? dx : dy) / Math.max(item * 1.3, 0.4))));
            String key = horizontal ? (dx > 0 ? "right" : "left") : (dy > 0 ? "down" : "up");
            return new JSONObject().put("action", "key").put("key", key).put("times", times);
        } catch (Exception e) {
            Log.w(TAG, "jev screenshot failed, using the vision model", e);
            return null;
        }
    }

    /** What every TV screen has in common, for Jev. */
    private static final String TV_RULES = "How this TV works: it has no mouse or touch, only a D-pad remote with up, down, "
            + "left, right, OK and back. Exactly one item on screen has the focus, and OK presses that item. An arrow key "
            + "moves the focus to the nearest item in that direction, one item per press. In a video player the "
            + "progress bar and its row of buttons hide a few seconds after the last key press; pressing Up brings them "
            + "back without changing anything, while OK on a player with hidden controls pauses the video.";

    /** What is known about the layout of the apps on this TV, so Jev knows where things live. */
    private static String appHints(String pkg) {
        switch (pkg) {
            case YouTube.PKG:
                return "YouTube player layout: under the progress bar is one row of round buttons, left to right: channel "
                        + "picture, Description, Subscribe, Previous, Play/Pause (in the middle), Next, Like, Dislike, "
                        + "Comments, Ask, Save, Thanks, CC (captions, near the right end), Settings (gear, at the far right). "
                        + "When the controls first appear the focus is on Play/Pause. CC switches captions on and off "
                        + "directly; when they are on, the CC button looks filled or underlined and caption text appears "
                        + "near the bottom of the video. Below the button row are suggested videos.";
            case "com.netflix.ninja":
                return "Netflix player: pressing Up or Down shows the controls; a speech-bubble 'Audio & Subtitles' button "
                        + "sits in the row of controls and opens a list of audio tracks and subtitle languages.";
            case "com.amazon.amazonvideo.livingroom":
                return "Prime Video player: pressing Up shows the controls; the speech-bubble 'Subtitles and Audio' "
                        + "button is at the top right and opens subtitle and audio language lists.";
            case "in.startv.hotstar":
                return "JioHotstar player: pressing Up or Down shows the controls; a settings or 'Audio & Subtitles' icon in "
                        + "the controls row opens language and subtitle choices.";
            case Spotify.PKG:
                return "Spotify: a top bar with Home, Search and Your Library; the now-playing screen shows the song with "
                        + "previous, play/pause and next buttons below it.";
            default:
                return "No layout notes for this app.";
        }
    }

    private static boolean pressedOk(List<String> history) {
        for (String h : history) if (h.startsWith("select") || h.equals("key ok")) return true;
        return false;
    }

    private static JSONObject image(byte[] jpg) throws Exception {
        return new JSONObject().put("type", "image_url").put("image_url",
                new JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(jpg, Base64.NO_WRAP)));
    }

    private static boolean inside(double x, double y, JSONObject box) throws Exception {
        return x >= box.getDouble("left") && x <= box.getDouble("right") && y >= box.getDouble("top") && y <= box.getDouble("bottom");
    }

    private static JSONObject decideFromScreenshot(Context c, String goal, List<String> history) throws Exception {
        JSONObject fast = jevFromScreenshot(c, goal, history);
        if (fast != null) return fast;
        byte[] jpg = Injector.query(c, "shot");
        if (jpg == null || jpg.length == 0) return new JSONObject().put("action", "wait");
        String user = "Goal: " + goal + "\nSteps so far: " + history
                + "\nThis is the TV screen. The focused item is usually highlighted, enlarged or outlined. "
                + "There are no element ids here, so use key actions (or type when a search field is open).";
        return Ai.json(c, "deepseek/deepseek-v4-flash-vision-exp", AGENT_RULES, user,
                "data:image/jpeg;base64," + Base64.encodeToString(jpg, Base64.NO_WRAP), 900);
    }

    // ---- moving the focus ------------------------------------------------------------------------

    /**
     * D-pad steps from the focused element towards the target, estimated from the size of the focused tile,
     * then OK once the target holds the focus. Re-reads the screen after each burst of presses.
     */
    private static void moveTo(Context c, List<El> els, El target) {
        for (int round = 0; round < 6; round++) {
            El focused = null;
            for (El e : els) if (e.focused) focused = e;
            if (focused == null || contains(target.r, focused.r) || contains(focused.r, target.r)) {
                press(c, "ok", 1);
                sleep(1500);
                return;
            }
            int dx = target.r.centerX() - focused.r.centerX(), dy = target.r.centerY() - focused.r.centerY();
            if (Math.abs(dy) > focused.r.height() / 2) {
                press(c, dy > 0 ? "down" : "up", Math.max(1, Math.round(Math.abs(dy) / (float) Math.max(40, focused.r.height()))));
            } else {
                press(c, dx > 0 ? "right" : "left", Math.max(1, Math.round(Math.abs(dx) / (float) Math.max(40, focused.r.width()))));
            }
            sleep(500);
            els = elements(c);
            El again = null;
            for (El e : els) if (sameElement(e, target)) again = e;
            if (again == null) return;
            target = again;
        }
    }

    private static boolean contains(Rect outer, Rect inner) {
        return outer.contains(inner.centerX(), inner.centerY()) && Math.abs(outer.width() - inner.width()) < outer.width();
    }

    private static boolean sameElement(El a, El b) {
        return a.text.equals(b.text) && a.desc.equals(b.desc) && a.res.equals(b.res);
    }

    private static void press(Context c, String key, int times) {
        int code;
        switch (key.toLowerCase(Locale.ROOT)) {
            case "up": code = KeyEvent.KEYCODE_DPAD_UP; break;
            case "down": code = KeyEvent.KEYCODE_DPAD_DOWN; break;
            case "left": code = KeyEvent.KEYCODE_DPAD_LEFT; break;
            case "right": code = KeyEvent.KEYCODE_DPAD_RIGHT; break;
            case "back": code = KeyEvent.KEYCODE_BACK; break;
            default: code = KeyEvent.KEYCODE_DPAD_CENTER;
        }
        for (int i = 0; i < Math.min(times, 8); i++) {
            Injector.key(c, code);
            sleep(160);
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
