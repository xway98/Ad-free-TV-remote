package app.pocketremote;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Turns a shortcut written as text ("win+shift+r") into Windows virtual-key codes, and back into something readable. */
final class KeyNames {
    private KeyNames() {}

    static final String HELP = "Join keys with +. Keys: ctrl shift alt win, esc tab enter space backspace delete insert, "
            + "home end pgup pgdn, up down left right, f1 to f12, prtsc, volup voldown mute play next prev, "
            + "letters, digits and . , ; / - = [ ] \\ ` '";

    private static final Map<String, Integer> NAMES = new HashMap<>();

    static {
        put(0x11, "ctrl", "control");
        put(0x10, "shift");
        put(0x12, "alt");
        put(0x5B, "win", "windows", "super", "cmd");
        put(0x1B, "esc", "escape");
        put(0x09, "tab");
        put(0x0D, "enter", "return");
        put(0x20, "space");
        put(0x08, "backspace", "bksp");
        put(0x2E, "delete", "del");
        put(0x2D, "insert", "ins");
        put(0x24, "home");
        put(0x23, "end");
        put(0x21, "pgup", "pageup");
        put(0x22, "pgdn", "pagedown");
        put(0x25, "left");
        put(0x26, "up");
        put(0x27, "right");
        put(0x28, "down");
        put(0x2C, "prtsc", "printscreen");
        put(0x14, "caps");
        put(0x5D, "menu", "apps");
        put(0xAF, "volup");
        put(0xAE, "voldown");
        put(0xAD, "mute");
        put(0xB3, "play");
        put(0xB0, "next");
        put(0xB1, "prev");
        put(0xBB, "plus");
        put(0xBD, "minus");
        for (int i = 1; i <= 24; i++) put(0x6F + i, "f" + i);
        for (char c = 'a'; c <= 'z'; c++) put(c - 'a' + 0x41, String.valueOf(c));
        for (char c = '0'; c <= '9'; c++) put(c - '0' + 0x30, String.valueOf(c));
        put(0xBE, ".");
        put(0xBC, ",");
        put(0xBA, ";");
        put(0xBF, "/");
        put(0xBD, "-");
        put(0xBB, "=");
        put(0xDB, "[");
        put(0xDD, "]");
        put(0xDC, "\\");
        put(0xC0, "`");
        put(0xDE, "'");
    }

    private static void put(int vk, String... names) {
        for (String n : names) NAMES.put(n, vk);
    }

    static boolean isModifier(int vk) {
        return vk == 0x11 || vk == 0x10 || vk == 0x12 || vk == 0x5B;
    }

    /** The codes in press order (modifiers first), or null when something isn't a known key. */
    static int[] parse(String spec) {
        if (spec == null || spec.trim().isEmpty()) return null;
        String s = spec.trim().toLowerCase(Locale.ROOT);
        List<Integer> mods = new ArrayList<>(), keys = new ArrayList<>();
        // a trailing "+" means the plus key itself ("ctrl++")
        if (s.endsWith("++")) s = s.substring(0, s.length() - 2) + "+plus";
        else if (s.equals("+")) s = "plus";
        for (String part : s.split("\\s*\\+\\s*")) {
            Integer vk = NAMES.get(part.trim());
            if (vk == null) return null;
            (isModifier(vk) ? mods : keys).add(vk);
        }
        if (mods.size() + keys.size() == 0) return null;
        List<Integer> all = new ArrayList<>(mods);
        all.addAll(keys);
        int[] r = new int[all.size()];
        for (int i = 0; i < r.length; i++) r[i] = all.get(i);
        return r;
    }

    /** "win+shift+r" as "Win + Shift + R". */
    static String pretty(String spec) {
        StringBuilder sb = new StringBuilder();
        for (String p : spec.trim().split("\\s*\\+\\s*")) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" + ");
            sb.append(p.length() == 1 ? p.toUpperCase(Locale.ROOT) : Character.toUpperCase(p.charAt(0)) + p.substring(1));
        }
        return sb.toString();
    }
}
