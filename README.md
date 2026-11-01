# Pocket Remote

**An ad-free remote** that turns your Android phone into a remote for your **Android TV / Google TV**, your **Windows laptop**, or both.

- Free and open source (MIT). No ads, no account, no analytics, no cloud: everything stays on your Wi-Fi.
- Small: the phone app is about 70 KB and has no libraries; the Windows app is one 60 KB file.
- Calm to use: one screen per device, icons instead of clutter, buttons that never fire when you swipe past them.

> Screenshots go in [`docs/screenshots`](docs/screenshots). Add a few before publishing.

## Pick what you use

The first time you open the app it asks what you want to control. You can change it later from the **⋯** menu, and your pairings are kept.

| You choose | You get | You install |
|---|---|---|
| **TV** | The Android TV remote | The phone app |
| **Laptop** | Mouse, air mouse, keyboard, shortcuts, apps | The phone app and the Windows app |
| **TV and laptop** | Both, with a TV / Laptop switch at the top | The phone app and the Windows app |

It is one APK; the choice only decides which screens you see.

## What it can do

### TV (Android TV and Google TV)
- **D-pad or touchpad.** The touchpad is fast: swipe to move (a quick swipe goes further), tap for OK, hold for a long OK, two-finger tap for Back, two-finger swipe to scroll long lists.
- **Volume, media and navigation:** volume rocker, Back, Home, Search, Menu, rewind, play/pause, fast-forward, mute. The phone's own volume buttons control the TV too.
- **Typing on the TV** that mirrors the TV's text box live (no Send button), so backspace works on text that is already there.
- **App shortcuts** you edit yourself, and **Share to TV**: share a link from any Android app and it opens on the TV.
- **More (⋯):** Inputs, Settings and Info buttons, Reconnect, Change TV.
- Every button acts when you lift your finger, and only if it stayed put, so scrolling past a button never presses it.

### Laptop (Windows 10 / 11)
- **Mouse** with acceleration, tap-to-click, tap-and-drag, two-finger scroll and right-click, a scroll strip, and Back / Forward / Left / Right buttons that hold, so you can drag.
- **Air mouse:** hold your thumb on the circle and turn your wrist. The pointer follows the gyroscope like a mouse lifted off the desk, so nothing drifts. Tap anywhere outside the circle to click, hold there to right-click.
- **Keyboard** that slides up over the mouse screen: live typing (Unicode works), sticky Ctrl / Shift / Alt / Win, Esc, Tab, arrows, F-keys, and a clipboard bridge between phone and laptop.
- **Shortcuts:** a pad of key combinations (Win+Shift+S, Alt+Tab, Ctrl+Shift+Esc, ...) that you can edit and add to.
- **Apps:** tiles that open an app on the laptop, or **switch to it if it is already open**, plus a list of open windows to bring forward or close.
- **More (⋯):** media and volume, lock / sleep / restart / shut down, speeds, and **Stay connected when the app is closed**.
- **Share from any app** to the laptop: open a link there, or copy or type text on it.
- The phone connects the moment the laptop switches on, and stays connected.

## What is supported

| | |
|---|---|
| Phone | Android 8.0 or newer. The air mouse needs a gyroscope. |
| TV | Android TV or Google TV: anything the official **Google TV / Android TV Remote** app works with. |
| Laptop | Windows 10 (1903 or newer) or Windows 11, 64-bit. Nothing else to install: it uses the .NET Framework that comes with Windows. |

Not supported: Samsung (Tizen), LG (webOS), Roku and other TVs that don't run Android TV. They use different protocols. Contributions are welcome, see [CONTRIBUTING.md](CONTRIBUTING.md).

Known limits:
- A TV in standby can't be woken over Wi-Fi: its remote service is off. Use the physical remote to wake it.
- The Windows app can't type into the lock screen or UAC prompts, or into windows running as administrator (run it as administrator for those). Ctrl+Alt+Del can't be sent by any program.
- What the TV's **Inputs / Settings / Info** buttons do depends on the TV.
- It has been tested on one Android TV and one phone so far. Tell us what works and what doesn't on yours.

## Install

Download the files from the [latest release](../../releases/latest):

**1. The phone app (`PocketRemote.apk`).** Copy it to your phone and open it. Android asks to allow installs from the app you opened it with (Files, Chrome...): allow it once.

**2. The Windows app (`PocketRemotePc-Setup.exe`), only if you chose Laptop.** Double-click it on the laptop. A setup window explains what it does and offers four options:
- start with Windows (recommended: the phone connects as soon as the laptop is on),
- a Start menu shortcut,
- a Windows Firewall rule so the phone can connect (Windows asks for permission once),
- "my Wi-Fi is marked Public": setup checks this for you. Windows hides your laptop from other devices on Public networks unless this is on.

Click **Install**, then **Finish**. It installs for your user only and lives in the tray (bottom right). Windows may show a "Windows protected your PC" notice because the installer isn't code-signed: choose **More info → Run anyway**, or build it yourself (see [docs/building.md](docs/building.md)). To uninstall: Windows **Settings → Apps → Pocket Remote for PC**.

To update the Windows app, run the newer setup and click **Update**. Pairings are kept.

## Pair

**TV:** turn the TV on and join the phone to the same Wi-Fi. Pick your TV from the list (or type its IP address), then enter the 6-character code the TV shows.

**Laptop:** on the laptop, right-click the tray icon → **Pair new phone**: it shows a 6-digit code and the laptop's IP address. In the phone app, pick the laptop (or type its IP) and enter the code. The code works for two minutes, and it is tied to the laptop's certificate, so nobody else on your Wi-Fi can pair in your place.

## Privacy and security

Nothing leaves your network, and the app asks for no account. Pairing is done once with a code; after that the phone proves itself with a random token over TLS with a pinned certificate. **Anyone you pair can control the laptop and open programs on it, so only pair your own phone.** Details and how to report a problem: [SECURITY.md](SECURITY.md).

## Troubleshooting

The most common problems (the phone can't find the laptop, the TV isn't listed, "the laptop app is out of date") are covered in [docs/troubleshooting.md](docs/troubleshooting.md).

## Build it yourself

```powershell
powershell -ExecutionPolicy Bypass -File scripts\release.ps1
```

This builds `dist\PocketRemotePc-Setup.exe` and `dist\PocketRemote.apk`. You need JDK 17 and the Android SDK for the phone app; the Windows app needs nothing installed. Full steps: [docs/building.md](docs/building.md). How it works inside: [docs/how-it-works.md](docs/how-it-works.md).

## Project layout

```
android/          the phone app (plain Android views, no libraries)
core/             protocol code shared by the app and the tests (plain Java)
windows-agent/    the Windows installer and laptop app (one C# file pair, built with the compiler that ships with Windows)
tools/            test clients and the end-to-end tests
scripts/          release script
docs/             how it works, building, troubleshooting
```

## Contributing

Bug reports, TV and phone compatibility notes, and pull requests are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE).
