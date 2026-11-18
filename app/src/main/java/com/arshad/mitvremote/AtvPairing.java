package com.arshad.mitvremote;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPublicKey;

import javax.net.ssl.SSLSocket;

/**
 * Pairing with an Android TV / Google TV ("Polo" protocol, port 6467).
 * start() makes the TV show a 6-character code; finish(code) completes pairing.
 */
final class AtvPairing implements Closeable {

    static final int PORT = 6467;
    private static final int STATUS_OK = 200;
    private static final int ENCODING_HEX = 3;
    private static final int ROLE_INPUT = 1;

    private final ClientIdentity id;
    private SSLSocket sock;
    private InputStream in;
    private OutputStream out;
    private RSAPublicKey serverKey;

    AtvPairing(ClientIdentity id) {
        this.id = id;
    }

    /** Connects and asks the TV to display a pairing code. */
    void start(String host, String clientName) throws Exception {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, PORT), 6000);
        sock = (SSLSocket) id.sslContext().getSocketFactory().createSocket(raw, host, PORT, true);
        sock.setSoTimeout(15000);
        sock.startHandshake();
        Certificate[] peer = sock.getSession().getPeerCertificates();
        serverKey = (RSAPublicKey) peer[0].getPublicKey();
        in = sock.getInputStream();
        out = sock.getOutputStream();

        // 1. Pairing request
        send(outer().msg(10, Proto.w().str(1, "atvremote").str(2, clientName)));
        expect(11, "pairing request");

        // 2. Options: we enter a 6-character hex code
        Proto.W enc = Proto.w().varint(1, ENCODING_HEX).varint(2, 6);
        send(outer().msg(20, Proto.w().msg(1, enc).varint(3, ROLE_INPUT)));
        expect(20, "options");

        // 3. Configuration -> TV shows the code
        send(outer().msg(30, Proto.w().msg(1, enc).varint(2, ROLE_INPUT)));
        expect(31, "configuration");
    }

    /** Returns false if the code doesn't match (nothing is sent, the user can retry). */
    boolean finish(String code) throws Exception {
        code = code.trim().toUpperCase();
        if (!code.matches("[0-9A-F]{6}")) return false;
        byte[] secret = secret(id.publicKey(), serverKey, code);
        if ((secret[0] & 0xFF) != Integer.parseInt(code.substring(0, 2), 16)) return false;
        send(outer().msg(40, Proto.w().bytes(1, secret)));
        expect(41, "secret");
        return true;
    }

    /** SHA-256 over both public keys and the last 4 hex digits of the code. */
    static byte[] secret(RSAPublicKey client, RSAPublicKey server, String code) throws Exception {
        MessageDigest h = MessageDigest.getInstance("SHA-256");
        h.update(unsigned(client.getModulus()));
        h.update(unsigned(client.getPublicExponent()));
        h.update(unsigned(server.getModulus()));
        h.update(unsigned(server.getPublicExponent()));
        h.update(hex(code.substring(2)));
        return h.digest();
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        int i = 0;
        while (i < b.length - 1 && b[i] == 0) i++;
        if (i == 0) return b;
        byte[] r = new byte[b.length - i];
        System.arraycopy(b, i, r, 0, r.length);
        return r;
    }

    private static byte[] hex(String s) {
        byte[] r = new byte[s.length() / 2];
        for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return r;
    }

    private static Proto.W outer() {
        return Proto.w().varint(1, 2).varint(2, STATUS_OK);
    }

    private void send(Proto.W m) throws IOException {
        out.write(Proto.frame(m.toBytes()));
        out.flush();
    }

    private void expect(int field, String step) throws IOException {
        Proto.M m = Proto.parse(Proto.readFrame(in));
        long status = m.num(2, 0);
        if (status != STATUS_OK) {
            throw new IOException("TV refused " + step + " (status " + status + ")");
        }
        // Status OK is what matters; some TVs omit empty acknowledgement messages.
    }

    @Override
    public void close() {
        try {
            if (sock != null) sock.close();
        } catch (IOException ignored) {
        }
    }
}
