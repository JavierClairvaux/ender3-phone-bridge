# Printer Bridge: Android app

A native Android app that runs on the phone attached to the Ender 3 and replaces the Termux scripts in the parent directory for everyday printing. It embeds the unmodified `ch340_serial.py` driver through [Chaquopy](https://chaquo.com/chaquopy/) (CPython inside the app) and talks to the printer through Android's USB Host API.

Features:

- A foreground service that owns the USB connection and the print job, and survives backgrounding and screen-off.
- A native status UI, a web dashboard, a REST API and an MCP server (for Claude Code and other MCP clients).
- Pause with park, retract and reheat; resume restores the exact position and state.
- Telegram notifications for print done and print error.
- A built-in simulated Marlin (`fake` and `sim-usb` backends) for testing without a printer.

## Status

Tested on a Redmi Note 9 Pro (LineageOS 22.2) with a stock Ender 3 (Marlin 1.1.6.2).

Verified on real hardware:
- Connecting and reconnecting without resetting the board.
- Heating, homing and leveling moves from the API and MCP.
- Full prints of 7,329, 147,423 and 352,009 lines (the longest took 4h23m), all with zero resends.
- Dashboard, REST and MCP over Wi-Fi, and Telegram notifications to a real chat.

Only tested against the simulator so far:
- Pause, park and resume on a real print, including pausing during heat-up.
- Unplug and replug during a print, the USB permission-denied path, and `/api/reset_board`.
- Doze and battery-optimization behavior on the phone over a long print.

## Quick start

Needs JDK 21, the Android SDK and a phone with wireless ADB (the printer occupies the USB port).

```bash
./gradlew -Pabi=arm64-v8a -Pbackend=real assembleDebug     # phone build
./gradlew -Pabi=x86_64 assembleDebug                        # emulator build
./deploy_phone.sh connect <phone-ip>:<adb-port>
./deploy_phone.sh install && ./deploy_phone.sh start
```

Then open `http://<phone-ip>:8080/`. Update with `adb install -r`; never uninstall, which wipes the settings, API token and USB permission.

## Docs

- [`docs/USAGE.md`](docs/USAGE.md): install, backends, the safety lock, printing, pause and resume, leveling, Telegram, recovery and testing.
- [`docs/API.md`](docs/API.md): REST and MCP reference with request and response examples.
- [`docs/DESIGN.md`](docs/DESIGN.md): architecture, the hardware findings behind it, known limitations and the real-hardware test plan.

## Security notes

- Control endpoints and `/mcp` need an API token over plain HTTP. Use a trusted LAN or Tailscale.
- Known issue: the service logs the API token at startup, so don't share logcat output.
- The Telegram bot token and chat ID are entered in the app and are never stored in this repo.

`app/src/main/python/ch340_serial.py` is a copy of the driver in the parent directory. `spike-archive/` holds the early Chaquopy spike code (including DTR-toggling test suites that must never ship). It isn't compiled.
