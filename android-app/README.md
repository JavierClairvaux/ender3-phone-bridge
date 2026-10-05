# Printer Bridge: Android app

A native Android app that runs on the phone attached to the Ender 3 and replaces the Termux scripts in the parent directory for everyday printing. It embeds the unmodified `ch340_serial.py` driver through [Chaquopy](https://chaquo.com/chaquopy/) (CPython inside the app) and talks to the printer through Android's USB Host API.

It provides a foreground service that owns the connection and the print job, a native status UI, a web dashboard, a REST API, an MCP server (for Claude Code and other MCP clients) and Telegram notifications for print done/error.

## Status

Proven on real hardware (Redmi Note 9 Pro, LineageOS 22.2, Ender 3 / Marlin 1.1.6.2): connecting without resetting the board, heating, homing, leveling moves and full prints (a 7,329-line cube and a 4h23m, 352,009-line part, both with zero resends). Everything else (pause/park/reheat on a real print, unplug/replug, power-loss handling) is only tested against the built-in simulator. See `APP_SUMMARY.md` for what was and wasn't verified.

Important design points, all learned on real hardware:

- RX uses a queued reader (four 512-byte `UsbRequest`s kept in flight). Per-packet synchronous reads dropped bytes on the CH340.
- DTR/RTS are never toggled while connected. Opening the USB device does not reset Marlin, only a DTR edge does, so the app can reconnect without disturbing a print or losing position.
- `M85 S0` is sent whenever a job ends. On this firmware an `M85` timeout calls `kill()` and halts the board.

## Docs

- `APP_SUMMARY.md`: how to build, install, use, endpoints, validation evidence and remaining work.
- `SUMMARY.md`, `PHASE2_SUMMARY.md`: the two spikes that proved Chaquopy + the real driver (simulated device, then real hardware).

## Build

```bash
./gradlew -Pabi=arm64-v8a -Pbackend=real assembleDebug   # phone
./gradlew -Pabi=x86_64 assembleDebug                      # emulator
```

Needs JDK 21 and the Android SDK (`local.properties` is not checked in). Install with `adb install -r` over the existing app to keep its settings and USB permission.

## Security notes

- Control endpoints and the MCP endpoint require an API token. Plain HTTP: use a trusted LAN or Tailscale.
- Known issue: the service currently logs the API token at startup. Don't share logcat output.
- Telegram token and chat ID are entered in the app's settings and are never stored in this repo.

`app/src/main/python/ch340_serial.py` is a copy of the driver in the parent directory.
