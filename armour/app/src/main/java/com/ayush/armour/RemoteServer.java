package com.ayush.armour;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.File;
import java.io.Closeable;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocket;

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
     * body and the file's path under {@link #UPLOAD} in the headers.
     */
    interface Uploads {
        java.io.File target(String path, Map<String, String> headers);
    }

    /** Header-map key for a saved upload's path. Header names end at the first ':', so no client can send it. */
    static final String UPLOAD = ":upload";

    interface Admission {
        Decision check(String method, String path, Map<String, String> headers, long length);
    }

    static final class Decision {
        final long limit;
        final boolean upload;
        final Response rejection;

        private Decision(long limit, boolean upload, Response rejection) {
            this.limit = limit;
            this.upload = upload;
            this.rejection = rejection;
        }

        static Decision allow(long limit, boolean upload) { return new Decision(limit, upload, null); }
        static Decision reject(int status) { return new Decision(0, false, error(status)); }
    }

    /** Package-private configuration also permits fast loopback regression tests. */
    static final class Limits {
        int connections = 8, headers = 64, lineBytes = 8192, headerBytes = 16384, requests = 100;
        int headerMs = 10_000, bodyMs = 30_000, uploadMs = 900_000, idleMs = 15_000;
        int uploadIdleMs = 30_000;
    }

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
    private final Admission admission;
    private final Limits limits;
    private final Semaphore connections;
    private final Semaphore uploadPermit = new Semaphore(1);
    private final Set<Socket> active = new HashSet<>();
    private final ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "remote-deadlines");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;
    private ServerSocket https, http;

    RemoteServer(Handler handler, Uploads uploads, Admission admission) {
        this(handler, uploads, admission, new Limits());
    }

    RemoteServer(Handler handler, Uploads uploads, Admission admission, Limits limits) {
        this.handler = handler;
        this.uploads = uploads;
        this.admission = admission;
        this.limits = limits;
        connections = new Semaphore(limits.connections);
        watchdog.setRemoveOnCancelPolicy(true);
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
        synchronized (active) {
            running = false;
            close(https);
            close(http);
            for (Socket socket : active) close(socket);
        }
        watchdog.shutdownNow();
    }

    private static void close(Closeable resource) {
        try { if (resource != null) resource.close(); } catch (IOException ignored) { }
    }

    private void acceptLoop(ServerSocket server, boolean secure) {
        while (running) {
            try { accept(server.accept(), secure); }
            catch (IOException e) { if (!running) return; }
        }
    }

    /** Shared admission point for both listeners; never queue accepted sockets. */
    void accept(Socket socket, boolean secure) {
        synchronized (active) {
            if (!running || !connections.tryAcquire()) {
                close(socket);
                return;
            }
            active.add(socket);
            try {
                new Thread(() -> {
                    try { serve(socket, secure); }
                    finally {
                        synchronized (active) { active.remove(socket); }
                        connections.release();
                    }
                }, "remote-conn").start();
            } catch (RuntimeException | Error e) {
                active.remove(socket);
                connections.release();
                close(socket);
                throw e;
            }
        }
    }

    /** Relative scheduler delays use monotonic time. Synchronization prevents a cancelled phase
     * from closing a socket after the next phase (or the handler) has begun. */
    private final class Deadline implements AutoCloseable {
        private boolean cancelled;
        private final ScheduledFuture<?> task;
        Deadline(Socket socket, int millis) {
            task = watchdog.schedule(() -> {
                synchronized (this) { if (!cancelled) RemoteServer.close(socket); }
            }, millis, TimeUnit.MILLISECONDS);
        }
        public synchronized void close() {
            cancelled = true;
            task.cancel(false);
        }
    }

    private static Response error(int status) {
        return Response.json(status, "{\"error\":\"Request rejected\"}");
    }

    private static void reject(OutputStream out, Response response) throws Exception {
        write(out, response.status, response.type, response.body, "Connection: close\r\n");
    }

    private static final class BadRequest extends IOException {
        final int status;
        BadRequest(int status) { this.status = status; }
    }

    /** HTTP/1.1 with bounded keep-alive for quick remote button taps. */
    private void serve(Socket socket, boolean secure) {
        try (Socket sock = socket) {
            if (sock instanceof SSLSocket) {
                try (Deadline deadline = new Deadline(sock, limits.headerMs)) {
                    sock.setSoTimeout(limits.headerMs);
                    ((SSLSocket) sock).startHandshake();
                }
            }
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            try {
                for (int request = 0; running && request < limits.requests; request++) {
                    sock.setSoTimeout(limits.idleMs);
                    int first = in.read();
                    if (first < 0) return;
                    String[] parts;
                    Map<String, String> headers = new HashMap<>();
                    long len;
                    try (Deadline deadline = new Deadline(sock, limits.headerMs)) {
                        sock.setSoTimeout(limits.headerMs);
                        String requestLine = readLine(in, first, new int[]{0}, limits.lineBytes);
                        parts = requestLine.split(" ");
                        if (parts.length != 3 || !parts[2].equals("HTTP/1.1")) throw new BadRequest(400);
                        int[] bytes = {0};
                        int count = 0;
                        while (true) {
                            String line = readLine(in, in.read(), bytes, limits.headerBytes);
                            if (line.isEmpty()) break;
                            if (++count > limits.headers) throw new BadRequest(431);
                            int colon = line.indexOf(':');
                            if (colon <= 0) throw new BadRequest(400);
                            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
                            if (!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+")) throw new BadRequest(400);
                            String value = line.substring(colon + 1).trim();
                            if (headers.containsKey(name) && name.equals("content-length")) throw new BadRequest(400);
                            headers.put(name, value);
                        }
                        if (headers.containsKey("transfer-encoding")) throw new BadRequest(400);
                        String length = headers.getOrDefault("content-length", "0");
                        if (!length.matches("[0-9]+")) throw new BadRequest(400);
                        try { len = Long.parseLong(length); }
                        catch (NumberFormatException e) { throw new BadRequest(400); }
                    }
                    if (!secure) {
                        String host = headers.getOrDefault("host", "").replaceAll(":\\d+$", "");
                        write(out, 301, "text/plain", new byte[0], "Connection: close\r\nLocation: https://" + host + ":" + HTTPS_PORT + "/\r\n");
                        return;
                    }
                    Decision decision = admission.check(parts[0], parts[1], headers, len);
                    if (decision.rejection != null) { reject(out, decision.rejection); return; }
                    if (len > decision.limit) { reject(out, error(413)); return; }
                    boolean uploading = decision.upload && len > 0;
                    if (uploading && !uploadPermit.tryAcquire()) { reject(out, error(409)); return; }
                    File upload = null;
                    try {
                        byte[] body;
                        try (Deadline deadline = new Deadline(sock, uploading ? limits.uploadMs : limits.bodyMs)) {
                            sock.setSoTimeout(uploading ? limits.uploadIdleMs : limits.bodyMs);
                            if (uploading) {
                                upload = uploads.target(parts[1], headers);
                                if (upload == null) { reject(out, error(401)); return; }
                                try (OutputStream file = new java.io.FileOutputStream(upload)) {
                                    byte[] buf = new byte[64 * 1024];
                                    long left = len;
                                    while (left > 0) {
                                        int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                                        if (n < 0) throw new BadRequest(400);
                                        file.write(buf, 0, n);
                                        left -= n;
                                    }
                                }
                                headers.put(UPLOAD, upload.getPath());
                                body = new byte[0];
                            } else {
                                if (len > 8 * 1024 * 1024) throw new BadRequest(413);
                                body = new byte[(int) len];
                                int read = 0;
                                while (read < body.length) {
                                    int n = in.read(body, read, body.length - read);
                                    if (n < 0) throw new BadRequest(400);
                                    read += n;
                                }
                            }
                        }
                        Response response;
                        try { response = handler.handle(parts[0], parts[1], headers, body); }
                        catch (Exception e) { response = error(500); }
                        boolean last = request + 1 == limits.requests || "close".equalsIgnoreCase(headers.get("connection"));
                        // Bound blocked response writes as well, without timing handler execution.
                        try (Deadline deadline = new Deadline(sock, limits.bodyMs)) {
                            write(out, response.status, response.type, response.body, last ? "Connection: close\r\n" : "");
                        }
                        if (last) return;
                    } finally {
                        // Successful handlers consume/rename the file. Remove anything left behind.
                        if (upload != null) upload.delete();
                        if (uploading) uploadPermit.release();
                    }
                }
            } catch (BadRequest e) { reject(out, error(e.status)); }
        } catch (Exception ignored) { }
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

    private String readLine(InputStream in, int first, int[] total, int maxTotal) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int c = first, count = 0, previous = -1;
        while (true) {
            if (c < 0) throw new BadRequest(400);
            if (++count > limits.lineBytes || ++total[0] > maxTotal) throw new BadRequest(431);
            if (c == '\n') {
                byte[] line = bytes.toByteArray();
                if (line.length == 0 || line[line.length - 1] != '\r') throw new BadRequest(400);
                return new String(line, 0, line.length - 1, StandardCharsets.UTF_8);
            }
            if (c < 32 && c != '\r' && c != '\t' || c == 127) throw new BadRequest(400);
            if (previous == '\r') throw new BadRequest(400);
            bytes.write(c);
            previous = c;
            c = in.read();
        }
    }
}
