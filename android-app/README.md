# Printer Bridge: Android app

A native Android app that runs on the phone attached to the Ender 3 and replaces the Termux scripts in the parent directory for everyday printing. It embeds the unmodified `ch340_serial.py` driver through [Chaquopy](https://chaquo.com/chaquopy/) (CPython 3.13 inside the app) and talks to the printer through Android's USB Host API.

Features:

- A foreground service that owns the USB connection and the print job, and survives backgrounding and screen-off.
- A native status UI, a web dashboard, a REST API and an MCP server (for Claude Code and other MCP clients).
- Pause with park, retract and reheat; resume restores the exact position and state.
- Telegram notifications for print done and print error.
- Optional HTTPS: a self-signed certificate out of the box, or a Let's Encrypt certificate the app obtains and renews itself (DNS-01 through the GoDaddy API). Plain HTTP stays the default.
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

TLS (HTTPS) status: the app obtained a production Let's Encrypt certificate for `printer.theconsortio.xyz` on the phone (DNS-01 through GoDaddy) and serves it over HTTPS, verified with the system trust store (REST and MCP). Staging was verified first. No print has run over HTTPS yet.

## Quick start

Needs JDK 21, the Android SDK, a **Python 3.13** interpreter for the build, and a phone with wireless ADB (the printer occupies the USB port).

The app embeds Python 3.13 (Chaquopy), and the build needs a matching 3.13 interpreter to precompile the Python sources. By default it looks for `python3.13` on `PATH`. One way to get it without touching the system Python: `uv python install 3.13` (puts `python3.13` in `~/.local/bin`). Or point the build at any 3.13 interpreter with `-PbuildPython=/path/to/python3.13`. The build stops with an explanatory error if none is found.

```bash
./gradlew -Pabi=arm64-v8a -Pbackend=real assembleDebug     # phone build
./gradlew -Pabi=x86_64 assembleDebug                        # emulator build
# add -PbuildPython=/path/to/python3.13 if python3.13 isn't on PATH
./deploy_phone.sh connect <phone-ip>:<adb-port>
./deploy_phone.sh install && ./deploy_phone.sh start
```

Then open `http://<phone-ip>:8080/`. Update with `adb install -r`; never uninstall, which wipes the settings, API token and USB permission.

## Docs

- [`docs/USAGE.md`](docs/USAGE.md): install, backends, the safety lock, printing, pause and resume, leveling, Telegram, HTTPS and Let's Encrypt, recovery and testing.
- [`docs/API.md`](docs/API.md): REST and MCP reference with request and response examples.
- [`docs/DESIGN.md`](docs/DESIGN.md): architecture, the hardware findings behind it, known limitations and the real-hardware test plan.

## Security notes

- Control endpoints and `/mcp` need an API token. Over plain HTTP the token travels in clear text: use a trusted LAN or Tailscale, or turn on HTTPS ([USAGE.md](docs/USAGE.md#https-tls-and-lets-encrypt)).
- The service no longer logs the API token, but logcat still shows other operational details, so share it with care.
- The Telegram bot token and chat ID are entered in the app and are never stored in this repo.
- GoDaddy API credentials (for Let's Encrypt DNS-01) are stored only on the phone, encrypted with an Android Keystore key, and are never returned by the API or logged. They can change every DNS record of the domain; see the threat model in [DESIGN.md](docs/DESIGN.md#tls-and-acme).

`app/src/main/python/ch340_serial.py` is a copy of the driver in the parent directory. `spike-archive/` holds the early Chaquopy spike code (including DTR-toggling test suites that must never ship). It isn't compiled.
