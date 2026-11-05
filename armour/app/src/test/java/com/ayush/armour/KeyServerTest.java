package com.ayush.armour;

import org.junit.Test;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class KeyServerTest {
    @Test public void lineLimitsAndAbsoluteDeadline() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
             Socket server = listener.accept()) {
            client.getOutputStream().write("12345\n".getBytes(StandardCharsets.UTF_8));
            try { KeyServer.readLine(server, server.getInputStream(), 4, 1000); fail("oversized line accepted"); }
            catch (IOException expected) { }
        }
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
             Socket server = listener.accept()) {
            Thread writer = new Thread(() -> {
                try { for (int i = 0; i < 20; i++) { client.getOutputStream().write('a'); Thread.sleep(20); } }
                catch (Exception ignored) { }
            });
            writer.start();
            long start = System.nanoTime();
            try { KeyServer.readLine(server, server.getInputStream(), 512, 100); fail("deadline not enforced"); }
            catch (SocketTimeoutException expected) { }
            assertTrue((System.nanoTime() - start) / 1_000_000 < 1000);
            client.close(); writer.join(1000);
        }
    }

    @Test public void capRejectsExcessAndAllowsAuthenticatedPing() throws Exception {
        List<Socket> clients = new ArrayList<>();
        try (ServerSocket listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            for (int i = 0; i < 5; i++) {
                Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                client.setSoTimeout(1500); clients.add(client);
                KeyServer.accept(listener.accept(), "secret");
            }
            assertEquals(-1, clients.get(4).getInputStream().read());
            Socket first = clients.get(0);
            first.getOutputStream().write("secret\nping\n".getBytes(StandardCharsets.UTF_8));
            assertEquals("pong", new BufferedReader(new InputStreamReader(first.getInputStream())).readLine());
            clients.get(1).getOutputStream().write("wrong\n".getBytes(StandardCharsets.UTF_8));
            assertEquals(-1, clients.get(1).getInputStream().read());
        } finally { for (Socket client : clients) client.close(); }
    }
}
