package com.solana.mobilewalletadapter.walletlib.scenario;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.protocols.Protocol;

import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** A dapp endpoint that follows the spec by default and can be told to break it at any step. */
final class GauntletDapp {
    public final KeyPair association;
    public KeyPair ecdh;
    SecretKey key;
    int txSeq = 0, rxSeq = 0;
    public final BlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>();
    public volatile boolean closed = false;
    public volatile String closeInfo = null;
    private WebSocketClient ws;

    GauntletDapp() throws Exception {
        association = p256();
        ecdh = p256();
    }


    static KeyPair p256() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    public static byte[] x962(ECPublicKey k) {
        byte[] out = new byte[65]; out[0] = 4;
        byte[] x = k.getW().getAffineX().toByteArray(), y = k.getW().getAffineY().toByteArray();
        int xl = Math.min(32, x.length), yl = Math.min(32, y.length);
        System.arraycopy(x, x.length - xl, out, 33 - xl, xl);
        System.arraycopy(y, y.length - yl, out, 65 - yl, yl);
        return out;
    }

    public byte[] associationToken() { return x962((ECPublicKey) association.getPublic()); }

    public void connect(int port) throws Exception {
        CountDownLatch open = new CountDownLatch(1);
        ws = new WebSocketClient(new URI("ws://127.0.0.1:" + port + "/solana-wallet"),
                new Draft_6455(Collections.emptyList(), List.of(new Protocol("com.solana.mobilewalletadapter.v1")))) {
            public void onOpen(ServerHandshake h) { open.countDown(); }
            public void onMessage(String m) { inbox.add(m.getBytes()); }
            public void onMessage(ByteBuffer b) { byte[] a = new byte[b.remaining()]; b.get(a); inbox.add(a); }
            public void onClose(int code, String reason, boolean remote) { closed = true; closeInfo = code + " " + reason; open.countDown(); }
            public void onError(Exception e) { closeInfo = "error " + e; }
        };
        ws.connect();
        if (!open.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("no connection");
    }

    public void sendRaw(byte[] b) { ws.send(b); }
    public void close() { try { ws.closeBlocking(); } catch (Exception ignored) {} }

    /** P1363 signature of data with the association key. */
    public byte[] signP1363(byte[] data) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
        s.initSign(association.getPrivate()); s.update(data); return s.sign();
    }

    public byte[] helloReq() throws Exception {
        byte[] qd = x962((ECPublicKey) ecdh.getPublic());
        byte[] sig = signP1363(qd);
        byte[] out = Arrays.copyOf(qd, 129); System.arraycopy(sig, 0, out, 65, 64); return out;
    }

    /** Handles HELLO_RSP; returns the decrypted session-properties JSON (or null for legacy). */
    public String finishHandshake(long timeoutMs) throws Exception {
        byte[] rsp = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (rsp == null) throw new TimeoutException("no HELLO_RSP");
        KeyFactory kf = KeyFactory.getInstance("EC");
        ECParameterSpec spec = ((ECPublicKey) ecdh.getPublic()).getParams();
        ECPoint w = new ECPoint(new BigInteger(1, Arrays.copyOfRange(rsp, 1, 33)), new BigInteger(1, Arrays.copyOfRange(rsp, 33, 65)));
        PublicKey qw = kf.generatePublic(new ECPublicKeySpec(w, spec));
        KeyAgreement ka = KeyAgreement.getInstance("ECDH"); ka.init(ecdh.getPrivate()); ka.doPhase(qw, true);
        byte[] secret = ka.generateSecret();
        key = new SecretKeySpec(hkdf(secret, associationToken()), "AES");
        if (rsp.length > 65) return new String(decrypt(Arrays.copyOfRange(rsp, 65, rsp.length)), "UTF-8");
        return null;
    }

    static byte[] hkdf(byte[] ikm, byte[] salt) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256")); mac.update((byte) 1);
        return Arrays.copyOf(mac.doFinal(), 16);
    }

    public byte[] encrypt(byte[] pt, int seq) throws Exception {
        byte[] s = ByteBuffer.allocate(4).putInt(seq).array();
        byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv)); c.updateAAD(s);
        byte[] ct = c.doFinal(pt);
        ByteBuffer b = ByteBuffer.allocate(16 + ct.length); b.put(s).put(iv).put(ct); return b.array();
    }

    public byte[] decrypt(byte[] msg) throws Exception {
        int seq = ByteBuffer.wrap(msg, 0, 4).getInt();
        if (seq != rxSeq + 1) throw new IllegalStateException("wallet sent seq " + seq + ", expected " + (rxSeq + 1));
        rxSeq = seq;
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, msg, 4, 12)); c.updateAAD(msg, 0, 4);
        return c.doFinal(msg, 16, msg.length - 16);
    }

    public void sendJson(String json) throws Exception { sendRaw(encrypt(json.getBytes("UTF-8"), ++txSeq)); }

    /** Collects every decrypted reply that arrives within the window. */
    public List<String> replies(long windowMs) throws Exception {
        List<String> out = new ArrayList<>();
        long end = System.currentTimeMillis() + windowMs;
        while (true) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) break;
            byte[] m = inbox.poll(left, TimeUnit.MILLISECONDS);
            if (m == null) break;
            out.add(new String(decrypt(m), "UTF-8"));
        }
        return out;
    }
}
