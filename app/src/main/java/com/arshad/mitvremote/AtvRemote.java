package com.arshad.mitvremote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/** Remote-control session with an Android TV / Google TV (port 6466). */
final class AtvRemote {

    static final int PORT = 6466;
    private static final int FEATURES = 622; // ping, key, IME, power, volume, app link

    interface Listener {
        void onDisconnected(String reason);
    }

    /** The TV didn't accept our certificate: pairing is needed. */
    static final class NotPairedException extends IOException {
        NotPairedException(String m) {
            super(m);
        }
    }

    private final ClientIdentity id;
    private final Listener listener;
    private SSLSocket sock;
    private OutputStream out;
    private volatile boolean closed;

    AtvRemote(ClientIdentity id, Listener listener) {
        this.id = id;
        this.listener = listener;
    }

    void connect(String host) throws Exception {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, PORT), 5000);
        try {
            sock = (SSLSocket) id.sslContext().getSocketFactory().createSocket(raw, host, PORT, true);
            sock.setSoTimeout(6000);
            sock.startHandshake();
        } catch (SSLException e) {
            closeQuietly();
            throw new NotPairedException("TV rejected this phone: " + e.getMessage());
        }
        InputStream in = sock.getInputStream();
        out = sock.getOutputStream();

        // The TV speaks first with a configure message. If it hangs up instead, we're not paired.
        Proto.M first;
        try {
            first = Proto.parse(Proto.readFrame(in));
        } catch (SSLException e) {
            closeQuietly();
            throw new NotPairedException("TV rejected this phone: " + e.getMessage());
        } catch (java.io.EOFException e) {
            closeQuietly();
            throw new NotPairedException("TV closed the connection (not paired)");
        }
        handle(first);
        sock.setSoTimeout(0);

        Thread reader = new Thread(() -> {
            try {
                while (!closed) handle(Proto.parse(Proto.readFrame(in)));
            } catch (Exception e) {
                if (!closed) {
                    closeQuietly();
                    listener.onDisconnected(e.getMessage());
                }
            }
        }, "atv-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void handle(Proto.M m) throws IOException {
        if (m.has(1)) { // remote_configure
            Proto.W info = Proto.w()
                    .str(1, android_model())
                    .str(2, "Arshad")
                    .varint(3, 1)
                    .str(4, "1")
                    .str(5, "atvremote")
                    .str(6, "1.0.0");
            send(Proto.w().msg(1, Proto.w().varint(1, FEATURES).msg(2, info)));
        } else if (m.has(2)) { // remote_set_active
            send(Proto.w().msg(2, Proto.w().varint(1, FEATURES)));
        } else if (m.has(8)) { // ping request -> pong
            Proto.M ping = m.sub(8);
            long v = ping == null ? 0 : ping.num(1, 0);
            send(Proto.w().msg(9, Proto.w().varint(1, v)));
        }
        // other messages (volume info, IME state, errors) are ignored
    }

    private static String android_model() {
        try {
            Class<?> b = Class.forName("android.os.Build");
            return (String) b.getField("MODEL").get(null);
        } catch (Exception e) {
            return "Phone";
        }
    }

    /** Short key press using Android KeyEvent codes. */
    void sendKey(int keyCode) throws IOException {
        send(Proto.w().msg(10, Proto.w().varint(1, keyCode).varint(2, 3)));
    }

    /** Opens an app on the TV via a deep link, e.g. https://www.youtube.com */
    void openLink(String url) throws IOException {
        send(Proto.w().msg(90, Proto.w().str(1, url)));
    }

    private synchronized void send(Proto.W m) throws IOException {
        if (out == null || closed) throw new IOException("Not connected");
        out.write(Proto.frame(m.toBytes()));
        out.flush();
    }

    boolean isOpen() {
        return sock != null && !closed && !sock.isClosed();
    }

    void close() {
        closed = true;
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (sock != null) sock.close();
        } catch (IOException ignored) {
        }
    }
}
