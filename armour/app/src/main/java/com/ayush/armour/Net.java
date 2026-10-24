package com.ayush.armour;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small HTTP helpers and one background thread pool for everything that touches the network. */
final class Net {
    static final ExecutorService IO = Executors.newFixedThreadPool(3);

    private Net() {
    }

    static String get(String url) throws Exception {
        return request(url, null, null);
    }

    static String request(String url, String body, String bearer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Armour-TV/1.0");
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-Title", "Armour TV");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String text = read(in);
        c.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code + ": " + text);
        return text;
    }

    static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }
}
