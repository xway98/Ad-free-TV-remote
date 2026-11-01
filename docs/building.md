# Building

## What you need

| For | You need |
|---|---|
| The Windows app | Windows 10 or 11. Nothing else: the build uses `csc.exe`, the C# compiler that ships with the .NET Framework. |
| The phone app | JDK 17, and the Android SDK with platform 34 and build-tools. Set `JAVA_HOME` and `ANDROID_HOME` (or put `sdk.dir=...` in `android/local.properties`, which is git-ignored). |
| The tests | A JDK 17 (the same one). |

The phone app has **no dependencies** besides the Android Gradle plugin, so it builds offline once Gradle and the plugin are cached.

## Everything at once

```powershell
powershell -ExecutionPolicy Bypass -File scripts\release.ps1
```

Output in `dist\`: `PocketRemotePc-Setup.exe`, `PocketRemote.apk`, `SHA256SUMS.txt`.

## One part at a time

```powershell
# Windows app: builds PocketRemotePc.exe (installer + app) and a console twin used by the tests
powershell -ExecutionPolicy Bypass -File windows-agent\build.ps1

# Phone app
cd android
.\gradlew.bat assembleRelease        # android\app\build\outputs\apk\release\app-release.apk
```

## Tests

```powershell
powershell -ExecutionPolicy Bypass -File tools\test-agent.ps1       # pairing, refusals, session, lists, gyro maths
powershell -ExecutionPolicy Bypass -File tools\test-installer.ps1   # install, run, uninstall in a temp folder
```

Both are safe to run: the Windows app runs in `--dry` mode (nothing is injected), and the installer runs with `--sandbox` (no registry, Start menu, startup or firewall changes). They exit non-zero on a failure, and the GitHub Actions workflow in `.github/workflows/build.yml` runs them on every push.

`tools/PcCli.java` and `tools/TvCli.java` are small command-line clients for the laptop and TV protocols, handy when changing the protocol code without a phone.

## Running the Windows app without installing it

```powershell
windows-agent\PocketRemotePc.exe --agent        # runs the tray app from where it is, without installing
windows-agent\PocketRemotePc-console.exe --agent --dry --nogui --pair --data C:\temp\pr-data
```

Opened without options from anywhere else, the exe is the installer. Other options: `--silent`, `--install-dir DIR`, `--no-startup`, `--no-shortcut`, `--no-firewall`, `--public`, `--uninstall`, `--sandbox` (tests only).

## Releasing

Push a tag such as `v1.0.0`: the workflow builds everything, runs the tests, and attaches `dist\*` to a GitHub release.

The APK is signed with the Android debug key (see `android/app/build.gradle`) so anyone can build and sideload it. To publish under your own identity, add your own `signingConfigs` entry with your keystore and point the `release` build type at it. Keep the keystore and its passwords out of the repository.

## Changing the app id

The package and application id are `app.pocketremote`. To use your own, rename the folders under `android/app/src/main/java/app/pocketremote` and `core/src/app/pocketremote`, replace the id in every `package` and `import` line, and set `namespace` and `applicationId` in `android/app/build.gradle`. A different id installs as a different app, so it won't share pairings with the original.

## On Linux or macOS

The phone app builds anywhere Gradle runs: `cd android && chmod +x gradlew && ./gradlew assembleRelease`. The Windows app and the scripts in this repository are Windows-only (the Windows app is built with `csc.exe` and uses Win32 calls), so you can build and test the phone app, but not the laptop app, there.
