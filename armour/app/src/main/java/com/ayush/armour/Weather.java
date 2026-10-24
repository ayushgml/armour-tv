package com.ayush.armour;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * Weather and air quality for the city in Prefs, from Open-Meteo (no API key). Cached for 30 minutes so
 * the home screen never waits on the network.
 */
final class Weather {
    static final class Now {
        int temp = Integer.MIN_VALUE;
        /** Today's sunrise and sunset, minutes after midnight (rough defaults until the first fetch). */
        int sunrise = 6 * 60 + 10, sunset = 18 * 60 + 10;
        int high, low, aqi = -1;
        String sky = "";
        /** Next hours: temperature and rain chance, for the briefing and the assistant card. */
        int[] hourTemp = new int[0];
        int[] hourRain = new int[0];
        long fetched;

        boolean ok() {
            return temp != Integer.MIN_VALUE;
        }

        String aqiWord() {
            if (aqi < 0) return "";
            if (aqi <= 50) return "good";
            if (aqi <= 100) return "moderate";
            if (aqi <= 150) return "poor for sensitive groups";
            if (aqi <= 200) return "unhealthy";
            return "very unhealthy";
        }

        /** "28° → 23°, no rain" over the next six hours. */
        String tonight() {
            if (hourTemp.length < 6) return temp + "°";
            int maxRain = 0;
            for (int i = 0; i < 6; i++) maxRain = Math.max(maxRain, hourRain[i]);
            return hourTemp[0] + "° → " + hourTemp[5] + "°, " + (maxRain >= 40 ? "rain likely (" + maxRain + "%)" : "no rain");
        }
    }

    private static Now cache;

    private Weather() {
    }

    static Now cached(Context c) {
        if (cache == null) {
            cache = new Now();
            String s = Prefs.get(c).getString("weather", "");
            if (!s.isEmpty()) {
                try {
                    parse(cache, new JSONObject(s));
                    cache.fetched = Prefs.get(c).getLong("weather_t", 0);
                    JSONObject aq = new JSONObject(Prefs.get(c).getString("aqi", "{}"));
                    if (aq.has("current")) cache.aqi = aq.getJSONObject("current").optInt("us_aqi", -1);
                } catch (Exception ignored) {
                }
            }
        }
        return cache;
    }

    /** Refreshes when older than 30 minutes; blocking, call off the main thread. */
    static Now refresh(Context c) {
        Now now = cached(c);
        if (System.currentTimeMillis() - now.fetched < 30 * 60_000L && now.ok()) return now;
        try {
            String w = Net.get("https://api.open-meteo.com/v1/forecast?latitude=" + Prefs.LAT + "&longitude=" + Prefs.LON
                    + "&current=temperature_2m,weather_code&hourly=temperature_2m,precipitation_probability"
                    + "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset&timezone=" + android.net.Uri.encode(BuildConfig.TIMEZONE) + "&forecast_days=2");
            String a = Net.get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + Prefs.LAT
                    + "&longitude=" + Prefs.LON + "&current=us_aqi&timezone=" + android.net.Uri.encode(BuildConfig.TIMEZONE));
            Now fresh = new Now();
            parse(fresh, new JSONObject(w));
            fresh.aqi = new JSONObject(a).getJSONObject("current").optInt("us_aqi", -1);
            fresh.fetched = System.currentTimeMillis();
            Prefs.get(c).edit().putString("weather", w).putString("aqi", a).putLong("weather_t", fresh.fetched).apply();
            cache = fresh;
        } catch (Exception ignored) {
            // Offline: keep showing the last reading.
        }
        return cache;
    }

    private static void parse(Now n, JSONObject j) throws Exception {
        JSONObject cur = j.getJSONObject("current");
        n.temp = (int) Math.round(cur.getDouble("temperature_2m"));
        n.sky = sky(cur.optInt("weather_code", 0));
        JSONObject d = j.getJSONObject("daily");
        n.high = (int) Math.round(d.getJSONArray("temperature_2m_max").getDouble(0));
        n.low = (int) Math.round(d.getJSONArray("temperature_2m_min").getDouble(0));
        if (d.has("sunrise")) {
            n.sunrise = minutes(d.getJSONArray("sunrise").getString(0), n.sunrise);
            n.sunset = minutes(d.getJSONArray("sunset").getString(0), n.sunset);
        }
        JSONObject h = j.getJSONObject("hourly");
        JSONArray times = h.getJSONArray("time");
        JSONArray temps = h.getJSONArray("temperature_2m");
        JSONArray rain = h.getJSONArray("precipitation_probability");
        // Hourly series starts at midnight today; skip to the current hour.
        int start = Math.min(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), times.length() - 1);
        int count = Math.max(0, Math.min(12, times.length() - start));
        n.hourTemp = new int[count];
        n.hourRain = new int[count];
        for (int i = 0; i < count; i++) {
            n.hourTemp[i] = (int) Math.round(temps.getDouble(start + i));
            n.hourRain[i] = rain.isNull(start + i) ? 0 : rain.getInt(start + i);
        }
    }

    /** "2026-10-19T06:08" → 368. */
    private static int minutes(String iso, int fallback) {
        try {
            String t = iso.substring(iso.indexOf('T') + 1);
            return Integer.parseInt(t.substring(0, 2)) * 60 + Integer.parseInt(t.substring(3, 5));
        } catch (Exception e) {
            return fallback;
        }
    }

    /** WMO weather codes in plain words. */
    static String sky(int code) {
        if (code == 0) return "clear";
        if (code <= 2) return "partly cloudy";
        if (code == 3) return "cloudy";
        if (code <= 48) return "foggy";
        if (code <= 57) return "drizzle";
        if (code <= 67) return "rain";
        if (code <= 77) return "snow";
        if (code <= 82) return "showers";
        return "thunderstorms";
    }
}
