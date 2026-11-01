# Troubleshooting

## The phone can't find the laptop

Work through these in order:

1. **Same Wi-Fi?** The phone and the laptop must be on the same network. A guest network, or a router with "client isolation" / "AP isolation" on, blocks them from seeing each other.
2. **Is the Windows app running?** Look for its icon in the tray (bottom right, maybe under the ^ arrow). If it isn't there, start **Pocket Remote for PC** from the Start menu.
3. **Windows calls your Wi-Fi "Public".** This is the most common cause. Windows hides the laptop from other devices on Public networks. Re-run the setup and tick **My Wi-Fi is marked Public** (setup ticks it for you when it detects this), or set the network to Private in **Settings → Network & internet → Wi-Fi → your network**.
4. **A VPN or another firewall** on the laptop can block incoming connections. Allow `PocketRemotePc.exe` on TCP port 47891.
5. **Type the IP address.** The laptop's pairing window shows it. Discovery uses a broadcast that some routers drop; the IP always works.

## "The code doesn't work" (laptop)

The code is valid for two minutes and for five wrong tries. Right-click the tray icon → **Pair new phone** for a fresh one.

## "The laptop app is out of date"

The phone app has a feature the laptop app doesn't know yet. Run the newest `PocketRemotePc-Setup.exe` on the laptop and click **Update**. Your pairing is kept.

## The laptop disconnects or doesn't reconnect

- When the laptop sleeps, the connection ends; the phone reconnects within a second or two of it waking, if the app is open or **Stay connected when the app is closed** is on (**⋯ → More** on the laptop side).
- Some phones stop background apps aggressively. If the notification keeps disappearing, exclude Pocket Remote from battery optimisation in Android's settings.
- If the laptop's address changed, the phone follows it automatically when it hears the laptop's announcement.

## The TV isn't listed

- The TV must be on (not standby) and on the same Wi-Fi.
- It must support Google's **Android TV Remote Service**: if Google's own Google TV / Android TV Remote app can control it, this will. Other TV families (Samsung, LG, Roku) aren't supported.
- Type the TV's IP address instead (Settings → Network on the TV shows it).

## Pairing with the TV fails

Check the code (6 characters, letters and digits), then try again. To start from scratch, clear the TV's saved pairings: on the TV, **Settings → Apps → See all apps → Show system apps → Android TV Remote Service → Storage → Clear data**. That also unpairs Google's own remote app.

## The TV's Inputs / Settings / Info buttons do nothing

They send the standard remote keys; whether a TV acts on them depends on the TV and its launcher. Please open an issue with your TV model.

## Windows says "Windows protected your PC"

The installer isn't code-signed (that costs money each year). Choose **More info → Run anyway**, check the file against `SHA256SUMS.txt` from the release, or [build it yourself](building.md).

## Apps don't show a newly installed program

In **Apps → Launcher → + Add app → Choose from the laptop's apps**, tap **Refresh**.

## Remove everything

- **Laptop:** Windows **Settings → Apps → Pocket Remote for PC → Uninstall**. It asks whether to forget paired phones too.
- **Phone:** uninstall the app. That forgets its TV and laptop pairings.