package com.arshad.mitvremote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * This phone's identity towards the TV: an RSA key pair and a self-signed certificate.
 * The TV remembers the certificate after pairing, so it is generated once and kept.
 */
final class ClientIdentity {

    final PrivateKey privateKey;
    final X509Certificate cert;
    private SSLContext ctx;

    private ClientIdentity(PrivateKey k, X509Certificate c) {
        privateKey = k;
        cert = c;
    }

    RSAPublicKey publicKey() {
        return (RSAPublicKey) cert.getPublicKey();
    }

    static synchronized ClientIdentity loadOrCreate(File dir) throws Exception {
        File keyFile = new File(dir, "client.key");
        File certFile = new File(dir, "client.crt");
        if (keyFile.exists() && certFile.exists()) {
            try {
                PrivateKey k = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(readAll(keyFile)));
                X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(readAll(certFile)));
                return new ClientIdentity(k, c);
            } catch (Exception ignored) {
                // fall through and regenerate
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048, new SecureRandom());
        KeyPair kp = g.generateKeyPair();
        byte[] der = selfSignedCert(kp, "atvremote");
        X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        writeAll(keyFile, kp.getPrivate().getEncoded());
        writeAll(certFile, der);
        return new ClientIdentity(kp.getPrivate(), c);
    }

    synchronized SSLContext sslContext() throws Exception {
        if (ctx != null) return ctx;
        char[] pw = "atvremote".toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("client", privateKey, pw, new Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        // The TV uses its own self-signed certificate; we identify it by pairing, not by a CA.
        TrustManager[] trustTv = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(kmf.getKeyManagers(), trustTv, new SecureRandom());
        ctx = c;
        return c;
    }

    // ---- Minimal DER / X.509 v3 certificate builder ----

    private static byte[] selfSignedCert(KeyPair kp, String cn) throws Exception {
        byte[] sha256WithRsa = seq(oid(new int[]{1, 2, 840, 113549, 1, 1, 11}), new byte[]{0x05, 0x00});
        byte[] name = seq(set(seq(oid(new int[]{2, 5, 4, 3}),
                tlv(0x0C, cn.getBytes(StandardCharsets.UTF_8)))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(utcTime(new Date(now - 86_400_000L)),
                utcTime(new Date(now + 20L * 365 * 86_400_000L)));
        byte[] serial = tlv(0x02, new BigInteger(63, new SecureRandom()).add(BigInteger.ONE).toByteArray());
        byte[] version = tlv(0xA0, tlv(0x02, new byte[]{2}));
        byte[] tbs = seq(version, serial, sha256WithRsa, name, validity, name, kp.getPublic().getEncoded());

        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bitString = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bitString, 1, sig.length);
        return seq(tbs, sha256WithRsa, tlv(0x03, bitString));
    }

    private static byte[] utcTime(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] oid(int[] parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(parts[0] * 40 + parts[1]);
        for (int i = 2; i < parts.length; i++) {
            int v = parts[i];
            byte[] tmp = new byte[5];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7F);
                v >>>= 7;
            } while (v != 0);
            for (int j = n - 1; j >= 0; j--) o.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, o.toByteArray());
    }

    private static byte[] seq(byte[]... items) {
        return tlv(0x30, concat(items));
    }

    private static byte[] set(byte[]... items) {
        return tlv(0x31, concat(items));
    }

    private static byte[] concat(byte[]... items) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] i : items) o.write(i, 0, i.length);
        return o.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int len = value.length;
        if (len < 128) {
            o.write(len);
        } else if (len < 256) {
            o.write(0x81);
            o.write(len);
        } else if (len < 65536) {
            o.write(0x82);
            o.write(len >> 8);
            o.write(len & 0xFF);
        } else {
            o.write(0x83);
            o.write(len >> 16);
            o.write((len >> 8) & 0xFF);
            o.write(len & 0xFF);
        }
        o.write(value, 0, value.length);
        return o.toByteArray();
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }
}
