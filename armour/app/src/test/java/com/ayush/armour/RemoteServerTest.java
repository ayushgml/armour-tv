package com.ayush.armour;

import org.junit.After;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RemoteServerTest {
    private final List<RemoteServer> servers = new ArrayList<>();
    private final List<Socket> clients = new ArrayList<>();
    private final AtomicInteger handled = new AtomicInteger(), allocated = new AtomicInteger();
    private File upload;

    private RemoteServer server(RemoteServer.Limits limits) throws Exception {
        upload = Files.createTempFile("remote-upload", ".tmp").toFile();
        RemoteServer server = new RemoteServer((method, path, headers, body) -> {
            handled.incrementAndGet();
            return new RemoteServer.Response(200, "text/plain", body);
        }, (path, headers) -> { allocated.incrementAndGet(); return upload; },
            (method, path, headers, length) -> {
                if (path.equals("/pair")) return RemoteServer.Decision.allow(1024, false);
                if (!"valid".equals(headers.get("x-token"))) return RemoteServer.Decision.reject(401);
                return RemoteServer.Decision.allow(path.equals("/upload") ? 1024L * 1024 * 1024 : 8 * 1024 * 1024, path.equals("/upload"));
            }, limits);
        servers.add(server);
        return server;
    }
    private Socket connect(RemoteServer server, boolean secure) throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
            client.setSoTimeout(3000);
            clients.add(client);
            server.accept(listener.accept(), secure);
            return client;
        }
    }
    private void send(Socket socket, String request) throws Exception {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }
    private int response(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int state = 0, c;
        while ((c = in.read()) >= 0) {
            header.write(c);
            state = c == "\r\n\r\n".charAt(state) ? state + 1 : (c == '\r' ? 1 : 0);
            if (state == 4) break;
        }
        String text = header.toString("UTF-8");
        assertTrue(text, text.startsWith("HTTP/1.1 "));
        int length = 0;
        for (String line : text.split("\r\n")) if (line.startsWith("Content-Length: ")) length = Integer.parseInt(line.substring(16));
        for (int i = 0; i < length; i++) assertNotEquals(-1, in.read());
        return Integer.parseInt(text.split(" ")[1]);
    }
    private String request(String path, String extra, String body) {
        return "POST " + path + " HTTP/1.1\r\nHost: localhost\r\n" + extra + "\r\n" + body;
    }
    private void closed(Socket socket) throws Exception {
        try { assertEquals(-1, socket.getInputStream().read()); }
        catch (SocketException expected) { }
    }
    @After public void cleanup() throws Exception {
        for (RemoteServer server : servers) server.stop();
        for (Socket client : clients) client.close();
        if (upload != null) upload.delete();
    }
    @Test public void unauthorizedNeverReadsBodyOrAllocatesUpload() throws Exception {
        RemoteServer s = server(new RemoteServer.Limits());
        for (String path : Arrays.asList("/private", "/upload")) {
            Socket c = connect(s, true);
            send(c, request(path, "Content-Length: 8388608\r\n", ""));
            assertEquals(401, response(c)); closed(c);
        }
        assertEquals(0, handled.get()); assertEquals(0, allocated.get());
    }
    @Test public void pairingLimitsAndAuthenticatedBodies() throws Exception {
        RemoteServer s = server(new RemoteServer.Limits());
        Socket c = connect(s, true);
        send(c, request("/pair", "Content-Length: 2\r\n", "{}")); assertEquals(200, response(c));
        send(c, request("/private", "X-Token: valid\r\nContent-Length: 4\r\n", "data")); assertEquals(200, response(c));
        send(c, request("/pair", "Content-Length: 1025\r\n", "")); assertEquals(413, response(c)); closed(c);
    }
    @Test public void invalidFramingAndHeaders() throws Exception {
        RemoteServer s = server(new RemoteServer.Limits());
        for (String extra : Arrays.asList("Content-Length: -1\r\n", "Content-Length: nope\r\n", "Content-Length: 9223372036854775808\r\n", "Content-Length: 0\r\nContent-Length: 0\r\n", "Transfer-Encoding: chunked\r\n")) {
            Socket c = connect(s, true); send(c, request("/pair", extra, "")); assertEquals(400, response(c)); closed(c);
        }
        Socket c = connect(s, true);
        send(c, request("/pair", "X-Large: " + String.join("", Collections.nCopies(8192, "a")) + "\r\n", ""));
        assertEquals(431, response(c));
        Socket partial = connect(s, true); send(partial, "POST /pair HTTP/1.1\r\nHost: a"); partial.shutdownOutput(); assertEquals(400, response(partial));
        assertEquals(0, handled.get());
    }
    @Test public void redirectDoesNotReadBody() throws Exception {
        Socket c = connect(server(new RemoteServer.Limits()), false);
        send(c, request("/", "Content-Length: 8388608\r\n", "")); assertEquals(301, response(c)); closed(c);
    }
    @Test public void connectionCapIsSharedAndShutdownClosesSockets() throws Exception {
        RemoteServer s = server(new RemoteServer.Limits());
        List<Socket> held = new ArrayList<>();
        for (int i = 0; i < 8; i++) held.add(connect(s, i % 2 == 0));
        closed(connect(s, true));
        held.get(0).close();
        // Poll until the worker has released its permit, rather than assuming thread scheduling.
        boolean recovered = false;
        for (int i = 0; i < 100 && !recovered; i++) {
            Thread.sleep(10);
            Socket c = connect(s, true);
            try { send(c, request("/pair", "", "")); recovered = response(c) == 200; }
            catch (SocketException | AssertionError ignored) { }
            c.close();
        }
        assertTrue(recovered);
        s.stop(); for (int i = 1; i < held.size(); i++) closed(held.get(i));
    }
    @Test public void deadlinesCloseTrickledHeadersAndBodies() throws Exception {
        RemoteServer.Limits limits = new RemoteServer.Limits(); limits.headerMs = 120; limits.bodyMs = 120;
        RemoteServer s = server(limits);
        Socket header = connect(s, true); send(header, "P"); Thread.sleep(70); send(header, "O"); Thread.sleep(100); closed(header);
        Socket body = connect(s, true); send(body, request("/pair", "Content-Length: 100\r\n", "a")); Thread.sleep(70); send(body, "b"); Thread.sleep(100); closed(body);
        assertEquals(0, handled.get());
    }
    @Test public void idleAndRequestCountLimits() throws Exception {
        RemoteServer.Limits limits = new RemoteServer.Limits(); limits.idleMs = 120; limits.requests = 2;
        RemoteServer s = server(limits);
        Socket idle = connect(s, true); Thread.sleep(200); closed(idle);
        Socket c = connect(s, true);
        for (int i = 0; i < 2; i++) { send(c, request("/pair", "", "")); assertEquals(200, response(c)); }
        closed(c);
    }
    @Test public void uploadIsSerializedAndPartialFilesAreRemoved() throws Exception {
        RemoteServer s = server(new RemoteServer.Limits());
        Socket first = connect(s, true);
        send(first, request("/upload", "X-Token: valid\r\nContent-Length: 4\r\n", "a"));
        for (int i = 0; i < 100 && allocated.get() == 0; i++) Thread.sleep(10);
        assertEquals(1, allocated.get());
        Socket second = connect(s, true); send(second, request("/upload", "X-Token: valid\r\nContent-Length: 4\r\n", "")); assertEquals(409, response(second));
        first.shutdownOutput(); assertEquals(400, response(first));
        assertFalse(upload.exists()); assertEquals(0, handled.get());
        Socket third = connect(s, true); send(third, request("/upload", "X-Token: valid\r\nContent-Length: 4\r\n", "data")); assertEquals(200, response(third));
        assertEquals(2, allocated.get()); assertEquals(1, handled.get());
    }
    @Test public void stalledTlsHandshakeIsClosed() throws Exception {
        RemoteServer.Limits limits = new RemoteServer.Limits(); limits.headerMs = 120;
        RemoteServer s = server(limits);
        try (ServerSocket listener = javax.net.ssl.SSLServerSocketFactory.getDefault().createServerSocket(0)) {
            Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
            clients.add(client); client.setSoTimeout(3000);
            s.accept(listener.accept(), true);
            Thread.sleep(200);
            // JSSE can send a TLS alert before EOF; it must still close promptly.
            int bytes = 0;
            try { while (client.getInputStream().read() != -1) assertTrue(++bytes < 1024); }
            catch (SocketException expected) { }
        }
    }
    @Test public void aggregateHeadersAndHeaderCountAreBounded() throws Exception {
        RemoteServer s = server(new RemoteServer.Limits());
        StringBuilder headers = new StringBuilder();
        for (int i = 0; i < 65; i++) headers.append("X-").append(i).append(": a\r\n");
        Socket count = connect(s, true); send(count, request("/pair", headers.toString(), "")); assertEquals(431, response(count));
        String value = String.join("", Collections.nCopies(6000, "a"));
        Socket size = connect(s, true); send(size, request("/pair", "X-A: " + value + "\r\nX-B: " + value + "\r\nX-C: " + value + "\r\n", "")); assertEquals(431, response(size));
    }
    @Test public void uploadDeadlinesReleasePermitAndDeleteFile() throws Exception {
        RemoteServer.Limits limits = new RemoteServer.Limits(); limits.uploadMs = 150; limits.uploadIdleMs = 1000;
        RemoteServer s = server(limits);
        Socket c = connect(s, true); send(c, request("/upload", "X-Token: valid\r\nContent-Length: 100\r\n", "a"));
        Thread.sleep(80); send(c, "b"); Thread.sleep(150); closed(c);
        for (int i = 0; i < 100 && upload.exists(); i++) Thread.sleep(10);
        assertFalse(upload.exists());
        Socket next = connect(s, true); send(next, request("/upload", "X-Token: valid\r\nContent-Length: 1\r\n", "a")); assertEquals(200, response(next));
    }
    @Test public void bodyDeadlineDoesNotTimeHandlerExecution() throws Exception {
        RemoteServer.Limits limits = new RemoteServer.Limits(); limits.bodyMs = 60;
        RemoteServer s = new RemoteServer((method, path, headers, body) -> {
            try { Thread.sleep(180); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return RemoteServer.Response.json(200, "{}");
        }, (path, headers) -> null, (method, path, headers, length) -> RemoteServer.Decision.allow(1024, false), limits);
        servers.add(s);
        Socket c = connect(s, true); send(c, request("/pair", "Content-Length: 1\r\n", "a")); assertEquals(200, response(c));
    }
    @Test public void uploadPermitCoversHandlerAndIsReleasedAfterHandlerFailure() throws Exception {
        upload = Files.createTempFile("remote-upload", ".tmp").toFile();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<byte[]> received = new java.util.concurrent.atomic.AtomicReference<>();
        RemoteServer s = new RemoteServer((method, path, headers, body) -> {
            try {
                received.set(Files.readAllBytes(new File(headers.get(RemoteServer.UPLOAD)).toPath()));
                entered.countDown();
                if (!release.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            } catch (Exception e) { throw new IllegalStateException(e); }
            throw new IllegalStateException("handler failed");
        }, (path, headers) -> upload, (method, path, headers, length) -> RemoteServer.Decision.allow(1024, true));
        servers.add(s);
        try {
            Socket first = connect(s, true); send(first, request("/upload", "Content-Length: 4\r\n", "data"));
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertArrayEquals("data".getBytes(StandardCharsets.UTF_8), received.get());
            Socket second = connect(s, true); send(second, request("/upload", "Content-Length: 1\r\n", "")); assertEquals(409, response(second));
            release.countDown(); assertEquals(500, response(first));
            for (int i = 0; i < 100 && upload.exists(); i++) Thread.sleep(10);
            assertFalse(upload.exists());
            Socket third = connect(s, true); send(third, request("/upload", "Content-Length: 1\r\n", "x")); assertEquals(500, response(third));
            assertArrayEquals(new byte[]{'x'}, received.get());
        } finally { release.countDown(); }
    }
    @Test public void uploadInactivityTimeoutClosesAndCleansUp() throws Exception {
        RemoteServer.Limits limits = new RemoteServer.Limits(); limits.uploadMs = 5000; limits.uploadIdleMs = 100;
        RemoteServer s = server(limits);
        Socket c = connect(s, true); send(c, request("/upload", "X-Token: valid\r\nContent-Length: 100\r\n", "a"));
        closed(c);
        assertFalse(upload.exists()); assertEquals(0, handled.get());
    }
}
