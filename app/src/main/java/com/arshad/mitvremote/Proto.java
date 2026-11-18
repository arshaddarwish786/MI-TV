package com.arshad.mitvremote;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Minimal protobuf encoder/decoder — just enough for the Android TV remote protocol. */
final class Proto {

    /** Builder for one protobuf message. */
    static final class W {
        private final ByteArrayOutputStream b = new ByteArrayOutputStream();

        W varint(int field, long value) {
            writeVarint(b, ((long) field << 3));
            writeVarint(b, value);
            return this;
        }

        W bytes(int field, byte[] value) {
            writeVarint(b, ((long) field << 3) | 2);
            writeVarint(b, value.length);
            b.write(value, 0, value.length);
            return this;
        }

        W str(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        W msg(int field, W inner) {
            return bytes(field, inner.toBytes());
        }

        byte[] toBytes() {
            return b.toByteArray();
        }
    }

    /** Parsed message: field number -> list of values (Long for varints, byte[] for length-delimited). */
    static final class M {
        final Map<Integer, List<Object>> f = new HashMap<>();

        boolean has(int field) {
            return f.containsKey(field);
        }

        long num(int field, long def) {
            List<Object> l = f.get(field);
            if (l == null || !(l.get(0) instanceof Long)) return def;
            return (Long) l.get(0);
        }

        byte[] raw(int field) {
            List<Object> l = f.get(field);
            if (l == null || !(l.get(0) instanceof byte[])) return null;
            return (byte[]) l.get(0);
        }

        M sub(int field) {
            byte[] r = raw(field);
            return r == null ? null : parse(r);
        }

        String str(int field) {
            byte[] r = raw(field);
            return r == null ? null : new String(r, StandardCharsets.UTF_8);
        }
    }

    static W w() {
        return new W();
    }

    static M parse(byte[] data) {
        M m = new M();
        int[] pos = {0};
        while (pos[0] < data.length) {
            long key = readVarint(data, pos);
            int field = (int) (key >>> 3);
            int type = (int) (key & 7);
            Object v;
            switch (type) {
                case 0:
                    v = readVarint(data, pos);
                    break;
                case 2: {
                    int len = (int) readVarint(data, pos);
                    byte[] r = new byte[len];
                    System.arraycopy(data, pos[0], r, 0, len);
                    pos[0] += len;
                    v = r;
                    break;
                }
                case 1:
                    pos[0] += 8;
                    continue;
                case 5:
                    pos[0] += 4;
                    continue;
                default:
                    return m; // unknown wire type: stop parsing
            }
            List<Object> l = m.f.get(field);
            if (l == null) {
                l = new ArrayList<>();
                m.f.put(field, l);
            }
            l.add(v);
        }
        return m;
    }

    static void writeVarint(ByteArrayOutputStream out, long v) {
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private static long readVarint(byte[] d, int[] pos) {
        long result = 0;
        int shift = 0;
        while (true) {
            int b = d[pos[0]++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }
    }

    /** Frame = varint length prefix + message bytes. */
    static byte[] frame(byte[] msg) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarint(out, msg.length);
        out.write(msg, 0, msg.length);
        return out.toByteArray();
    }

    static byte[] readFrame(InputStream in) throws IOException {
        long len = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException("Connection closed by TV");
            len |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        if (len > 1 << 20) throw new IOException("Bad message length");
        byte[] buf = new byte[(int) len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, (int) len - off);
            if (n < 0) throw new EOFException("Connection closed by TV");
            off += n;
        }
        return buf;
    }
}
