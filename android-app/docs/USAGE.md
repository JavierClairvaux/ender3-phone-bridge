# Using Printer Bridge

For endpoint details see [API.md](API.md). This page covers the everyday workflows.

## Install and update

Build and install with the [Quick start](../README.md#quick-start). Notes:

- The printer occupies the phone's only USB port, so use wireless debugging (Settings > Developer options). The port changes every time it's re-enabled; read it from that screen or with `su -c getprop service.adb.tls.port`. Android also switches it off when Wi-Fi changes.
- `-Pbackend=` only sets the default backend on first launch. `-PappId=<id>` overrides the package id; reusing an id that already has the USB "use by default" grant avoids one permission tap.
- The first time the app sees the printer, Android shows a USB permission dialog. Tap OK and tick "use by default". If the LAN API can't open its socket, check the per-app Network access toggle (LineageOS: Settings > Apps > Printer Bridge).
- Exempt the app from battery optimization for long prints. `deploy_phone.sh` has more commands (`stop`, `forward`, `logcat`); see its header.

Start the foreground service from adb with `am start-foreground-service -n <pkg>/com.javcabr.printerbridge.service.PrinterService`. Extras: `--es backend fake|real|sim-usb`, `--ez auto_connect false`, `--ez idle_temp_poll false`, `--el real_settle_ms 4000`, `--ez connect true`, `--ez disconnect true`, `--ez stop true`. The service accepts these only from callers holding `android.permission.DUMP` (adb shell and the system).

## Backends

| Backend | What it is |
|---|---|
| `real` | The printer, over USB through the unmodified driver |
| `fake` | A built-in simulated Marlin: temperature ramps, position, resends, thermal-runaway and disconnect errors. Safe for testing everything |
| `sim-usb` | The full real Chaquopy and driver path over a simulated CH340 |

Switch with the Backend button in the app or `POST /api/connection {"backend": "fake"}`.

## Safety lock

The real backend starts with a safety lock that allows only `M115`, `M105`, `M503`, `M114` and `M119`. That keeps stray API or MCP calls from heating or moving the printer. Turn it off in Settings or with `POST /api/config {"real_safety_lock": false}` for heating, homing, leveling moves and printing, and turn it back on afterwards. Park-on-pause needs it off too.

## Printing

1. Slice for the printer. Put the bed temperature directly into `machine_start_gcode` (`M140 S60` / `M190 S60`); OrcaSlicer's CLI resolves the bed-temperature placeholder wrongly for this profile.
2. Upload: `curl -T part.gcode -H "$H" $B/api/files/part.gcode`.
3. Start: `POST /api/print {"file": "part.gcode"}` (or the dashboard, or the MCP `start_print`). The sliced start G-code homes the printer, so no separate home is needed.
4. Watch the first layer, then monitor from the dashboard (`http://<phone-ip>:8080/`), the API, or Telegram.

The laptop isn't involved once the job starts: it runs entirely on the phone. If the phone loses the printer or the app dies, the printer halts itself after 5 minutes of silence (the `M85` cutoff) with the heaters off.

## Pause and resume

Pause is for deliberate use: swapping filament or inspecting a part. The head retracts, lifts and parks at the back-left (the bed comes toward you), the bed stays hot and the nozzle turns off after 5 minutes. Resume reheats, purges and returns to the exact spot, usually leaving a small blob or seam. If the printer wasn't homed on this connection, or the safety lock is on, the head doesn't move at all and the status says why. The exact sequence is in [DESIGN.md](DESIGN.md#pause-design) and the tunable `pause_*` settings are in the [config table](API.md#config).

## Leveling through the API

With the lock off and the printer homed, send each point (10 mm in from the edges, Z = 0.1 for the paper-drag test):

```bash
for c in "G90" "G1 Z5 F600" "M400" "G1 X10 Y10 F3000" "M400" "G1 Z0.1 F300" "M400" "M114"; do
  curl -s -X POST -H "$H" -H 'Content-Type: application/json' -d "{\"command\":\"$c\"}" $B/api/gcode; echo
done
```

Points: front-left (10,10), front-right (210,10), back-right (210,210), back-left (10,210), center (110,110). Always lift to Z5 before moving. If you heat the nozzle to clean it, raise it well above the bed first and let it cool below about 60 °C before lowering it onto paper. Idle shutdown applies only during a job, so turn heaters off yourself with `M104 S0` and `M140 S0`.

## Telegram notifications

Enter the bot token and chat ID in Settings, or `POST /api/config/telegram`, then `POST /api/config/telegram/test`. You get one message when a print finishes and one on an error. Cancel messages are off by default.

For a one-shot setup from the laptop, put `{"telegram_token": "...", "telegram_chat_id": "..."}` in `tools/telegram_config.local.json` (git-ignored) and `adb push` it to `/sdcard/Android/data/<pkg>/files/config.json`. The service applies it on its next start and deletes it.

## Recovering from a halt

If the printer shows KILLED (thermal runaway, an `M85` timeout, an error), power-cycle it. The app reconnects on its own and clears the halted state. The printer's position is unknown after any reboot: home before moving.

## Testing without a printer

Use the `fake` or `sim-usb` backend and the scripts in `tools/`:

- `tools/e2e_fake.py`: drives the REST API, MCP (with the official `mcp` SDK), the dashboard and a mock Telegram server on an emulator.
- `tools/fake_telegram.py`: a local fake of the Telegram Bot API (set the base URL to it).
- `tools/doze_test.sh`: runs a long simulated print with the screen off and deep Doze forced.
- `tools/phone_smoke.sh`: a read-only (`M115`) smoke test on the real phone; set `PHONE_ADB=<phone-ip>:<adb-port>`.

The instrumented tests (`./gradlew connectedDebugAndroidTest`, needs an emulator or device) cover the driver path, resends, pause/resume, the `M85` timer and the disconnect handling.
