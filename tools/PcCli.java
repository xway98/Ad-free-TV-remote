import app.pocketremote.core.PcSession;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test harness for the laptop protocol, run from a PC against the PC agent (use the agent's --dry mode).
 *   pair  DIR HOST CODE     pairs and stores the token
 *   drive DIR HOST          signs in and sends a short script of moves, clicks, keys and text
 *   bad   DIR HOST          signs in with a wrong token (must be refused)
 */
public class PcCli {
    public static void main(String[] a) throws Exception {
        String cmd = a[0];
        File dir = new File(a[1]);
        String host = a[2];
        dir.mkdirs();

        if (cmd.equals("pair")) {
            PcSession.Paired p = PcSession.pair(host, PcSession.PORT, a[3], "Test phone");
            Files.write(new File(dir, "token").toPath(), p.token);
            Files.write(new File(dir, "fp").toPath(), p.fingerprint);
            System.out.println("PAIRED laptop=" + p.laptopName);
            return;
        }

        byte[] token = Files.readAllBytes(new File(dir, "token").toPath());
        byte[] fp = Files.readAllBytes(new File(dir, "fp").toPath());
        if (cmd.equals("bad")) token[0] ^= 1;
        if (cmd.equals("badfp")) fp[0] ^= 1;

        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        PcSession s = new PcSession(host, PcSession.PORT, token, fp, new PcSession.Listener() {
            public void onReady(String name) { System.out.println("READY " + name); ready.countDown(); }
            public void onClosed(Exception r) { System.out.println("CLOSED " + r); closed.countDown(); }
        });
        try {
            s.connect(2000);
        } catch (Exception e) {
            System.out.println("REFUSED " + e);
            return;
        }
        if (!ready.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("never ready");
        System.out.println("AGENT VERSION " + s.agentVersion());

        if (cmd.equals("drive")) {
            s.move(10, -4);
            s.move(5, 5);                       // merged with the one before while queued
            Thread.sleep(100);
            s.button(PcSession.LEFT, true);
            s.move(40000, 0);                   // bigger than one message holds
            s.button(PcSession.LEFT, false);
            s.click(PcSession.RIGHT);
            s.scroll(-120, 0);
            s.text("héllo wörld € 😀\n");
            s.key(0x41, true);
            s.key(0x41, false);
            s.chord(0x5B, 0x10, 0x52);          // Win+Shift+R
            s.button(PcSession.LEFT, true);     // left held when the connection ends: the agent must release it
            Thread.sleep(3500);                 // lets two pings go and come back
            System.out.println("OPEN after idle: " + s.isOpen());
            s.close();
        } else if (cmd.equals("apps")) {
            final CountDownLatch got = new CountDownLatch(1);
            long t0 = System.currentTimeMillis();
            s.requestApps(list -> {
                System.out.println("APPS " + list.size());
                for (int i = 0; i < Math.min(5, list.size()); i++) System.out.println("  " + list.get(i)[0] + "  =>  " + list.get(i)[1]);
                got.countDown();
            });
            System.out.println("APPS received: " + got.await(30, TimeUnit.SECONDS) + " after " + (System.currentTimeMillis() - t0) + " ms");
            long t1 = System.currentTimeMillis();
            s.requestApps(list -> System.out.println("APPS again " + list.size() + " after " + (System.currentTimeMillis() - t1) + " ms (cached)"));
            Thread.sleep(800);
            final long t2 = System.currentTimeMillis();
            s.requestApps(true, list -> System.out.println("APPS refreshed " + list.size() + " after " + (System.currentTimeMillis() - t2) + " ms (rescanned)"));
            Thread.sleep(4000);
            s.launch("app:Microsoft.WindowsCalculator_8wekyb3d8bbwe!App");
            s.launch("run:C:\\Windows\\notepad.exe\u0001C:\\temp\\x.txt");
            Thread.sleep(500);
        } else if (cmd.equals("windows")) {
            final CountDownLatch got = new CountDownLatch(1);
            s.requestWindows(list -> {
                System.out.println("WINDOWS " + list.size() + " (front: " + (list.isEmpty() ? "-" : list.get(0).process) + ")");
                for (int i = 0; i < Math.min(8, list.size()); i++) {
                    PcSession.Win w = list.get(i);
                    System.out.println("  " + (w.foreground ? "*" : " ") + " " + w.process + "  title length " + w.title.length());
                }
                if (!list.isEmpty()) {
                    s.focusWindow(list.get(0).handle);           // only logged in dry mode
                    s.closeWindow(list.get(0).handle);
                }
                got.countDown();
            });
            System.out.println("WINDOWS received: " + got.await(8, TimeUnit.SECONDS));
            Thread.sleep(300);
        } else if (cmd.equals("clip")) {
            s.setClipboard("héllo clipboard \u20ac");         // only logged in dry mode
            final CountDownLatch got = new CountDownLatch(1);
            s.requestClipboard(text -> {                        // reads the real clipboard: only the length is printed
                System.out.println("CLIP read, " + text.length() + " characters");
                got.countDown();
            });
            System.out.println("CLIP received: " + got.await(8, TimeUnit.SECONDS));
            Thread.sleep(300);
        } else if (cmd.equals("hold")) {
            Thread.sleep(60000);
        }
        System.out.println("DONE");
    }
}
