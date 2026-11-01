# How it works

Three parts: the **phone app**, the **shared protocol code** (`core/`), and the **Windows app** (`windows-agent/`). The TV needs nothing installed.

```
            Wi-Fi                          Wi-Fi
 phone  ---------------  Android TV      phone  ---------------  Windows laptop
 (app)   Android TV Remote protocol 6466/6467   (app)   Pocket Remote protocol 47891, announce 47892
```

## The TV side

The phone speaks the **Android TV Remote protocol v2**, the same one Google's own remote app uses: a TLS connection to port 6466 carrying length-prefixed protobuf messages. The code (`core/.../RemoteSession.java`, `Pairing.java`, `Identity.java`, `Pb.java`) contains just enough protobuf (varints and length-delimited fields) to speak it, so there is no protobuf library.

- **Finding TVs:** the app browses for `_androidtvremote2._tcp` with Android's network service discovery (`Discovery.java`), or you type the IP address.
- **Pairing:** on port 6467 the phone and TV run a secret-code exchange (the TV shows a 6-character code; both sides hash it with the two certificates). The phone keeps its own self-signed certificate (`Identity.java`), which the TV remembers. The result is a pin the phone stores.
- **Using it:** keys are sent as key events; text is sent to the focused text field; apps and links are opened with the "app link" message (`market://launch?id=<package>` for apps, a plain URL for links).
- **Typing** mirrors the TV's field: when the TV reports the field's text the box starts with it, and every edit on the phone is sent as the smallest change (backspaces for what was deleted, text for what was added). If a TV doesn't report its text, typing still works but the box starts empty.

## The laptop side

The Windows app (`windows-agent/Agent.cs`) is a tray program that listens on **TCP 47891** over TLS 1.2 and announces itself with a **UDP broadcast on 47892** every two seconds, and again the moment the network changes. It injects input with `SendInput`. The installer (`Setup.cs`) is the same exe: opened from outside its install folder it is the setup window; from inside it, it is the app.

### Messages

Every message is `[u16 big-endian length][payload]`; `payload[0]` is the opcode; numbers are big-endian.

| Opcode | Name | Payload |
|---|---|---|
| `0x01` | Auth | token (32 bytes) |
| `0x02` | Pair | HMAC-SHA-256 (32 bytes), phone name |
| `0x10` | Move | i16 dx, i16 dy (relative pixels; the phone merges queued moves) |
| `0x11` | Button | button (1 left, 2 right, 3 middle, 4 back, 5 forward), down (0/1) |
| `0x12` | Scroll | i16 vertical, i16 horizontal (120 = one notch; positive = up/right) |
| `0x20` | Key | u16 virtual key, down (0/1) |
| `0x21` | Text | UTF-8 text typed as-is |
| `0x22` | Chord | count, then that many u16 keys: pressed in order, released in reverse |
| `0x30` | System | 1 lock, 2 sleep, 3 restart, 4 shut down, 5 screen off |
| `0x31` | Launch | `app:<Start menu app id>` or `run:<path or link>[\u0001arguments]` |
| `0x32` | ListApps | optional u8: 1 = rescan |
| `0x33` | ListWindows | |
| `0x34` / `0x35` | FocusWindow / CloseWindow | i64 window handle |
| `0x36` | ClipSet | UTF-8 text |
| `0x37` | ClipGet | |
| `0x40` | Ping | |
| `0x50` | ReleaseAll | |
| `0x41` | Pong | |
| `0x80` | AuthOk | `name\nprotocol version` |
| `0x81` | PairOk | token (32 bytes), laptop name |
| `0x82` | Apps | lines of `app:<id>\tname` |
| `0x83` | Windows | lines of `handle\tforeground\tprocess\ttitle`, front-most first |
| `0x84` | Clip | UTF-8 text |
| `0x8E` | Denied | reason |

**Versions.** 1 original; 2 opens apps; 3 can rescan the app list; 4 lists windows and shares the clipboard. The phone reads the version from `AuthOk` and tells you when a feature needs a newer laptop app. Older apps ignore opcodes they don't know.

### Pairing and trust

1. On the laptop, **Pair new phone** opens a two-minute window with a random 6-digit code.
2. The phone connects over TLS, accepting the laptop's self-signed certificate this once, and remembers its SHA-256 fingerprint.
3. It sends `Pair` with `HMAC-SHA-256(key = code, message = certificate fingerprint)`. A man in the middle presents a different certificate, so he can't produce the right MAC without the code.
4. The laptop checks it, hands back a random token, and stores only the token's hash. After five wrong tries the window closes.
5. From then on the phone connects with the **pinned fingerprint** (a changed certificate is refused) and sends `Auth` with the token.

A newer connection from the same phone replaces a stale one, which is what makes reconnecting after a Wi-Fi blip instant.

### Staying connected (`PcLink.java`)

One connection per app, reference-counted: the screen and the optional background service each hold it. The phone pings every two seconds and gives up on a connection that has been silent for six, then reconnects at once. A broadcast from the laptop (new IP, or just switched on) wakes the retry loop immediately. While connected it holds a low-latency Wi-Fi lock.

### Pointer feel

- **Trackpad** (`MousePadView.java`): finger movement is turned into whole pixels with the fraction carried over, with a speed-dependent gain (slow = precise, fast = far). A touch that doesn't travel is a tap, so the pointer never jumps on a click.
- **Air mouse** (`AirPadView.java`, `core/.../AirMath.java`): uses the gyroscope's *rate*, not an angle. The rate is projected onto the world's vertical (turn left/right) and onto the horizontal axis across the phone's heading (point up/down), using gravity, so it works held flat, upright or tilted. A dead zone and speed-aware smoothing keep a steady hand steady, and the first 70 ms of a touch are ignored so pressing the glass doesn't move the pointer. `tools/AirMathTest.java` checks the projection.
- **Moves are merged** while they wait to be sent, so a slow moment on the network becomes one bigger move instead of a queue of stale ones.

## Phone app structure

`MainActivity` chooses what to show from the saved choice (TV, laptop or both); `LaptopUi` is the whole laptop side; `PcLink` and `BackgroundService` hold the connection; the rest are small custom views (`IconButton`, `KeyButton`, `DockButton`, `MousePadView`, `AirPadView`, `DPadView`, `TouchpadView`, `Sheet`, ...). There are no layouts in XML: the screens are built in code, which keeps the app tiny and the look consistent.