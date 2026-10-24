package com.ayush.armour;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

/**
 * A tiny HTTPS server for the phone page. HTTPS because phone browsers only allow the microphone on secure
 * pages; the certificate is self-signed, so the phone asks once to trust it. Port 8090 redirects to 8443.
 */
final class RemoteServer {
    static final int HTTPS_PORT = 8443, HTTP_PORT = 8090;

    interface Handler {
        Response handle(String method, String path, Map<String, String> headers, byte[] body);
    }

    /**
     * Where a file upload's body should go, or null to refuse it (not paired, or not an upload path). An
     * accepted body is streamed to that file instead of memory, and the handler is then called with an empty
     * body and the file's path in the "x-upload" header.
     */
    interface Uploads {
        java.io.File target(String path, Map<String, String> headers);
    }

    /** A presentation or video can be a few hundred megabytes. */
    private static final long MAX_UPLOAD = 1024L * 1024 * 1024;

    static final class Response {
        final int status;
        final String type;
        final byte[] body;

        Response(int status, String type, byte[] body) {
            this.status = status;
            this.type = type;
            this.body = body;
        }

        static Response json(int status, String json) {
            return new Response(status, "application/json", json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private final Handler handler;
    private final Uploads uploads;
    private volatile boolean running = true;
    private ServerSocket https, http;

    RemoteServer(Handler handler, Uploads uploads) {
        this.handler = handler;
        this.uploads = uploads;
    }

    void start(InputStream p12, char[] password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(p12, password);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);
        https = ssl.getServerSocketFactory().createServerSocket(HTTPS_PORT);
        ((SSLServerSocket) https).setUseClientMode(false);
        new Thread(() -> acceptLoop(https, true), "remote-https").start();
        // The plain-HTTP redirect is a convenience only; another TV app may already hold the port.
        try {
            http = new ServerSocket(HTTP_PORT);
            new Thread(() -> acceptLoop(http, false), "remote-http").start();
        } catch (Exception ignored) {
        }
    }

    void stop() {
        running = false;
        try {
            if (https != null) https.close();
            if (http != null) http.close();
        } catch (Exception ignored) {
        }
    }

    private void acceptLoop(ServerSocket server, boolean secure) {
        while (running) {
            try {
                Socket s = server.accept();
                new Thread(() -> serve(s, secure), "remote-conn").start();
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }

    /** HTTP/1.1 with keep-alive, so the phone's quick button taps reuse one TLS connection. */
    private void serve(Socket s, boolean secure) {
        try (Socket sock = s) {
            sock.setSoTimeout(60_000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            while (true) {
                String requestLine = readLine(in);
                if (requestLine == null || requestLine.isEmpty()) return;
                String[] parts = requestLine.split(" ");
                if (parts.length < 2) return;
                Map<String, String> headers = new HashMap<>();
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    int c = line.indexOf(':');
                    if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
                }
                long len = 0;
                try {
                    len = Long.parseLong(headers.getOrDefault("content-length", "0"));
                } catch (NumberFormatException ignored) {
                }
                java.io.File upload = secure && len > 0 && "POST".equals(parts[0]) ? uploads.target(parts[1], headers) : null;
                if (upload != null) {
                    if (len > MAX_UPLOAD) return;
                    sock.setSoTimeout(120_000);
                    try (OutputStream f = new java.io.FileOutputStream(upload)) {
                        byte[] buf = new byte[64 * 1024];
                        long left = len;
                        while (left > 0) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) return;
                            f.write(buf, 0, n);
                            left -= n;
                        }
                    }
                    headers.put("x-upload", upload.getPath());
                    len = 0;
                }
                if (len > 8 * 1024 * 1024) return;   // a thrown photo is at most a few hundred KB
                byte[] body = new byte[(int) len];
                int read = 0;
                while (read < len) {
                    int n = in.read(body, read, body.length - read);
                    if (n < 0) return;
                    read += n;
                }
                if (!secure) {
                    String host = headers.getOrDefault("host", "").replaceAll(":\\d+$", "");
                    write(out, 301, "text/plain", new byte[0], "Location: https://" + host + ":" + HTTPS_PORT + "/\r\n");
                    return;
                }
                Response r;
                try {
                    r = handler.handle(parts[0], parts[1], headers, body);
                } catch (Exception e) {
                    r = Response.json(500, "{\"error\":" + org.json.JSONObject.quote(String.valueOf(e.getMessage())) + "}");
                }
                write(out, r.status, r.type, r.body, "");
                if ("close".equalsIgnoreCase(headers.get("connection"))) return;
            }
        } catch (Exception ignored) {
        }
    }

    private static void write(OutputStream out, int status, String type, byte[] body, String extra) throws Exception {
        String head = "HTTP/1.1 " + status + " " + (status < 300 ? "OK" : status < 400 ? "Moved" : "Error") + "\r\n"
                + "Content-Type: " + type + "; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + extra + "\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) return null;
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }
}
