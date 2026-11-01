package app.pocketremote.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * A live connection to the laptop's PC agent: mouse, keys and text, over TLS. The agent's certificate is pinned at
 * pairing (the 6-digit code shown on the laptop is mixed into a MAC of that certificate, so nobody in the middle can
 * pair in its place), and every later connection proves itself with the token the pairing returned.
 *
 * Mouse moves are merged while they wait to be sent, so a slow moment on the network turns into one bigger move
 * instead of a growing queue of old ones. Everything else keeps its order.
 */
public final class PcSession implements Closeable {
    public static final int PORT = 47891, BEACON_PORT = 47892;

    public static final int LEFT = 1, RIGHT = 2, MIDDLE = 3, BACK = 4, FORWARD = 5;
    public static final int SYS_LOCK = 1, SYS_SLEEP = 2, SYS_RESTART = 3, SYS_SHUTDOWN = 4, SYS_SCREEN_OFF = 5;

    private static final int OP_AUTH = 0x01, OP_PAIR = 0x02, OP_MOVE = 0x10, OP_BUTTON = 0x11, OP_SCROLL = 0x12,
            OP_KEY = 0x20, OP_TEXT = 0x21, OP_CHORD = 0x22, OP_SYSTEM = 0x30, OP_LAUNCH = 0x31, OP_LIST_APPS = 0x32,
            OP_LIST_WINDOWS = 0x33, OP_FOCUS_WINDOW = 0x34, OP_CLOSE_WINDOW = 0x35, OP_CLIP_SET = 0x36, OP_CLIP_GET = 0x37,
            OP_PING = 0x40, OP_RELEASE_ALL = 0x50,
            OP_PONG = 0x41, OP_AUTH_OK = 0x80, OP_PAIR_OK = 0x81, OP_APPS = 0x82, OP_WINDOWS = 0x83, OP_CLIP = 0x84, OP_DENIED = 0x8E;

    public interface Listener {
        void onReady(String laptopName);

        void onClosed(Exception reason);
    }

    /** One open window on the laptop. */
    public static final class Win {
        public final long handle;
        public final boolean foreground;
        public final String process, title;

        Win(long handle, boolean foreground, String process, String title) {
            this.handle = handle;
            this.foreground = foreground;
            this.process = process;
            this.title = title;
        }
    }

    /** The laptop's answer to {@link #requestWindows}, front-most window first. Called on the reader thread. */
    public interface WindowsCallback {
        void onWindows(java.util.List<Win> windows);
    }

    /** The laptop's answer to {@link #requestClipboard}. Called on the reader thread. */
    public interface ClipCallback {
        void onClip(String text);
    }

    /** The laptop's answer to {@link #requestApps}: each entry is {id, name}. Called on the reader thread. */
    public interface AppsCallback {
        void onApps(java.util.List<String[]> apps);
    }

    /** What a successful pairing leaves behind: keep all three, they are what the next connections need. */
    public static final class Paired {
        public final byte[] token, fingerprint;
        public final String laptopName;

        Paired(byte[] token, byte[] fingerprint, String laptopName) {
            this.token = token;
            this.fingerprint = fingerprint;
            this.laptopName = laptopName;
        }
    }

    /** Pins one certificate; with no pin it accepts any and remembers it (only pairing does that). */
    private static final class Trust implements X509TrustManager {
        private final byte[] pinned;
        volatile byte[] seen;

        Trust(byte[] pinned) {
            this.pinned = pinned;
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("no client certificates");
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            byte[] fp = sha256(chain[0].getEncoded());
            seen = fp;
            if (pinned != null && !MessageDigest.isEqual(pinned, fp)) {
                throw new CertificateException("the laptop's certificate changed: pair again");
            }
        }

        @Override public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    // ---- pairing ---------------------------------------------------------------------------------------

    public static Paired pair(String host, int port, String code, String phoneName) throws IOException {
        Trust trust = new Trust(null);
        SSLSocket s = open(host, port, trust, 4000);
        try {
            byte[] fp = trust.seen;
            byte[] mac;
            try {
                Mac m = Mac.getInstance("HmacSHA256");
                m.init(new SecretKeySpec(code.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
                mac = m.doFinal(fp);
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
            writeFrame(s.getOutputStream(), OP_PAIR, mac, phoneName.getBytes(StandardCharsets.UTF_8));
            byte[] r = readFrame(s.getInputStream());
            if ((r[0] & 0xFF) == OP_PAIR_OK && r.length >= 33) {
                byte[] token = new byte[32];
                System.arraycopy(r, 1, token, 0, 32);
                return new Paired(token, fp, new String(r, 33, r.length - 33, StandardCharsets.UTF_8));
            }
            if ((r[0] & 0xFF) == OP_DENIED) throw new SecurityException(new String(r, 1, r.length - 1, StandardCharsets.UTF_8));
            throw new IOException("unexpected reply from the laptop");
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ---- a connection ----------------------------------------------------------------------------------

    private final String host;
    private final int port;
    private final byte[] token, fingerprint;
    private final Listener listener;

    private final Object lock = new Object();
    private final ArrayDeque<Object> queue = new ArrayDeque<>();     // byte[] frames, and int[]{dx, dy} merged moves
    private volatile SSLSocket socket;
    private volatile boolean closed;
    private volatile AppsCallback appsCallback;
    private volatile WindowsCallback windowsCallback;
    private volatile ClipCallback clipCallback;
    private volatile int agentVersion = 1;
    private volatile long lastRx;
    private long lastPing;

    public PcSession(String host, int port, byte[] token, byte[] fingerprint, Listener listener) {
        this.host = host;
        this.port = port;
        this.token = token;
        this.fingerprint = fingerprint;
        this.listener = listener;
    }

    /** Connects, signs in and starts the reader and writer. {@link Listener#onReady} fires once the laptop accepts us. */
    public void connect(int connectTimeoutMs) throws IOException {
        SSLSocket s = open(host, port, new Trust(fingerprint), connectTimeoutMs);
        try {
            writeFrame(s.getOutputStream(), OP_AUTH, token, null);
            byte[] r = readFrame(s.getInputStream());
            if ((r[0] & 0xFF) == OP_DENIED) throw new SecurityException(new String(r, 1, r.length - 1, StandardCharsets.UTF_8));
            if ((r[0] & 0xFF) != OP_AUTH_OK) throw new IOException("unexpected reply from the laptop");
            s.setSoTimeout(0);
            socket = s;
            lastRx = System.currentTimeMillis();
            lastPing = lastRx;
            // "name" from the first version of the agent, "name\nversion" from later ones
            String hello = new String(r, 1, r.length - 1, StandardCharsets.UTF_8);
            int nl = hello.indexOf('\n');
            final String name = nl < 0 ? hello : hello.substring(0, nl);
            try {
                agentVersion = nl < 0 ? 1 : Integer.parseInt(hello.substring(nl + 1).trim());
            } catch (NumberFormatException e) {
                agentVersion = 1;
            }
            final InputStream in = s.getInputStream();
            final OutputStream out = s.getOutputStream();
            Thread reader = new Thread(() -> readLoop(in), "pc-reader");
            reader.setDaemon(true);
            reader.start();
            Thread writer = new Thread(() -> writeLoop(out), "pc-writer");
            writer.setDaemon(true);
            writer.start();
            listener.onReady(name);
        } catch (IOException | RuntimeException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    public boolean isOpen() {
        return socket != null && !closed;
    }

    /** What the laptop's agent understands: 1 original, 2 opens apps, 3 rescans them, 4 lists windows and shares the clipboard. */
    public int agentVersion() {
        return agentVersion;
    }

    @Override
    public void close() {
        closed = true;
        SSLSocket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    // ---- what to send -----------------------------------------------------------------------------------

    /** A relative mouse move in pixels. Moves queued behind a slow network are added together. */
    public void move(int dx, int dy) {
        if (closed || (dx == 0 && dy == 0)) return;
        synchronized (lock) {
            Object tail = queue.peekLast();
            if (tail instanceof int[]) {
                int[] m = (int[]) tail;
                m[0] += dx;
                m[1] += dy;
            } else {
                queue.add(new int[]{dx, dy});
            }
            lock.notify();
        }
    }

    public void button(int button, boolean down) {
        send(frame(OP_BUTTON, new byte[]{(byte) button, (byte) (down ? 1 : 0)}));
    }

    public void click(int button) {
        button(button, true);
        button(button, false);
    }

    /** Wheel units: 120 is one notch. Positive scrolls up (vertical) or right (horizontal). */
    public void scroll(int vertical, int horizontal) {
        if (vertical == 0 && horizontal == 0) return;
        send(frame(OP_SCROLL, new byte[]{hi(vertical), lo(vertical), hi(horizontal), lo(horizontal)}));
    }

    public void key(int vk, boolean down) {
        send(frame(OP_KEY, new byte[]{hi(vk), lo(vk), (byte) (down ? 1 : 0)}));
    }

    /** Presses the keys in order and releases them in reverse, as one unit: Ctrl, Shift, Esc is a chord. */
    public void chord(int... vks) {
        byte[] p = new byte[1 + vks.length * 2];
        p[0] = (byte) vks.length;
        for (int i = 0; i < vks.length; i++) {
            p[1 + 2 * i] = hi(vks[i]);
            p[2 + 2 * i] = lo(vks[i]);
        }
        send(frame(OP_CHORD, p));
    }

    public void text(String text) {
        if (text == null || text.isEmpty()) return;
        send(frame(OP_TEXT, text.getBytes(StandardCharsets.UTF_8)));
    }

    public void system(int action) {
        send(frame(OP_SYSTEM, new byte[]{(byte) action}));
    }

    public void releaseAll() {
        send(frame(OP_RELEASE_ALL, new byte[0]));
    }

    /** Opens something on the laptop: an id from {@link #requestApps} ("app:...") or "run:" + path or link. */
    public void launch(String id) {
        send(frame(OP_LAUNCH, id.getBytes(StandardCharsets.UTF_8)));
    }

    /** Asks for the laptop's Start menu apps; the first call can take a couple of seconds on the laptop. */
    public void requestApps(AppsCallback cb) {
        requestApps(false, cb);
    }

    /** With refresh the laptop looks again instead of answering from what it remembers (agent version 3 and later). */
    public void requestApps(boolean refresh, AppsCallback cb) {
        appsCallback = cb;
        send(frame(OP_LIST_APPS, new byte[]{(byte) (refresh ? 1 : 0)}));
    }

    /** Asks for the open windows (agent version 4 and later). */
    public void requestWindows(WindowsCallback cb) {
        windowsCallback = cb;
        send(frame(OP_LIST_WINDOWS, new byte[0]));
    }

    public void focusWindow(long handle) {
        send(frame(OP_FOCUS_WINDOW, longBytes(handle)));
    }

    /** Asks a window to close, as its own X button would. */
    public void closeWindow(long handle) {
        send(frame(OP_CLOSE_WINDOW, longBytes(handle)));
    }

    /** Puts text on the laptop's clipboard. At most about 60,000 bytes fit in one message; the caller checks. */
    public void setClipboard(String text) {
        send(frame(OP_CLIP_SET, text.getBytes(StandardCharsets.UTF_8)));
    }

    public void requestClipboard(ClipCallback cb) {
        clipCallback = cb;
        send(frame(OP_CLIP_GET, new byte[0]));
    }

    private void send(byte[] frame) {
        if (closed) return;
        synchronized (lock) {
            queue.add(frame);
            lock.notify();
        }
    }

    // ---- threads ----------------------------------------------------------------------------------------

    private void readLoop(InputStream in) {
        try {
            while (!closed) {
                byte[] f = readFrame(in);
                lastRx = System.currentTimeMillis();
                if ((f[0] & 0xFF) == OP_WINDOWS) {
                    WindowsCallback cb = windowsCallback;
                    windowsCallback = null;
                    if (cb != null) cb.onWindows(parseWindows(new String(f, 1, f.length - 1, StandardCharsets.UTF_8)));
                } else if ((f[0] & 0xFF) == OP_CLIP) {
                    ClipCallback cb = clipCallback;
                    clipCallback = null;
                    if (cb != null) cb.onClip(new String(f, 1, f.length - 1, StandardCharsets.UTF_8));
                }
                if ((f[0] & 0xFF) == OP_APPS) {
                    AppsCallback cb = appsCallback;
                    appsCallback = null;
                    if (cb != null) cb.onApps(parseApps(new String(f, 1, f.length - 1, StandardCharsets.UTF_8)));
                }
            }
        } catch (IOException | RuntimeException e) {
            fail(e);
        }
    }

    private void writeLoop(OutputStream out) {
        try {
            while (true) {
                Object item;
                synchronized (lock) {
                    while (true) {
                        if (closed) return;
                        long now = System.currentTimeMillis();
                        if (now - lastRx > 6000) throw new IOException("no answer from the laptop");
                        if (now - lastPing >= 2000) {
                            queue.add(frame(OP_PING, new byte[0]));
                            lastPing = now;
                        }
                        if (!queue.isEmpty()) break;
                        lock.wait(500);
                    }
                    item = queue.poll();
                }
                if (item instanceof int[]) {
                    int[] m = (int[]) item;
                    int dx = m[0], dy = m[1];
                    while (dx != 0 || dy != 0) {         // a move can be bigger than one message holds
                        int cx = Math.max(-32768, Math.min(32767, dx)), cy = Math.max(-32768, Math.min(32767, dy));
                        out.write(frame(OP_MOVE, new byte[]{hi(cx), lo(cx), hi(cy), lo(cy)}));
                        dx -= cx;
                        dy -= cy;
                    }
                } else {
                    out.write((byte[]) item);
                }
                out.flush();
            }
        } catch (IOException | RuntimeException | InterruptedException e) {
            fail(e instanceof Exception ? (Exception) e : new IOException(e));
        }
    }

    private void fail(Exception e) {
        if (closed) return;
        close();
        listener.onClosed(e);
    }

    // ---- plumbing ---------------------------------------------------------------------------------------

    private static SSLSocket open(String host, int port, Trust trust, int connectMs) throws IOException {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{trust}, null);
            Socket raw = new Socket();
            raw.setTcpNoDelay(true);
            raw.connect(new InetSocketAddress(host, port), connectMs);
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
            s.setEnabledProtocols(new String[]{"TLSv1.2"});
            s.setSoTimeout(5000);
            s.startHandshake();
            return s;
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static byte[] longBytes(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) v;
            v >>= 8;
        }
        return b;
    }

    private static java.util.List<Win> parseWindows(String text) {
        java.util.List<Win> out = new java.util.ArrayList<>();
        for (String line : text.split("\n")) {
            String[] p = line.split("\t", 4);
            if (p.length < 4) continue;
            try {
                out.add(new Win(Long.parseLong(p[0]), "1".equals(p[1]), p[2], p[3]));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static java.util.List<String[]> parseApps(String text) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String line : text.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0 && tab < line.length() - 1) out.add(new String[]{line.substring(0, tab), line.substring(tab + 1)});
        }
        return out;
    }

    private static byte hi(int v) {
        return (byte) (v >> 8);
    }

    private static byte lo(int v) {
        return (byte) v;
    }

    private static byte[] sha256(byte[] b) throws CertificateException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (GeneralSecurityException e) {
            throw new CertificateException(e);
        }
    }

    /** A complete message: length, opcode, payload. */
    private static byte[] frame(int op, byte[] payload) {
        byte[] f = new byte[3 + payload.length];
        int n = 1 + payload.length;
        f[0] = (byte) (n >> 8);
        f[1] = (byte) n;
        f[2] = (byte) op;
        System.arraycopy(payload, 0, f, 3, payload.length);
        return f;
    }

    private static void writeFrame(OutputStream out, int op, byte[] a, byte[] b) throws IOException {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        if (a != null) p.write(a, 0, a.length);
        if (b != null) p.write(b, 0, b.length);
        out.write(frame(op, p.toByteArray()));
        out.flush();
    }

    private static byte[] readFrame(InputStream in) throws IOException {
        int h = in.read(), l = in.read();
        if (h < 0 || l < 0) throw new EOFException("connection closed");
        int n = (h << 8) | l;
        if (n == 0) return new byte[]{0};
        byte[] b = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r < 0) throw new EOFException("connection closed");
            got += r;
        }
        return b;
    }
}
