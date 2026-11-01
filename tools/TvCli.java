import app.pocketremote.core.Identity;
import app.pocketremote.core.Pairing;
import app.pocketremote.core.RemoteSession;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test harness for the protocol core, run from a PC against the TV. Not part of the phone app. */
public class TvCli {
    public static void main(String[] a) throws Exception {
        String cmd = a[0];
        File dir = new File(a[1]);
        String host = a[2];

        if (cmd.equals("pair")) {
            Identity id;
            if (Identity.exists(dir)) {
                id = Identity.load(dir);
            } else {
                id = Identity.create("pocketremote");
                id.save(dir);
            }
            try (Pairing p = new Pairing(id, host, "Test client")) {
                p.start();
                System.out.println("CODE_NEEDED");
                System.out.flush();
                File cf = new File(dir, "code.txt");
                long end = System.currentTimeMillis() + 180_000;
                while (!cf.isFile() && System.currentTimeMillis() < end) Thread.sleep(300);
                if (!cf.isFile()) throw new IllegalStateException("no code given");
                Thread.sleep(300);
                String code = new String(Files.readAllBytes(cf.toPath()), StandardCharsets.UTF_8).trim();
                cf.delete();
                byte[] pin = p.finish(code);
                Files.write(new File(dir, "pin.txt").toPath(), hex(pin).getBytes(StandardCharsets.UTF_8));
                System.out.println("PAIRED pin=" + hex(pin));
            }
            return;
        }

        Identity id = Identity.load(dir);
        File pinFile = new File(dir, "pin.txt");
        byte[] pin = pinFile.isFile() && !cmd.equals("nopin")
                ? unhex(new String(Files.readAllBytes(pinFile.toPath()), StandardCharsets.UTF_8).trim()) : null;

        final CountDownLatch ready = new CountDownLatch(1);
        RemoteSession s = new RemoteSession(id, host, pin, new RemoteSession.Listener() {
            public void onReady() { System.out.println("READY"); ready.countDown(); }
            public void onVolume(int level, int max, boolean muted) { System.out.println("VOLUME " + level + "/" + max + " muted=" + muted); }
            public void onPower(boolean on) { System.out.println("POWER on=" + on); }
            public void onClosed(Exception r) { System.out.println("CLOSED " + r); }
        });
        s.connect();
        if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("TV never became ready");
        Thread.sleep(500);

        if (cmd.equals("keys")) {
            for (int i = 3; i < a.length; i++) {
                s.sendKey(Integer.parseInt(a[i]));
                System.out.println("SENT " + a[i]);
                Thread.sleep(500);
            }
        } else if (cmd.equals("applink")) {
            s.launchApp(a[3]);
            System.out.println("LAUNCH " + a[3]);
        } else if (cmd.equals("text")) {
            s.sendText(a[3]);
            System.out.println("TEXT " + a[3]);
        }
        Thread.sleep(2500);
        s.close();
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}
