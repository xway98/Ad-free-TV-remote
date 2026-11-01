# Security

Pocket Remote controls real devices, so this page says plainly what it protects, what it doesn't, and how to report a problem.

## How it protects you

- **Local only.** The phone talks directly to your TV and your laptop on your own network. There is no server, no account and no analytics. The app has the internet permission only because Android requires it for any network connection.
- **TV:** it uses the same protocol as Google's own remote app. Pairing is done once with a code the TV shows; the TV remembers the phone's certificate.
- **Laptop:** the connection is TLS 1.2. The first time, you type a 6-digit code shown on the laptop. The phone sends a keyed hash of the laptop's certificate fingerprint (HMAC-SHA-256 with the code as key), so someone in the middle can't pair in your place without knowing the code. The code works for two minutes and for five wrong tries. Pairing hands the phone a random 32-byte token; the laptop stores only a hash of it. Every later connection pins the laptop's certificate and presents the token. A connection that fails either check is refused.
- **Stored secrets:** on the phone, the token and certificate fingerprint are in the app's private storage (backups are off). On the laptop, the certificate's private key is encrypted for your Windows user (DPAPI) in `%APPDATA%\PocketRemotePc`.
- **Stuck input:** if a connection drops while a key or mouse button is held, the laptop releases it.

## What it does not protect against

- **A paired phone is fully trusted.** It can move the mouse, type, press shortcuts, open any program or link, read and write the clipboard, and lock, sleep or shut down the laptop. That is the point of a remote, but it means: **only pair phones you own**, and use **Forget all phones** in the tray menu if a phone is lost.
- **Your Wi-Fi.** Anyone on the network can see that the laptop is there (it announces itself with a small UDP broadcast on port 47892) and can try to connect to port 47891. They can't do anything without a paired token, but a hostile network is not the place to leave the Windows app running. The setup's firewall rule is limited to Private and Domain networks unless you tick the Public option.
- **Unsigned binaries.** The Windows installer is not code-signed, and the APK is signed with the Android debug key so anyone can build it. Check the `SHA256SUMS.txt` published with each release, or build from source.
- **Elevated windows.** The Windows app runs as you and can't control programs running as administrator.

## Reporting a problem

Please report security issues privately, not in a public issue: use GitHub's **Security → Report a vulnerability** on this repository. Include what you did, what happened and what you expected. You'll get a reply as soon as someone can look at it.
