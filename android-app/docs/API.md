# API reference

The app serves a dashboard, a REST API and an MCP endpoint on one port (default `8080`) from inside its foreground service. Examples below use `<ip>` for the phone's address and `TOKEN` for the API token.

```bash
export B=http://<ip>:8080 H="Authorization: Bearer TOKEN"
curl $B/api/status                                           # reads need no token
curl -X POST -H "$H" $B/api/check_temps                      # everything else does
```

## Basics

- **Auth:** `GET` requests (dashboard, status, temps, history, files, log, notifications, config) are open. Every `POST`, `PUT` and `DELETE`, and every `/mcp` call, needs the token as `Authorization: Bearer TOKEN` (also accepted: `X-Api-Token: TOKEN` or `?token=TOKEN`). If the token is cleared in Settings, auth is off. The transport is plain HTTP.
- **Bodies:** JSON in, JSON out (`Content-Type: application/json`, 1 MB limit). File uploads are raw bytes.
- **Errors:** always `{"error": "<message>", "type": "<ExceptionName>"}` with one of these codes:

| Code | Meaning | Typical cause |
|---|---|---|
| 400 | Bad request | Missing or invalid field, unknown config key |
| 401 | Unauthorized | Missing or wrong token |
| 403 | Forbidden | Real-printer safety lock refused the command |
| 404 | Not found | Unknown route; deleting a file that doesn't exist |
| 405 | Method not allowed | Wrong HTTP verb for the route |
| 409 | Conflict | Wrong state: job already running, printer halted or not connected, `home` or a motion command during a print |
| 502 | Bad gateway | USB or I/O failure talking to the printer |
| 500 | Server error | Unexpected; check logcat |

- **Timestamps** are Unix milliseconds. Temperatures are °C, positions mm.

## Job states

`queued` → `printing` ⇄ `pausing` → `paused` → `resuming` → `printing` → `done`. A job can also end as `cancelled` or `error`.

- `pausing`: pause requested; the in-flight command (maybe a heat-up wait) is still finishing. `paused` is reported only once the head has actually stopped (and parked).
- `resuming`: reheating and restoring position; becomes `printing` when the next line streams.
- A cancel request returns immediately with `cancel_requested: true` and takes effect after the in-flight command completes.

## Status and monitoring (no token needed)

### `GET /api/status`

Everything at once.

```json
{
  "connection": {"state": "connected", "backend": "real", "error": null, "firmware": "Marlin Creality 3D",
                 "machine_type": "Ender-3", "halted": false, "lines_sent_total": 10, "last_rx": "ok", "details": {}},
  "temps":    {"hotend": 200.1, "hotend_target": 200, "bed": 60.5, "bed_target": 60, "updated_at": 1791052861640},
  "position": {"known": true, "x": 111.5, "y": 123.2, "z": 1.4, "e": 0, "updated_at": 1791052983687},
  "job": {"id": "576c3e4c", "file": "part.gcode", "state": "printing", "progress_pct": 3.1,
          "lines_done": 10923, "lines_total": 352009, "elapsed_s": 826, "remaining_s": 15480,
          "remaining_source": "slicer_m73", "resends": 0, "error": null, "backend": "real",
          "in_flight": "G1 X115.5 Y121.9 E.01377", "pause": null, "pause_requested": false,
          "cancel_requested": false, "pauses": 0, "queued_at": 0, "started_at": 0, "ended_at": null},
  "service": {"uptime_s": 1821, "backend_setting": "real", "http_port": 8080, "urls": ["http://<ip>:8080/"],
              "real_safety_lock": true, "wake_lock_held": true, "ignoring_battery_optimizations": true, "usb_permission": null}
}
```

- `job` is `null` before the first job. `position.known` is false until the printer has been homed on this connection.
- `connection.state`: `connected`, `connecting`, `error` or `disconnected` (before the first connect and after a disconnect) (with `error` text, for example `CH340 (1a86:7523) not attached`). `halted: true` means Marlin killed itself and needs a power-cycle.
- `remaining_source` is `slicer_m73` (from the slicer's `M73 R` lines), `linear_estimate` or `none`.
- While paused, `job.pause` holds `stage`, `parked`, `park_skipped` (reason), `retracted_mm`, `nozzle_cooled`, `nozzle_standby_in_s`, `reheated` and the `saved` position, targets, fan and feedrate.

### Other reads

| Route | Returns |
|---|---|
| `GET /api/temps` | `{"current": {...}, "history": [[t_ms, hotend, hotend_target, bed, bed_target], ...], "history_format": "..."}` |
| `GET /api/history?limit=50` | `{"history": [job, ...]}`, newest first; each entry has the job fields above |
| `GET /api/files` | `{"files": [{"name": "part.gcode", "bytes": 234663, "modified": 1790814747701}]}` |
| `GET /api/log` | `{"log": ["<t_ms> <message>", ...]}`: recent connection and job events |
| `GET /api/notifications` | `{"notifications": [{"kind": "job_done", "text": "...", "result": "sent", ...}]}` |
| `GET /api/connection` | The `connection` object from status |
| `GET /api/config` | Current settings (see [Config](#config)) |

## Printing

### Files

```bash
curl -T part.gcode -H "$H" $B/api/files/part.gcode      # upload (PUT, raw body, up to 64 MB)
curl -X DELETE -H "$H" $B/api/files/part.gcode          # delete
```

Upload returns `{"saved": "part.gcode", "bytes": 234663}`. Uploading over the file that is currently printing is refused with 409.

### `POST /api/print`

```bash
curl -X POST -H "$H" -H 'Content-Type: application/json' -d '{"file": "part.gcode"}' $B/api/print
```

Returns the new job object (`state: "queued"`, `lines_total`). 409 if the printer isn't connected, is halted, or another job is active. The job starts with `M110 N0`, arms `M85 S300` (a firmware heater cutoff if the host dies), then streams the file with line numbers and checksums, waiting for `ok` per line and honoring `Resend`.

### Pause, resume, cancel

```bash
curl -X POST -H "$H" $B/api/pause     # -> job object; state "paused" or "pausing"
curl -X POST -H "$H" $B/api/resume    # -> state "resuming" (reheating) or "printing"
curl -X POST -H "$H" $B/api/cancel    # -> job object with cancel_requested: true
```

All three return within about 1.5 s with the true state; poll `/api/status` for the final one. 409 if there is no job or it's in the wrong state.

- **Pause** parks the head (retract, raise Z, move to the park point) when the position is known and the safety lock allows motion; otherwise it only stops sending lines and `job.pause.park_skipped` says why. **Resume** reheats if needed and restores the position and state. The full sequences are in [DESIGN.md](DESIGN.md#pause-design).
- **Cancel** works in any state and ends with heaters, fan and steppers off. A firmware heat wait (`M109`/`M190`) can't be interrupted, so it waits for it.

## Direct control (refused during a print)

| Route | Body | Does |
|---|---|---|
| `POST /api/home` | none | `G28`, then `M114`; returns `{"ok": true, "reply": [...], "position": {...}}` |
| `POST /api/check_temps` | none | fresh `M105`; returns the temps object |
| `POST /api/gcode` | `{"command": "M114"}` | sends one line; returns `{"command": "M114", "reply": ["X:0.00 ...", "ok"]}` |

- While a job is active, `/api/home` and any motion or heater command through `/api/gcode` return 409; only `M105`, `M114`, `M115`, `M119` and `M503` are allowed then.
- With the **real-printer safety lock on** (the default), `/api/home` is also refused (403), and `/api/gcode` allows only `M115`, `M105`, `M503`, `M114` and `M119`; anything else returns 403 `SafetyLockException`.
- Replies can include `echo:busy: processing` lines during long commands such as `G28`.

## Connection

```bash
curl -X POST -H "$H" -H 'Content-Type: application/json' \
  -d '{"action": "connect", "backend": "fake"}' $B/api/connection
```

- `action`: `connect` (default) or `disconnect`. `backend` is optional: `real`, `fake` or `sim-usb`; it switches backends (and saves the choice).
- `POST /api/reset_board` with `{"confirm": true}` deliberately pulses DTR to reboot the printer board. It's the only code path that ever toggles DTR, and it has not been tested on real hardware.

## Config

`GET /api/config` returns the settings; `POST /api/config` with any of these keys changes them and returns the config plus `"applied": [keys]`. Unknown keys are ignored. Settings persist across restarts.

| Key | Default | Notes |
|---|---|---|
| `backend` | `real` | `real`, `fake` or `sim-usb` |
| `real_safety_lock` | `true` | Allow only read-only commands on the real printer |
| `idle_shutdown_s` | `300` | `M85` timeout armed at job start (0 disables) |
| `idle_temp_poll` | `false` | Poll `M105` while idle so temperatures refresh |
| `auto_connect` | `true` | Connect on service start and on USB attach |
| `keep_awake` | `true` | Hold a wake lock continuously, not just during jobs |
| `api_token` | generated | Empty string turns authentication off |
| `http_port` | `8080` | Restart the service for it to take effect |
| `pause_park_enabled` | `true` | Park on pause |
| `pause_park_x`, `pause_park_y` | `10`, `210` | Clamped to 0–220 |
| `pause_z_raise_mm` | `10` | Z is capped at 250 |
| `pause_retract_mm` | `5` | Only retracts if the nozzle is at least 170 °C |
| `pause_extra_purge_mm` | `1.5` | Extra extrusion on resume |
| `pause_nozzle_standby_s` | `300` | Nozzle turns off this long after parking (0 or less = never) |
| `real_settle_ms` | `4000` | Post-connect listen/drain window |
| `fake_line_delay_ms`, `fake_time_scale`, `fake_inject_resend_every` | `15`, `1`, `0` | Simulator tuning |

### Telegram

```bash
curl -X POST -H "$H" -H 'Content-Type: application/json' \
  -d '{"telegram_token": "<bot token>", "telegram_chat_id": "<chat id>"}' $B/api/config/telegram
curl -X POST -H "$H" $B/api/config/telegram/test
```

`/api/config/telegram` accepts `telegram_token`, `telegram_chat_id`, `telegram_base_url`, `telegram_enabled` and `notify_on_cancel`, and returns the masked settings (`{"enabled": true, "configured": true, "token": "1234...abcd", "chat_id": "...", ...}`). `GET` returns the same. The test route sends a message and returns its delivery result. Notifications fire on print done and print error, once each.

## Simulator controls (fake and sim-usb backends only)

- `POST /api/sim/error` with `{"kind": "thermal_runaway"}`, `"mintemp"` or `"disconnect"` triggers a simulated printer error.
- `POST /api/sim/config` with `line_delay_ms`, `time_scale` and/or `inject_resend_every` tunes the simulator.

## MCP

`POST /mcp` speaks JSON-RPC 2.0 over MCP's Streamable HTTP transport, statelessly: every POST gets a single JSON response, with no SSE stream and no session id (`GET /mcp` returns 405). Protocol versions `2025-06-18`, `2025-03-26` and `2024-11-05` are accepted. Send the token as a bearer header.

Claude Code:

```bash
claude mcp add --transport http printer http://<ip>:8080/mcp --header "Authorization: Bearer TOKEN"
```

Raw example:

```bash
curl -X POST $B/mcp -H "$H" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_status","arguments":{}}}'
```

| Tool | Arguments | Same as |
|---|---|---|
| `get_status` | none | `GET /api/status` (without `service`) |
| `list_files` | none | `GET /api/files` |
| `start_print` | `file` (string, required) | `POST /api/print` |
| `pause` | none | `POST /api/pause` |
| `resume` | none | `POST /api/resume` |
| `cancel` | none | `POST /api/cancel` |
| `home` | none | `POST /api/home` (refused during a print) |
| `check_temps` | none | `POST /api/check_temps` |
| `list_print_history` | `limit` (integer, default 20) | `GET /api/history` |

Tool results come back as a text block containing the same JSON as REST. Errors set `isError: true` and put `ExceptionName: message` in the text. There is no MCP tool for raw G-code or config; use REST for those.
