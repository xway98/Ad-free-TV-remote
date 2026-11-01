package app.pocketremote.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import javax.net.ssl.SSLSocket;

/** A live connection to a paired TV on port 6466: send keys, text and app links, hear volume and power. */
public final class RemoteSession implements Closeable {
    public static final int PORT = 6466;

    /** Feature bits the TV and the client agree on. */
    private static final int PING = 1, KEY = 2, IME = 4, POWER = 32, VOLUME = 64, APP_LINK = 512;
    private static final int WANTED = PING | KEY | IME | POWER | VOLUME | APP_LINK;

    public static final int SHORT = 3, START_LONG = 1, END_LONG = 2;

    public interface Listener {
        void onReady();

        void onVolume(int level, int max, boolean muted);

        void onPower(boolean on);

        void onClosed(Exception reason);

        /** The TV put the cursor in a text field and wants text. */
        default void onKeyboard() {}

        /** What the TV's text field holds right now (empty when it is empty). */
        default void onTextField(String text) {}
    }

    private final Identity identity;
    private final String host;
    private final byte[] pin;
    private final Listener listener;

    private volatile SSLSocket socket;
    private OutputStream out;
    private int active = PING | KEY;
    private int imeCounter;
    private int fieldCounter;
    private volatile boolean closed;

    public RemoteSession(Identity identity, String host, byte[] pin, Listener listener) {
        this.identity = identity;
        this.host = host;
        this.pin = pin;
        this.listener = listener;
    }

    /** Connects and starts reading in the background. {@link Listener#onReady()} fires once the TV accepts commands. */
    public void connect() throws IOException {
        // The TV pings every 5 seconds, so 20 seconds of silence means the connection is gone.
        socket = identity.connect(host, PORT, pin, 20000);
        out = socket.getOutputStream();
        final InputStream in = socket.getInputStream();
        Thread t = new Thread(() -> readLoop(in), "tv-remote-reader");
        t.setDaemon(true);
        t.start();
    }

    public void sendKey(int keyCode, int direction) {
        send(new Pb.Writer().message(10, new Pb.Writer().varint(1, keyCode).varint(2, direction)));
    }

    public void sendKey(int keyCode) {
        sendKey(keyCode, SHORT);
    }

    public void sendText(String text) {
        if (text == null || text.isEmpty()) return;
        int last = text.length() - 1;
        Pb.Writer field = new Pb.Writer().varint(1, last).varint(2, last).string(3, text);
        Pb.Writer edit = new Pb.Writer().varint(1, 1).message(2, field);
        send(new Pb.Writer().message(21, new Pb.Writer().varint(1, imeCounter).varint(2, fieldCounter).message(3, edit)));
    }

    public void launchApp(String link) {
        send(new Pb.Writer().message(90, new Pb.Writer().string(1, link)));
    }

    public boolean isOpen() {
        SSLSocket s = socket;
        return s != null && !closed && !s.isClosed();
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
    }

    private void send(Pb.Writer msg) {
        OutputStream o = out;
        if (o == null || closed) return;
        try {
            synchronized (this) {
                Pb.writeFrame(o, msg.toBytes());
            }
        } catch (IOException e) {
            fail(e);
        }
    }

    private void readLoop(InputStream in) {
        try {
            while (!closed) handle(Pb.parse(Pb.readFrame(in)));
        } catch (IOException | RuntimeException e) {
            fail(e);
        }
    }

    private void handle(List<Pb.Field> msg) {
        Pb.Field f;
        if ((f = Pb.find(msg, 1)) != null) {                       // remote_configure
            Pb.Field features = Pb.find(f.sub(), 1);
            active = (features == null ? PING | KEY : (int) features.num) & WANTED;
            Pb.Writer info = new Pb.Writer().varint(3, 1).string(4, "1").string(5, "atvremote").string(6, "1.0.0");
            send(new Pb.Writer().message(1, new Pb.Writer().varint(1, active).message(2, info)));
        } else if (Pb.find(msg, 2) != null) {                      // remote_set_active
            send(new Pb.Writer().message(2, new Pb.Writer().varint(1, active)));
        } else if ((f = Pb.find(msg, 8)) != null) {               // ping
            Pb.Field v = Pb.find(f.sub(), 1);
            send(new Pb.Writer().message(9, new Pb.Writer().varint(1, v == null ? 0 : v.num)));
        } else if ((f = Pb.find(msg, 40)) != null) {              // remote_start
            Pb.Field started = Pb.find(f.sub(), 1);
            listener.onPower(started != null && started.num != 0);
            listener.onReady();
        } else if ((f = Pb.find(msg, 50)) != null) {              // volume
            List<Pb.Field> v = f.sub();
            Pb.Field max = Pb.find(v, 6), level = Pb.find(v, 7), muted = Pb.find(v, 8);
            listener.onVolume(level == null ? 0 : (int) level.num, max == null ? 0 : (int) max.num,
                    muted != null && muted.num != 0);
        } else if ((f = Pb.find(msg, 22)) != null) {              // keyboard requested by a text field
            listener.onKeyboard();
            reportText(f.sub());
        } else if ((f = Pb.find(msg, 20)) != null) {              // the text field changed on the TV
            reportText(f.sub());
        } else if ((f = Pb.find(msg, 21)) != null) {              // keyboard counters
            List<Pb.Field> v = f.sub();
            Pb.Field a = Pb.find(v, 1), b = Pb.find(v, 2);
            if (a != null) imeCounter = (int) a.num;
            if (b != null) fieldCounter = (int) b.num;
        }
    }

    /** The field's status is field 2 of these messages; its text is field 2 again, and absent when the field is empty. */
    private void reportText(List<Pb.Field> msg) {
        try {
            Pb.Field status = Pb.find(msg, 2);
            if (status == null || status.wire != 2) return;
            Pb.Field value = Pb.find(status.sub(), 2);
            if (value == null) listener.onTextField("");
            else if (value.wire == 2) listener.onTextField(value.str());
        } catch (RuntimeException ignored) {
            // not the shape we expected: leave the text box alone
        }
    }

    private void fail(Exception e) {
        if (closed) return;
        close();
        listener.onClosed(e);
    }
}
