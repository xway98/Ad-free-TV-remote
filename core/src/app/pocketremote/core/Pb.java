package app.pocketremote.core;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Just enough protobuf (varints and length-delimited fields) for the Android TV remote protocol. */
final class Pb {
    private Pb() {}

    static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Writer varint(int field, long value) {
            rawVarint((long) field << 3);
            rawVarint(value);
            return this;
        }

        Writer bytes(int field, byte[] value) {
            rawVarint(((long) field << 3) | 2);
            rawVarint(value.length);
            out.write(value, 0, value.length);
            return this;
        }

        Writer string(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        Writer message(int field, Writer sub) {
            return bytes(field, sub.toBytes());
        }

        byte[] toBytes() {
            return out.toByteArray();
        }

        private void rawVarint(long v) {
            while ((v & ~0x7FL) != 0) {
                out.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            out.write((int) v);
        }
    }

    static final class Field {
        final int number;
        final int wire;
        final long num;
        final byte[] data;

        Field(int number, int wire, long num, byte[] data) {
            this.number = number;
            this.wire = wire;
            this.num = num;
            this.data = data;
        }

        String str() {
            return new String(data, StandardCharsets.UTF_8);
        }

        List<Field> sub() {
            return parse(data);
        }
    }

    static List<Field> parse(byte[] b) {
        List<Field> out = new ArrayList<>();
        int[] pos = {0};
        while (pos[0] < b.length) {
            long tag = readVarint(b, pos);
            int wire = (int) (tag & 7);
            int number = (int) (tag >>> 3);
            if (wire == 0) {
                out.add(new Field(number, wire, readVarint(b, pos), null));
            } else if (wire == 2) {
                int len = (int) readVarint(b, pos);
                if (len < 0 || pos[0] + len > b.length) throw new IllegalArgumentException("bad length");
                byte[] d = new byte[len];
                System.arraycopy(b, pos[0], d, 0, len);
                pos[0] += len;
                out.add(new Field(number, wire, 0, d));
            } else if (wire == 1) {
                pos[0] += 8;
            } else if (wire == 5) {
                pos[0] += 4;
            } else {
                throw new IllegalArgumentException("unsupported wire type " + wire);
            }
        }
        return out;
    }

    static Field find(List<Field> fields, int number) {
        for (Field f : fields) if (f.number == number) return f;
        return null;
    }

    private static long readVarint(byte[] b, int[] pos) {
        long result = 0;
        int shift = 0;
        while (true) {
            if (pos[0] >= b.length) throw new IllegalArgumentException("truncated varint");
            int c = b[pos[0]++] & 0xFF;
            result |= (long) (c & 0x7F) << shift;
            if ((c & 0x80) == 0) return result;
            shift += 7;
            if (shift > 63) throw new IllegalArgumentException("varint too long");
        }
    }

    /** Messages on the wire are a varint length followed by that many bytes. */
    static void writeFrame(OutputStream out, byte[] msg) throws IOException {
        Writer w = new Writer();
        w.rawVarint(msg.length);
        byte[] len = w.toBytes();
        byte[] all = new byte[len.length + msg.length];
        System.arraycopy(len, 0, all, 0, len.length);
        System.arraycopy(msg, 0, all, len.length, msg.length);
        out.write(all);
        out.flush();
    }

    static byte[] readFrame(InputStream in) throws IOException {
        long len = 0;
        int shift = 0;
        while (true) {
            int c = in.read();
            if (c < 0) throw new EOFException("connection closed");
            len |= (long) (c & 0x7F) << shift;
            if ((c & 0x80) == 0) break;
            shift += 7;
            if (shift > 28) throw new IOException("bad frame length");
        }
        if (len > 1_000_000) throw new IOException("frame too large");
        byte[] buf = new byte[(int) len];
        int got = 0;
        while (got < buf.length) {
            int n = in.read(buf, got, buf.length - got);
            if (n < 0) throw new EOFException("connection closed");
            got += n;
        }
        return buf;
    }
}
