# Contributing

Thanks for helping. This is a small project with a few strong preferences, listed below so you don't have to guess.

## Ways to help

- **Tell us what works.** Open an issue with your phone, your TV (or laptop) and what you saw. Compatibility notes are the most useful thing right now: the project has only been tested on one Android TV and one phone.
- **Fix a bug or add a feature.** Small, focused pull requests are easiest to review.
- **Bring another platform.** Ideas that fit the design: a macOS or Linux version of the laptop app (it only has to speak the protocol in [docs/how-it-works.md](docs/how-it-works.md)), support for another TV family, translations.

## Set up

See [docs/building.md](docs/building.md). In short: JDK 17 and the Android SDK for the phone app; nothing for the Windows app.

Before you open a pull request, run:

```powershell
powershell -ExecutionPolicy Bypass -File windows-agent\build.ps1
powershell -ExecutionPolicy Bypass -File tools\test-agent.ps1
powershell -ExecutionPolicy Bypass -File tools\test-installer.ps1
cd android; .\gradlew.bat assembleRelease
```

The tests run the Windows app in a "dry" mode that injects no input, and the installer test works inside a temporary folder, so they are safe to run on your own machine. The same steps run in GitHub Actions on every push.

## What we care about

- **No dependencies.** The phone app uses only the Android framework (plain views, no AndroidX, no Compose) and the Windows app only what ships with Windows. That is why they are tiny and why they build offline. Please don't add a library for something a few lines can do.
- **Calm UI.** One neutral palette; the accent colour marks only what is active. Prefer an icon to a label, and put rarely used things behind the **⋯** sheet rather than on the main screen.
- **Buttons act on release**, and only if the finger stayed put. Anything new that sits in a swipeable area must follow the same rule (see `IconButton` and `KeyButton`).
- **Protocol changes bump the version.** The laptop protocol has a version the phone reads (`Server.Protocol` in `windows-agent/Agent.cs`, `PcSession.agentVersion()`); new messages must be ignored safely by an older app, and the phone must say so when a feature needs a newer laptop app.
- **Comments say why**, not what. Match the surrounding code.
- **Be honest about testing.** If you couldn't try something on a real device, say so in the pull request.

## Project layout

See the end of [README.md](README.md).
