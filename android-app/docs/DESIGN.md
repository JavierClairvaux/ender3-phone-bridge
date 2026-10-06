# Design notes

How the app is put together and the hardware findings behind it.

## Architecture

```
Dashboard / REST / MCP / native UI
              │
     PrinterService (foreground service, wake + Wi-Fi locks)
              │
     PrinterController  (one worker thread: job state, streaming, pause/resume, M85)
              │
   PrinterConnection  ── FakePrinterConnection → FakeMarlin (simulator)
              │
   RealPrinterConnection (safety lock) → Chaquopy → printer_link.py
              │                                       │
         UsbIo (Kotlin)  ◄── callbacks ──  unmodified ch340_serial.py
              │
   Android USB Host API → CH340 → Marlin 1.1.6.2
```

Code is under `app/src/main/java/com/javcabr/printerbridge/` and `app/src/main/python/`:

| Piece | Role |
|---|---|
| `usb/AndroidUsbIo.kt` | The real `UsbIo`: opens the device, claims the bulk interface, and runs the queued reader |
| `usb/SimCh340UsbIo.kt` | Simulated CH340 in front of the simulator; any DTR edge reboots it, so tests can prove the app never produces one |
| `python/printer_link.py` | `KotlinUsbBackend` plus `DtrSafeCH340Serial`, a subclass of the unmodified driver, and a `Link` wrapper with line I/O and a post-open drain |
| `printer/RealPrinterConnection.kt` | Chaquopy to `printer_link`, with the safety lock |
| `printer/FakeMarlin.kt` | Simulated Marlin 1.1.6: `ok` per line, resends, temperature ramps, `M109`/`M190` waits, position, fan, `M85` kill model, thermal-runaway and disconnect errors |
| `printer/PrinterController.kt` | Streams one line in flight with `N` and checksum framing and `Resend` handling; parses temps, position and errors; owns job state, history and the pause/resume machine |
| `service/PrinterService.kt` | Owns the controller, HTTP server and notifier; handles USB attach/detach and the permission request; holds the wake and Wi-Fi locks |
| `server/ApiServer.kt`, `McpHandler.kt` | NanoHTTPD server: dashboard, REST and MCP |
| `notify/TelegramNotifier.kt` | Bot API `sendMessage` with retries; the token is masked in logs |
| `service/TlsManager.kt`, `python/tls_tool.py` | Server keystore (PKCS12), self-signed certificates, certificate status, the ACME issuance job and renewal decisions |
| `python/acme_issue.py` | ACME client (certbot's `acme` library) with DNS-01 through the GoDaddy API |
| `service/SecretStore.kt` | Android Keystore AES-GCM encryption for secrets |

`ch340_serial.py` is never modified. The Kotlin backend replaces the driver's USB layer by subclassing, so the real driver code runs against Android's USB API.

**Embedded Python is 3.13**, not the newest 3.14: Chaquopy's package index only has Android builds of some native packages (notably `cryptography`, needed for future in-app ACME/TLS work) up to cp313. The build compiles the Python sources with a matching host interpreter (`-PbuildPython`, default `python3.13` on `PATH`).

**An active job owns the printer.** In every active state (`queued`, `printing`, `pausing`, `paused`, `resuming`) the controller refuses homing, board reset and any non-read-only G-code. The check runs once in the caller's thread (so the 409 is immediate even while the worker sits in a heat wait or a park move) and again on the worker before anything is sent. `paused` is the case that matters most: the head is parked and returns to the saved position on resume, so a manual move in between would wreck the print. The dashboard mirrors this by disabling Home and Start, but the server check is the guard.

## What the hardware taught us

These findings drive the rules the app follows. All were observed on a Redmi Note 9 Pro and a stock Ender 3.

**1. Only DTR edges reset Marlin.** Opening and claiming the USB device, running the full chip init and toggling RTS never reset the board. Switching DTR in either direction does, and so does the old driver's `close()` after DTR was raised. That explains the long-standing "resets even with `reset_on_open=False`": `_init_chip()` turns the lines off and `reset_on_open=False` then immediately raises DTR, which is the same edge as the reset pulse. The app keeps DTR and RTS off for the whole connection, on open and on close. M115, M105 and M503 all work with DTR off, and reconnecting never reboots the board, so a print survives an app restart and no re-homing is needed. `deliberate_reset()` is the only DTR path and is reachable only through `/api/reset_board`.

**2. RX must use a queued reader.** Calling `bulkTransfer` once per packet from Python let the CH340's small FIFO overflow between calls, silently dropping bytes mid-line (for example `PROTOC1.0 MACHINE_TYPE`). M115 replies were intact only 0–8 times in 10 in direct mode. A Kotlin thread that keeps four 512-byte `UsbRequest`s queued gave 10/10 clean M115 replies, byte-identical 820-byte M503 dumps and 100/100 clean M105 replies. The app uses only the queued path. A corrupted `ok`, `Resend` or temperature line would be dangerous during a print.

**3. `bulkTransfer` returns -1 for both timeout and error.** An idle read is indistinguishable from a disconnect. The app uses `requestWait` (which throws a distinct `TimeoutException`) and the `USB_DEVICE_DETACHED` broadcast to detect a real disconnect.

**4. The first read after a power-up can be stale.** After the printer was re-plugged, a baud-register read returned the chip's default rate and the first bytes were garbage, probably a power-on banner at the wrong baud. After init the app drains and listens for a settle window (`real_settle_ms`, 4 s by default).

**5. A reset takes a few seconds to finish booting.** Commands sent during the banner are still answered, but wait for the output to go quiet before streaming a print.

**6. `M85` kills the board.** The app arms `M85 S300` at job start as a firmware heater cutoff if the host dies. On this firmware an expired timer calls `kill()` and halts the board until it is power-cycled, and the app's idle `M105` polling doesn't count as activity. The first real print finished, then 300 s later the board halted. The app now sends `M85 S0` whenever a job ends (done, cancelled or errored), on cancel before the cooldown, on disconnect, and on reconnect if a job lost its link while armed. Pause also disarms it and resume re-arms it.

**7. A heat wait blocks everything.** `M109` and `M190` wait inside the firmware, and without an emergency parser (`Cap:EMERGENCY_PARSER:0`) they can't be interrupted. Pause, resume and cancel therefore flip job flags and return at once with the true state (`pausing`, `cancel_requested`) instead of queueing work behind the blocked worker thread.

**8. Android hides USB STALLs.** `controlTransfer` returns only -1 on failure, so the driver's `limited_prescaler` quirk probe can't tell a stall from another error. It doesn't matter at 115200 on this chip (version `0x31`).

**9. Round trips are fast.** A connection opens in 15–140 ms and an `M105` round trip takes 13–21 ms on average, against about 90 ms per line with the Termux streamer.

## Pause design

Modeled on Marlin's `M125`, OctoPrint's `afterPrintPaused`/`beforePrintResumed` scripts and Klipper's PAUSE/RESUME macros.

**Pause**, after the in-flight command completes:
1. `M85 S0`.
2. Save heater targets, fan, feedrate and the `G90`/`G91` and `M82`/`M83` modes, all tracked from every command sent (Marlin 1.1 semantics: `G91` also makes E relative).
3. Park only if parking is enabled, motion is allowed (safety lock off) and the position is known (a full `G28` on this connection plus a fresh `M114` after `M400`). Otherwise no motion, and `park_skipped` says why. A reconnect counts as unknown.
4. Park: `M400`, `M114`, retract (`G1 E-5 F1800`, only at 170 °C or hotter, Marlin's cold-extrusion limit), raise Z (clamped to 250), travel to the park point, fan off, `M400`.
5. State becomes `paused`. The bed stays on; the nozzle turns off after the standby time, checked from the worker's idle loop so no other thread touches the port.

**Resume**:
1. Fresh `M105`; reheat the nozzle and bed if cooled, with `M109`/`M190` waits shown as `in_flight`.
2. If parked: un-retract exactly what was retracted, purge 1.5 mm, travel back at the park height, lower to the saved Z, restore E (`G92 E`), the modes, feedrate and fan.
3. Re-arm `M85`, then continue at the next line.

A pause requested during a resume's reheat returns to `paused` (still parked); a pause during the restore moves runs after they finish. Cancel aborts any pause or resume. Connection loss or a printer halt still ends the job as an error, with no auto-resume.

## TLS and ACME

**Listeners.** Plain HTTP (NanoHTTPD) and, when `tls_enabled`, a second NanoHTTPD instance made secure with an `SSLServerSocketFactory` built from `files/tls/server.p12`. Both serve the same routes and auth. Listener changes run on a dedicated `net-config` thread, never on `printer-io`, so a print is unaffected. A replaced keystore (detected by mtime and size) restarts only the HTTPS listener, which drops in-flight HTTPS connections for a moment. A request that would turn both listeners off is rejected before anything changes.

**Keystore.** Python `cryptography` 42.0.8 writes the PKCS12 with the legacy PBE-SHA1-3DES encoding that Android's PKCS12 KeyStore reads reliably, to a temp file that is atomically renamed. Its random password lives in the secret store. Self-signed mode makes a local CA plus a server certificate (P-256), so strict clients (Python 3.13+ `VERIFY_X509_STRICT`) can verify it; the CA key is discarded.

**ACME.** `acme` 3.1.0 and `josepy` 1.15.0 (certbot's client library; the newest versions compatible with the only Android `cryptography` build for cp313) run under Chaquopy on an `acme-issue` thread. The account key (RSA 2048) is per directory in `files/tls/acme_account_<env>.pem`; each certificate gets a fresh P-256 key. DNS-01: GET the TXT records at `_acme-challenge.<host>`, PUT them back plus the challenge value, poll Google and Cloudflare DNS-over-HTTPS until one sees it (Let's Encrypt queries the authoritative servers itself), answer, finalize, install, hot reload, then restore the previous records (DELETE when there were none) and verify with a GET. The restore runs in a `finally`, so it also happens on failure.

**Issuance during a print is refused, and renewal waits.** The issuance itself is mostly network, but it runs in the same Python interpreter as the printer driver and does CPU-heavy crypto (key generation, signing) on the phone. A GIL hold or CPU spike would delay the one-line-at-a-time streaming and could starve the planner, leaving blobs. With a 30-day renewal margin, waiting costs nothing.

**Threat model.**
- *The GoDaddy API key can change every DNS record of the domain* (redirect web or mail, issue certificates elsewhere). It lives on a rooted phone. It is stored only as AES-256-GCM ciphertext (`SharedPreferences` "secrets") under a non-exportable Android Keystore key, never returned by the API, never logged, redacted from error messages, progress steps and notifications. This protects against copying the app's files (backups, a pulled data partition, file-level root access). It does **not** protect against an attacker running code as the app or as root on the live phone, who can ask the Keystore to decrypt or read the value from memory while it is used. Limit the blast radius at GoDaddy where possible (a dedicated key, revoked when no longer needed) and keep the phone off the open internet.
- *The certificate private key and the ACME account key* are in the app's private files (the account key as PEM, the server key inside the PKCS12 whose password is in the secret store). A thief of the files can impersonate the HTTPS endpoint until the certificate expires (90 days) or is revoked, and can request certificates only with DNS control, which also needs the GoDaddy key.
- *The API token* still travels in every control request; HTTPS protects it in transit. Reads stay unauthenticated by design.
- *Not covered:* client certificates, HSTS, pinning, and protection of the plain HTTP listener when it is left on.

## Status of testing

- **Real hardware:** see the [README](../README.md#status) for what has been verified.
- **TLS:** Let's Encrypt staging issuance for `printer.theconsortio.xyz` ran end to end from the app on the phone (arm64) and on the emulator; the served chain verified against the staging root (curl, openssl, the MCP SDK), and the TXT name was restored to zero records. Production issuance is not done yet.
- **Simulator and emulator only:** pause/park/reheat/resume sequences and exact state restore; the `M85` disarm paths; pause during a heat wait; resend handling (checksum errors injected into a 405-line job); thermal-runaway and disconnect handling; Doze survival (forced deep idle on an emulator, which doesn't suspend the CPU like a phone does).
- **Not tested anywhere:** unplug and replug during a print, the USB permission-denied path, `/api/reset_board` on hardware, real-phone Doze with battery management, and sustained full-rate RX.

### Hardware test plan for pause/resume

Do this with the printer watched, using a short part such as a 20 mm cube:
1. After 2–3 layers, pause. Expect: the current move finishes, about 5 mm retracts, Z rises 10 mm, the head goes to X10 Y210, the fan stops and the bed stays hot.
2. Leave it paused over 5 minutes. Expect: the nozzle target drops to 0, the bed stays on, and no "KILL caused by too much inactive time".
3. Resume. Expect: `resuming` while `M109` reheats, a purge at the park spot, travel back, and printing continues at the same point. Check the part for a seam or blob and adjust `pause_extra_purge_mm` and `pause_retract_mm`.
4. Pause again, then cancel while paused. Expect: heaters, fan and steppers off, and no `M85` kill afterwards.
5. Optionally pause during the initial heat-up. Expect: `pausing` until the wait finishes, then `paused` with no retract, because the nozzle is below 170 °C.

## Known limitations

Security notes are in the [README](../README.md#security-notes) and the TLS threat model above.

- A lost connection or app crash mid-print ends the job as an error. There's no resume from a line number. The restarted app doesn't know whether the board's `M85` timer is armed.
- Cancel can't interrupt an in-flight heat wait.
- Certificate issuance is refused during a print job; the renewal scheduler waits for it to finish.
- The self-signed CA changes on every regeneration, so clients must re-download `/api/tls/ca.pem`.
- Line numbers, checksums and `Resend` handling have only been exercised against the simulator, so the real error wording from Marlin 1.1.6 is unverified.
