package com.ayush.armour;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Philips WiZ bulbs on the home Wi-Fi, controlled directly over their local UDP API (port 38899): no cloud,
 * no Google Home in between, about a tenth of a second per change. Bulbs are found by a broadcast plus a
 * sweep of the subnet, remembered, and looked for again when one stops answering.
 */
final class Lights {
    private static final String TAG = "Armour";
    private static final int PORT = 38899;
    /** What the bulbs were doing before a scene changed them, so "normal mode" can put it back. */
    private static JSONArray saved;

    private Lights() {
    }

    static List<String> bulbs(Context c) {
        List<String> out = new ArrayList<>();
        for (String ip : Prefs.get(c).getString("wiz_ips", "").split(",")) if (!ip.trim().isEmpty()) out.add(ip.trim());
        return out;
    }

    /**
     * Blocking, up to ten seconds. Finds every bulb on the network and remembers them. A broadcast first;
     * then every address is asked directly in small batches (some routers drop broadcasts between Wi-Fi
     * clients, and a flood of 254 at once overflows the TV's address lookups). Bulbs doze between packets,
     * so the first answer can take a few hundred milliseconds.
     */
    static List<String> discover(Context c) {
        List<String> found = new ArrayList<>();
        String self = MainActivity.ipAddress();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(250);
            byte[] ask = "{\"method\":\"getPilot\",\"params\":{}}".getBytes();
            byte[] buf = new byte[2048];
            List<InetAddress> waves = new ArrayList<>();
            waves.add(InetAddress.getByName("255.255.255.255"));
            String base = self != null && self.contains(".") ? self.substring(0, self.lastIndexOf('.') + 1) : null;
            if (base != null) waves.add(InetAddress.getByName(base + "255"));
            for (String ip : bulbs(c)) waves.add(InetAddress.getByName(ip));
            for (InetAddress t : waves) s.send(new DatagramPacket(ask, ask.length, t, PORT));
            listen(s, buf, found, 1500);
            if (base != null) {
                for (int start = 1; start < 255; start += 32) {
                    for (int i = start; i < Math.min(255, start + 32); i++) {
                        if ((base + i).equals(self)) continue;
                        s.send(new DatagramPacket(ask, ask.length, InetAddress.getByName(base + i), PORT));
                    }
                    listen(s, buf, found, 600);
                }
                // Second round for bulbs that were still waking up.
                for (int i = 1; i < 255; i++) {
                    if ((base + i).equals(self) || found.contains(base + i)) continue;
                    s.send(new DatagramPacket(ask, ask.length, InetAddress.getByName(base + i), PORT));
                    if (i % 32 == 0) listen(s, buf, found, 300);
                }
                listen(s, buf, found, 1500);
            }
        } catch (Exception e) {
            Log.w(TAG, "wiz discovery failed", e);
        }
        if (!found.isEmpty()) Prefs.get(c).edit().putString("wiz_ips", String.join(",", found)).apply();
        Log.i(TAG, "wiz bulbs: " + found);
        return found.isEmpty() ? bulbs(c) : found;
    }

    private static void listen(DatagramSocket s, byte[] buf, List<String> found, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                String ip = p.getAddress().getHostAddress();
                if (!found.contains(ip) && new String(buf, 0, p.getLength()).contains("\"result\"")) found.add(ip);
            } catch (Exception ignored) {
            }
        }
    }

    private static List<String> known(Context c) {
        List<String> b = bulbs(c);
        return b.isEmpty() ? discover(c) : b;
    }

    /** Sends {@code params} as setPilot to every bulb at once. Blocking, well under a second. */
    private static boolean set(Context c, JSONObject params) {
        List<String> ips = known(c);
        if (ips.isEmpty()) return false;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, ips.size()));
        final int[] ok = {0};
        for (String ip : ips) {
            pool.execute(() -> {
                try {
                    JSONObject r = call(ip, new JSONObject().put("method", "setPilot").put("params", params));
                    if (r != null) synchronized (ok) {
                        ok[0]++;
                    }
                } catch (Exception ignored) {
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(4, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        // A bulb that did not answer may have a new address: look again next time.
        if (ok[0] < ips.size()) Net.IO.execute(() -> discover(c));
        return ok[0] > 0;
    }

    /** One request and its reply; sent up to three times since UDP can drop a packet. */
    private static JSONObject call(String ip, JSONObject msg) throws Exception {
        try (DatagramSocket s = new DatagramSocket()) {
            // Dozing bulbs can take a few hundred milliseconds to answer the first packet.
            s.setSoTimeout(900);
            byte[] out = msg.toString().getBytes();
            InetAddress a = InetAddress.getByName(ip);
            byte[] buf = new byte[2048];
            for (int attempt = 0; attempt < 3; attempt++) {
                s.send(new DatagramPacket(out, out.length, a, PORT));
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    return new JSONObject(new String(buf, 0, p.getLength()));
                } catch (java.net.SocketTimeoutException ignored) {
                }
            }
            return null;
        }
    }

    static boolean color(Context c, int rgb, int brightness) {
        try {
            return set(c, new JSONObject().put("state", true).put("r", (rgb >> 16) & 255).put("g", (rgb >> 8) & 255)
                    .put("b", rgb & 255).put("dimming", dim(brightness)));
        } catch (Exception e) {
            return false;
        }
    }

    /** White light by colour temperature: 2200 K candle-warm to 6500 K daylight. */
    static boolean white(Context c, int kelvin, int brightness) {
        try {
            return set(c, new JSONObject().put("state", true).put("temp", Math.max(2200, Math.min(6500, kelvin)))
                    .put("dimming", dim(brightness)));
        } catch (Exception e) {
            return false;
        }
    }

    static boolean power(Context c, boolean on) {
        try {
            return set(c, new JSONObject().put("state", on));
        } catch (Exception e) {
            return false;
        }
    }

    /** WiZ accepts 10..100. */
    private static int dim(int brightness) {
        return Math.max(10, Math.min(100, brightness <= 0 ? 100 : brightness));
    }

    /**
     * The assistant's light action: a colour name or #rrggbb, "warm", "white", "cool", "on" or "off", with an
     * optional brightness. Returns what was done, for the phone.
     */
    static String apply(Context c, String what, int brightness) {
        String w = what == null ? "" : what.trim().toLowerCase(Locale.ROOT);
        boolean ok;
        switch (w) {
            case "off": ok = power(c, false); break;
            case "on": ok = brightness > 0 ? white(c, 2700, brightness) : power(c, true); break;
            case "warm": case "warm white": ok = white(c, 2700, brightness); break;
            case "candle": ok = white(c, 2200, brightness); break;
            case "white": case "neutral": ok = white(c, 4200, brightness); break;
            case "cool": case "daylight": case "cool white": ok = white(c, 6500, brightness); break;
            default: {
                Integer rgb = rgb(w);
                if (rgb == null) return "I don't know the colour " + what + ".";
                ok = color(c, rgb, brightness);
            }
        }
        if (!ok) return "I couldn't reach the lights.";
        return w.equals("off") ? "Lights off." : "Lights " + w + (brightness > 0 ? " at " + brightness + "%" : "") + ".";
    }

    private static Integer rgb(String name) {
        if (name.matches("#?[0-9a-f]{6}")) return Integer.parseInt(name.replace("#", ""), 16);
        switch (name) {
            case "red": return 0xFF0000;
            case "crimson": case "dark red": return 0xC0001A;
            case "orange": return 0xFF6A00;
            case "amber": case "gold": return 0xFFB000;
            case "yellow": return 0xFFE000;
            case "green": return 0x00FF30;
            case "teal": case "cyan": return 0x00E0E0;
            case "blue": return 0x0040FF;
            case "purple": case "violet": return 0x8A00FF;
            case "pink": case "magenta": return 0xFF1E8C;
            default: return null;
        }
    }

    /** Remembers every bulb's current setting, once per scene (a second scene keeps the first snapshot). */
    static void save(Context c) {
        if (saved == null) saved = stored(c);
        if (saved != null && saved.length() > 0) return;
        JSONArray all = new JSONArray();
        for (String ip : known(c)) {
            try {
                JSONObject r = call(ip, new JSONObject().put("method", "getPilot").put("params", new JSONObject()));
                if (r != null) all.put(new JSONObject().put("ip", ip).put("pilot", r.getJSONObject("result")));
            } catch (Exception ignored) {
            }
        }
        if (all.length() > 0) {
            saved = all;
            // Kept on disk too: the setting to go back to must survive the app restarting mid-scene.
            Prefs.get(c).edit().putString("wiz_saved", all.toString()).apply();
        }
    }

    private static JSONArray stored(Context c) {
        try {
            String j = Prefs.get(c).getString("wiz_saved", "");
            return j.isEmpty() ? null : new JSONArray(j);
        } catch (Exception e) {
            return null;
        }
    }

    /** Puts the bulbs back the way {@link #save} found them. */
    static void restore(Context c) {
        JSONArray s = saved != null ? saved : stored(c);
        saved = null;
        Prefs.get(c).edit().remove("wiz_saved").apply();
        if (s == null) return;
        for (int i = 0; i < s.length(); i++) {
            try {
                JSONObject e = s.getJSONObject(i), p = e.getJSONObject("pilot"), params = new JSONObject();
                params.put("state", p.optBoolean("state", true));
                if (p.has("r")) params.put("r", p.getInt("r")).put("g", p.getInt("g")).put("b", p.getInt("b"));
                else if (p.optInt("sceneId") > 0) params.put("sceneId", p.getInt("sceneId"));
                else if (p.has("temp")) params.put("temp", p.getInt("temp"));
                if (p.has("dimming")) params.put("dimming", p.getInt("dimming"));
                call(e.getString("ip"), new JSONObject().put("method", "setPilot").put("params", params));
            } catch (Exception ignored) {
            }
        }
    }

    /** "1 bulb · warm white 28%" for the welcome dashboard, or null when there are none. Blocking. */
    static String status(Context c) {
        List<String> ips = bulbs(c);
        if (ips.isEmpty()) return null;
        try {
            JSONObject r = call(ips.get(0), new JSONObject().put("method", "getPilot").put("params", new JSONObject()));
            if (r == null) return ips.size() + (ips.size() == 1 ? " bulb" : " bulbs") + " · not answering";
            JSONObject p = r.getJSONObject("result");
            String what = !p.optBoolean("state", true) ? "off"
                    : p.has("r") ? "colour" : p.optInt("temp", 0) <= 3000 ? "warm white" : "white";
            return ips.size() + (ips.size() == 1 ? " bulb" : " bulbs") + " · " + what
                    + (p.optBoolean("state", true) ? " " + p.optInt("dimming", 100) + "%" : "");
        } catch (Exception e) {
            return null;
        }
    }
}
