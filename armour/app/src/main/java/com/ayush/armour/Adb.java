package com.ayush.armour;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;

/**
 * Just enough of the adb wire protocol to run one shell command on this same TV over 127.0.0.1:5555.
 * The launcher uses it once per boot to start {@link KeyServer} as the shell user, which may inject key
 * presses into any app. The TV asks once to allow this app's key ("Always allow" makes it permanent).
 */
final class Adb {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;

    private Adb() {
    }

    /** Runs {@code cmd} through adb's shell service and returns its output. Blocking, may wait for the TV prompt. */
    static String shell(Context c, String cmd, int authWaitMs) throws Exception {
        KeyPair kp = keys(c);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            s.setSoTimeout(authWaitMs);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            send(out, CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            // 1st token: sign it (works once the TV has "always allowed" our key). 2nd token: the signature
            // was refused, so offer the public key and the TV shows "Allow USB debugging?". 3rd: refused.
            int tokens = 0;
            while (true) {
                int[] h = new int[6];
                byte[] data = read(in, h);
                if (h[0] == CNXN) break;
                if (h[0] != AUTH || h[1] != AUTH_TOKEN) throw new Exception("adb: unexpected " + Integer.toHexString(h[0]));
                tokens++;
                if (tokens == 1) send(out, AUTH, AUTH_SIGNATURE, 0, sign(kp.getPrivate(), data));
                else if (tokens == 2) send(out, AUTH, AUTH_RSAPUBLICKEY, 0, publicKey((RSAPublicKey) kp.getPublic()));
                else throw new Exception("adb: key refused");
            }
            s.setSoTimeout(15000);
            int local = 1;
            send(out, OPEN, local, 0, ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            int remote = 0;
            while (true) {
                int[] h = new int[6];
                byte[] data = read(in, h);
                if (h[0] == OKAY) {
                    remote = h[1];
                } else if (h[0] == WRTE) {
                    result.write(data);
                    send(out, OKAY, local, h[1], new byte[0]);
                } else if (h[0] == CLSE) {
                    send(out, CLSE, local, remote, new byte[0]);
                    break;
                }
            }
            return result.toString("UTF-8");
        }
    }

    private static void send(OutputStream out, int cmd, int a0, int a1, byte[] data) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xFF;
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    private static byte[] read(DataInputStream in, int[] h) throws Exception {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 6; i++) h[i] = b.getInt();
        byte[] data = new byte[h[3]];
        in.readFully(data);
        return data;
    }

    // ---- keys ------------------------------------------------------------------------------------

    private static KeyPair keys(Context c) throws Exception {
        File priv = new File(c.getFilesDir(), "adbkey"), pub = new File(c.getFilesDir(), "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath()))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        Files.write(priv.toPath(), kp.getPrivate().getEncoded());
        Files.write(pub.toPath(), kp.getPublic().getEncoded());
        return kp;
    }

    /** adb signs the 20-byte token as if it were a SHA-1 digest: PKCS#1 v1.5 type 1 padding by hand. */
    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        byte[] digestInfo = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        byte[] block = new byte[256];
        Arrays.fill(block, (byte) 0xFF);
        block[0] = 0x00;
        block[1] = 0x01;
        int tail = digestInfo.length + token.length;
        block[256 - tail - 1] = 0x00;
        System.arraycopy(digestInfo, 0, block, 256 - tail, digestInfo.length);
        System.arraycopy(token, 0, block, 256 - token.length, token.length);
        Cipher rsa = Cipher.getInstance("RSA/ECB/NoPadding");
        rsa.init(Cipher.ENCRYPT_MODE, key);
        return rsa.doFinal(block);
    }

    /** The public key in adb's own format (mincrypt RSAPublicKey struct, base64) plus a name for the prompt. */
    private static byte[] publicKey(RSAPublicKey k) {
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n = k.getModulus();
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n0inv.intValue());
        BigInteger t = n;
        for (int i = 0; i < 64; i++) {
            b.putInt(t.mod(r32).intValue());
            t = t.shiftRight(32);
        }
        t = rr;
        for (int i = 0; i < 64; i++) {
            b.putInt(t.mod(r32).intValue());
            t = t.shiftRight(32);
        }
        b.putInt(k.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " armour@livingroom\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
