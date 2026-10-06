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

While a job is active (queued, printing, pausing, paused or resuming) the printer belongs to the job: Home, starting another print, motion or heater G-code and board reset are refused by the server (409, or `isError` over MCP), and the dashboard greys out its Home and Start buttons (hover for the reason). This matters most when **paused**: the head is parked and will travel back to the saved position on resume, so a manual home or move in between would ruin the print. Read-only commands (`M105`, `M114`, `M115`, `M119`, `M503`) still work. The buttons come back once the job is done, cancelled or failed.
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

## HTTPS (TLS) and Let's Encrypt

Plain HTTP on port 8080 is the default (`tls_enabled=false`): no HTTPS listener, no certificate work, no ACME network traffic. Everything below is opt-in and can be switched at runtime from Settings or `POST /api/config`, without restarting the service or disturbing a print.

**1. HTTPS with a self-signed certificate.** Set `tls_enabled: true` and `tls_domain` (the host name clients will use). The app creates a local CA plus a server certificate for that name (and `localhost`) and serves HTTPS on `https_port` (8443). Clients that should verify it download the CA once and trust it:

```bash
curl -o printer-ca.pem http://<ip>:8080/api/tls/ca.pem
curl --cacert printer-ca.pem https://<tls_domain>:8443/api/status
```

Regenerating the self-signed certificate (`POST /api/tls/self_signed`) creates a new CA, so clients must fetch it again.

**2. Let's Encrypt via GoDaddy DNS-01.** The app proves control of `tls_domain` by publishing a temporary `_acme-challenge` TXT record through the GoDaddy API, so the phone needs no inbound internet access. It needs:
- `tls_domain` (for example `printer.theconsortio.xyz`) inside a GoDaddy-managed domain (`tls_dns_zone`, default: the last two labels);
- `acme_email` (Let's Encrypt account contact; agreeing to the Let's Encrypt terms is implied);
- `acme_directory`: `staging` (default, untrusted test certificates) or `production`;
- the GoDaddy API credentials `KEY:SECRET` (write-only; stored encrypted).

Supply the credentials without printing them, from a file such as an `.env` holding `GODADDY_API_TOKEN=KEY:SECRET`:

```bash
python3 tools/godaddy_secret.py push --env-file /path/to/.env --base http://<ip>:8080 --token TOKEN
# prints only: push: HTTP 200, godaddy_credentials_set=True
```

They can also be typed into Settings (the field is write-only). `POST /api/config {"godaddy_credentials": ""}` deletes them.

Then `POST /api/tls/issue` and poll `GET /api/tls` until `issuance.state` is `succeeded` or `failed`. A run takes about a minute: account, order, TXT record, DNS propagation check (DNS-over-HTTPS), validation, certificate install, TXT cleanup. The new certificate is served at once (the HTTPS listener reloads). Existing TXT records at `_acme-challenge.<host>` are preserved, and the name is restored afterwards, also when issuance fails; `issuance.result.cleanup` reports it. `tools/godaddy_secret.py txt` checks the name read-only.

**Staging first, then production.** Staging certificates are signed by "(STAGING)" intermediates that browsers don't trust; verify them with the [staging root](https://letsencrypt.org/certs/staging/letsencrypt-stg-root-x1.pem) (`curl --cacert letsencrypt-stg-root-x1.pem ...`). When staging looks right, set `acme_directory: production` and issue again (or wait for the scheduler, which treats a directory change as due). Production has strict rate limits; don't loop on failures.

**Renewal.** A scheduler checks 60 s after the service starts and then daily. It renews when the certificate is missing, self-signed, from the other directory, for a different `tls_domain`, or has fewer than 30 days left. It waits while a print job is active and backs off (1 h up to 24 h) after failures. Success and failure are sent to Telegram (no secrets in the messages). Nothing runs while TLS is off or ACME isn't fully configured.

**Listener modes.** `http_enabled: false` makes the app HTTPS-only; `http_bind: "127.0.0.1"` keeps plain HTTP for on-phone use only. Turning off both HTTP and HTTPS is refused (400) and changes nothing.

**Recovery.** If HTTPS is broken (for example a bad keystore), `POST /api/tls/self_signed {"confirm": true}` over HTTP installs a fresh self-signed certificate; `tls_enabled: false` turns HTTPS off. If HTTP is off and HTTPS is unreachable, use adb: `adb forward tcp:8443 tcp:8443` reaches the HTTPS listener on the phone's loopback; the app's data (`files/tls/`) can be cleared by uninstalling only as a last resort (that also wipes the settings and USB permission).

## Restricting who can connect

By default any address that can reach the phone can talk to the API (control still needs the token). To accept only tailnet clients, set `restrict_to_tailnet: true`: connections from outside 100.64.0.0/10 (the Tailscale/headscale range) get 403 on both listeners, for every route including the dashboard and `/mcp`, before authentication. `allowed_cidrs` (a list such as `["192.168.1.0/24"]`) adds more ranges, or on its own restricts to just those. Loopback is always allowed. The active rule shows in `/api/status` under `service.access`.

With the restriction on, the phone's Wi-Fi LAN address stops answering for LAN clients, while its tailnet address (100.64.x.x) keeps working.

A change that would block the address making it is refused (409) and changes nothing. If a restriction still locks you out, clear it over adb:

```bash
adb shell am start-foreground-service -n <pkg>/com.javcabr.printerbridge.service.PrinterService --ez restrict_to_tailnet false
adb shell am start-foreground-service -n <pkg>/com.javcabr.printerbridge.service.PrinterService --es allowed_cidrs ,
```

`adb forward tcp:8080 tcp:8080` also reaches the API on the phone's loopback, which is always allowed.

## Recovering from a halt

If the printer shows KILLED (thermal runaway, an `M85` timeout, an error), power-cycle it. The app reconnects on its own and clears the halted state. The printer's position is unknown after any reboot: home before moving.

## Testing without a printer

Use the `fake` or `sim-usb` backend and the scripts in `tools/`:

- `tools/e2e_fake.py`: drives the REST API, MCP (with the official `mcp` SDK), the dashboard and a mock Telegram server on an emulator.
- `tools/fake_telegram.py`: a local fake of the Telegram Bot API (set the base URL to it).
- `tools/doze_test.sh`: runs a long simulated print with the screen off and deep Doze forced.
- `tools/phone_smoke.sh`: a read-only (`M115`) smoke test on the real phone; set `PHONE_ADB=<phone-ip>:<adb-port>`.

The instrumented tests (`./gradlew connectedDebugAndroidTest`, needs an emulator or device) cover the driver path, resends, pause/resume, the `M85` timer, the disconnect handling, and that homing, motion, heater and reset commands are refused (with nothing sent) in every active job state. `tools/e2e_access.py` checks the source restriction (403 vs 200 on both listeners and `/mcp`, the lock-out guard and the adb escape hatch). `tools/e2e_tls.py` checks the HTTP/HTTPS listener modes, the self-signed certificate (verified with curl and the MCP SDK), hot reload during a print and the ACME guards. `tools/e2e_fake.py` also checks the REST/MCP refusals and, with headless chromium, that the dashboard's Home and Start buttons are disabled mid-print and while paused and enabled again when idle.
