package com.ayush.armour;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** App side of {@link KeyServer}: starts it through adb when it is not running and sends it key presses. */
final class Injector {
    private static Socket sock;
    private static OutputStream out;
    private static BufferedReader in;
    private static long lastStartAttempt;
    /** Shown on the phone page: why remote keys inside other apps are not working. */
    static volatile String status = "starting";

    private Injector() {
    }

    /** Sends one command ("key 19", "text hi"). Blocking, call off the main thread. */
    static synchronized boolean send(Context c, String cmd) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (sock == null) connect(c);
                out.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                String r = in.readLine();
                if (r != null && (r.equals("ok") || r.equals("pong"))) {
                    status = "ready";
                    return true;
                }
            } catch (Exception e) {
                close();
            }
            if (attempt == 0 && !start(c)) return false;
        }
        if (status.equals("started")) status = "injector did not start";
        return false;
    }

    /** Stops a KeyServer left over from an older install of the app and starts this version's. */
    static void restart(Context c) {
        Log.i("Armour", "injector: restart");
        try {
            if (sock == null) connect(c);
            out.write("quit\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
        }
        close();
        Agent.sleep(300);
        lastStartAttempt = 0;
        start(c);
    }

    static boolean key(Context c, int keyCode) {
        return send(c, "key " + keyCode);
    }

    /** "dump", "shot" or "playing": returns the decoded data, or null. Can take a few seconds. */
    static synchronized byte[] query(Context c, String cmd) {
        if (!send(c, "ping")) return null;
        try {
            sock.setSoTimeout(15000);
            out.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String r = in.readLine();
            sock.setSoTimeout(3000);
            if (r == null || !r.startsWith("data ")) return null;
            return android.util.Base64.decode(r.substring(5), android.util.Base64.DEFAULT);
        } catch (Exception e) {
            close();
            return null;
        }
    }

    private static void connect(Context c) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", KeyServer.PORT), 500);
        s.setSoTimeout(3000);
        sock = s;
        out = s.getOutputStream();
        in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        out.write((Prefs.injectorSecret(c) + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void close() {
        try {
            if (sock != null) sock.close();
        } catch (Exception ignored) {
        }
        sock = null;
    }

    /** Launches KeyServer as the shell user through the TV's own adb. Waits up to a minute for the prompt. */
    static synchronized boolean start(Context c) {
        if (System.currentTimeMillis() - lastStartAttempt < 5000) return false;
        lastStartAttempt = System.currentTimeMillis();
        String apk = c.getApplicationInfo().sourceDir;
        Log.i("Armour", "injector: starting KeyServer through adb");
        // setsid: its own session, so it survives the adb shell closing (a plain "&" is killed with it).
        String cmd = "setsid nohup sh -c 'CLASSPATH=" + apk + " exec app_process / com.ayush.armour.KeyServer "
                + Prefs.injectorSecret(c) + "' </dev/null >/data/local/tmp/armour-keys.log 2>&1 & sleep 2";
        // The trailing sleep keeps the adb session open until the server has detached: closing it at once
        // can take the half-started process down with it.
        try {
            status = "waiting for TV approval";
            String outText = Adb.shell(c, cmd, 60_000);
            Log.i("Armour", "injector: adb shell returned '" + outText.trim() + "'");
            Thread.sleep(800);   // app_process needs a moment before it listens
            status = "started";
            return true;
        } catch (java.net.ConnectException e) {
            status = "network debugging is off";
            Log.w("Armour", "injector: adb not reachable", e);
        } catch (Exception e) {
            status = "not allowed on the TV (" + e.getMessage() + ")";
            Log.w("Armour", "injector: adb start failed", e);
        }
        return false;
    }
}
