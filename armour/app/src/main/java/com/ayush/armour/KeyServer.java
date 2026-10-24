package com.ayush.armour;

import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Not part of the app's own process: started through adb as
 * {@code CLASSPATH=<apk> app_process / com.ayush.armour.KeyServer <secret>}, so it runs as the shell user,
 * which holds INJECT_EVENTS. It listens on 127.0.0.1:7777 and turns lines like "key 19" or "text hello"
 * into real key presses in whatever app is in front. Every connection must start with the secret.
 */
public final class KeyServer {
    static final int PORT = 7777;
    private static Object inputManager;
    private static Method inject;

    private KeyServer() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) return;
        String secret = args[0];
        Looper.prepareMainLooper();
        Class<?> cls;
        try {
            cls = Class.forName("android.hardware.input.InputManagerGlobal");   // Android 14+
        } catch (ClassNotFoundException e) {
            cls = Class.forName("android.hardware.input.InputManager");
        }
        inputManager = cls.getMethod("getInstance").invoke(null);
        inject = cls.getMethod("injectInputEvent", InputEvent.class, int.class);

        ServerSocket server;
        try {
            server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
        } catch (Exception alreadyRunning) {
            return;
        }
        while (true) {
            Socket s = server.accept();
            new Thread(() -> serve(s, secret)).start();
        }
    }

    private static void serve(Socket s, String secret) {
        try (Socket sock = s) {
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = sock.getOutputStream();
            if (!secret.equals(in.readLine())) return;
            String line;
            while ((line = in.readLine()) != null) {
                String reply = "ok";
                try {
                    if (line.equals("ping")) reply = "pong";
                    else if (line.equals("quit")) System.exit(0);
                    else if (line.startsWith("key ")) key(Integer.parseInt(line.substring(4).trim()), false);
                    else if (line.startsWith("longkey ")) key(Integer.parseInt(line.substring(8).trim()), true);
                    else if (line.startsWith("text ")) text(line.substring(5));
                    else if (line.equals("dump")) reply = "data " + b64(uiDump());
                    else if (line.equals("shot")) reply = "data " + b64(screenshot());
                    else if (line.equals("shotfull")) reply = "data " + b64(screenshotFull());
                    else if (line.equals("playing")) reply = "data " + b64(playing().getBytes(StandardCharsets.UTF_8));
                    else if (line.equals("front")) reply = "data " + b64(front().getBytes(StandardCharsets.UTF_8));
                    else reply = "unknown";
                } catch (Exception e) {
                    reply = "error " + e;
                }
                out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private static void key(int code, boolean longPress) throws Exception {
        long now = SystemClock.uptimeMillis();
        send(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD));
        if (longPress) {
            Thread.sleep(600);
            send(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, 1, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_LONG_PRESS,
                    InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD));
        }
        send(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD));
    }

    private static void text(String t) throws Exception {
        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(t.toCharArray());
        if (events == null) return;
        for (KeyEvent e : events) send(KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0));
    }

    /** The on-screen view tree of whatever app is in front, as uiautomator XML. */
    private static byte[] uiDump() throws Exception {
        String path = "/data/local/tmp/armour-ui.xml";
        run("uiautomator", "dump", "--compressed", path);
        return java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
    }

    /** The screen as a 640-wide JPEG, small enough to send to a vision model quickly. */
    private static byte[] screenshot() throws Exception {
        android.graphics.Bitmap full = capture();
        if (full == null) return new byte[0];
        int w = 640, h = full.getHeight() * 640 / full.getWidth();
        android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(full, w, h, true);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out);
        return out.toByteArray();
    }

    /** The whole screen at full size for Jev's boxes (it reads up to 1568 px a side), as a near-lossless JPEG. */
    private static byte[] screenshotFull() throws Exception {
        android.graphics.Bitmap full = capture();
        if (full == null) return new byte[0];
        if (full.getWidth() > 1568) {
            full = android.graphics.Bitmap.createScaledBitmap(full, 1568, full.getHeight() * 1568 / full.getWidth(), true);
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        full.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out);
        return out.toByteArray();
    }

    /**
     * Raw screencap (a third of the time of screencap -p on this TV): a 16-byte header (width, height,
     * format, colour space) then RGBA pixels. The vendor library prints a line after the pixels; it is ignored.
     */
    private static android.graphics.Bitmap capture() throws Exception {
        byte[] raw = run("screencap");
        if (raw.length < 16) return null;
        java.nio.ByteBuffer hdr = java.nio.ByteBuffer.wrap(raw, 0, 16).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int w = hdr.getInt(), h = hdr.getInt(), format = hdr.getInt();
        int size = w * h * 4;
        if (format != 1 || w <= 0 || h <= 0) {
            byte[] png = run("screencap", "-p");
            return android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
        }
        int offset = raw.length >= 16 + size ? 16 : 12;
        if (raw.length < offset + size) return null;
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(raw, offset, size));
        return b;
    }

    /** The package of the activity in front, or "". */
    private static String front() throws Exception {
        String dump = new String(run("dumpsys", "activity", "activities"), StandardCharsets.UTF_8);
        int i = dump.indexOf("topResumedActivity=");
        if (i < 0) return "";
        String rest = dump.substring(i);
        int u = rest.indexOf(" u0 ");
        if (u < 0) return "";
        String comp = rest.substring(u + 4).split("[ }]")[0];
        return comp.contains("/") ? comp.substring(0, comp.indexOf('/')) : comp;
    }

    /** Packages whose media session is playing right now: how the agent knows a film has started. */
    private static String playing() throws Exception {
        String dump = new String(run("dumpsys", "media_session"), StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        String pkg = null;
        for (String l : dump.split("\n")) {
            l = l.trim();
            int i = l.indexOf("package=");
            if (i >= 0) pkg = l.substring(i + 8).split("[ ,]")[0];
            if (pkg != null && (l.contains("{state=PLAYING") || l.contains("{state=3,") || l.contains("{state=3 "))) {
                if (out.indexOf(pkg) < 0) out.append(out.length() > 0 ? "," : "").append(pkg);
            }
        }
        return out.toString();
    }

    private static byte[] run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        java.io.InputStream in = p.getInputStream();
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        p.waitFor();
        return b.toByteArray();
    }

    private static String b64(byte[] data) {
        return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
    }

    private static void send(KeyEvent e) throws Exception {
        inject.invoke(inputManager, e, 0);
    }
}
