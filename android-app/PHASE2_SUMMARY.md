# Chaquopy spike, phase 2: real hardware

**Verdict: YES, it is sound to build `RealPrinterConnection` on this, with one required change:** read bulk IN from a Kotlin thread using queued `UsbRequest`s, not by having Python call `bulkTransfer` once per packet. The naive path from phase 1 works end to end, but on real hardware it **silently drops received bytes**. That was seen in every verbose run and in 3 of 4 quiet runs. The Kotlin-reader variant was intact in all 5 runs.

The real round trip works on the phone (Redmi Note 9 Pro, LineageOS 22.2 / Android 15, arm64). The chain is Android USB Host API -> Kotlin `UsbIo` -> Chaquopy -> the unmodified `ch340_serial.py` -> CH340 -> Marlin 1.1.6.2. M115, M503 and M105 all get correct replies.

## What was run

- **Build and install.** `./gradlew -Pabi=arm64-v8a assembleDebug` built a 21 MB APK containing only `lib/arm64-v8a`. It was installed over wireless ADB (`<phone-ip>:<adb-port>`, i.e. the phone's Tailscale address) and launched with `adb shell am start -n com.example.chaquopyspike/.HwActivity --ez run true [--es suite v2|v3]`.
- **Safety.** Only M115, M105 and M503 were sent, all read-only. Apart from those, the only thing done to the board was DTR/RTS toggling, which reboots an idle board. No Termux/Python process was running on the phone at any point (checked over SSH before and after).
- **Evidence** is in `evidence/phase2_run{1..4}_*`:
  - `_logcat.txt`: filtered to the app tag, `python.stderr` and USB services
  - `_logcat_full.txt`: full logcat
  - `_screenshot.png`: the Spike HW screen

| Run | Suite | Result |
|---|---|---|
| 1 | v1: device, phase-1 suite, sessions 1-5 | 7/7 PASS. But the M115 replies had bytes missing mid-line, which the v1 checks didn't test for (see below) |
| 2 | v2: line edges, reset on close, integrity x3 | 2/5. The edge tests passed. The integrity checks "failed" partly because my M503 validator was too strict. The Kotlin-reader data was intact |
| 3 | v2 again, with the M503 validator fixed | 4/5. Only the verbose/debug direct mode (run 1's conditions) failed |
| 4 | v3: 3 x (direct-quiet, queued), no resets | direct 0/3 intact, queued 3/3 intact |

### USB permission flow (observed)

- `UsbManager.getDeviceList()` returned one device: `/dev/bus/usb/001/002 1a86:7523 'USB Serial'`. It has one interface (class 255) with endpoints 0x82 bulk IN, 0x02 bulk OUT and 0x81 interrupt IN, max packet 32. The manufacturer name comes back as `null`.
- `requestPermission` with an explicit, `FLAG_MUTABLE` PendingIntent and a `RECEIVER_NOT_EXPORTED` receiver worked on Android 15. The user tapped Allow about 95 s after launch; the log shows `USB permission result: granted=true`.
- **The permission survived `adb install -r` (3 reinstalls).** No further taps were needed.
- The deny path is coded but was not exercised.

### Round trip and chip init (observed, run 1 session 4, with the same checks as phase 1)

- The chip version read returned `31 00`.
- The quirk probe (`0x95 0x2706`) succeeded, so `limited_prescaler=False`.
- Then `0xA1` init, divisor `0x9A 0x1312 = 0xCC83`, line settings `0x2518 = 0xC3`, and the DTR/RTS pulse. Every control transfer returned >= 0.
- M115 returned the real banner: `FIRMWARE_NAME:Marlin Creality 3D ... PROTOCOL_VERSION:1.0 MACHINE_TYPE:Ender-3 EXTRUDER_COUNT:1 UUID:cede2a2f-...`, then 11 `Cap:` lines, then `ok`.
- Opening takes 25-140 ms. A single M115 reply takes about 55 ms.
- The phase-1 fake suite also passes on the phone: 8/8, `Python 3.14.0 on android (aarch64)`.

## Does opening reset the board? (observed)

Opening does not; DTR edges do. Idle Marlin is silent, so any boot banner (`start`, `echo: External Reset`, ...) arriving before we send anything means the board rebooted.

| What was done | Board reset? | Where |
|---|---|---|
| `openDevice` + `claimInterface`, plus vendor reads only | no | run 1 session 1 |
| Full driver init (`0xA1`, baud, line settings, handshake 0), DTR/RTS never asserted | **no** | run 1 sessions 2 and 5; runs 2-4 sessions 7-10 and v3 (about 12 sessions) |
| RTS on, then RTS off | no | run 2 and run 3 edges |
| **DTR on** (off -> on) | **yes** (`External Reset`) | run 1 session 3; edges in runs 2 and 3 |
| **DTR off** (on -> off) | **yes** (banner with no reset-reason line) | edges in runs 2 and 3 |
| Driver `close()` after DTR was on (it drops DTR) | **yes**: the next session reads the tail of a banner produced while nothing was reading | run 1 sessions 4 and 5; session 7 in runs 2 and 3 |

**Why the old driver resets "even with `reset_on_open=False`".** `_init_chip()` sets the lines off and `reset_on_open=False` then immediately turns DTR and RTS on. That off->on DTR edge is exactly what the reset pulse does. Session 3 (`driver_no_reset`, the unmodified driver) confirmed it on hardware. Android's open/claim is not the cause, and pyusb almost certainly wasn't either. The pyusb path itself was not re-tested here.

**What `RealPrinterConnection` should do:**
- Leave DTR (and RTS) deasserted for the whole connection, on open and on close.
- M115, M105 and M503 all work with DTR off. The CH340 transmits regardless of DTR.
- Pulse DTR only when a reboot is actually wanted.
- Note that toggling DTR in either direction reboots the board.
- This lets the app reconnect without killing a running print. It is the fix for the "never assume state survives across invocations" bug.
- This needs a small, legitimate driver change: an option to keep lines off, and a `close()` that doesn't touch DTR if it was never raised. The spike did this with a subclass override (`lines_never_asserted`) rather than by editing `ch340_serial.py`.

## Real-hardware gotchas (not visible with the fake)

1. **RX data loss with per-packet synchronous reads (the important one).**
   - **Symptom:** the driver's reader thread calls `bulkRead(32, 200ms)` in a loop, and each call is Python -> Kotlin -> `bulkTransfer`. Bytes silently vanish in the middle of lines, e.g. `PROTOC1.0 MACHINE_TYPE`, `Cap:AOBE:0`.
   - **Cause:** between transfers nothing is polling the endpoint, and at 115200 baud the CH340's small FIFO overflows in about 3 ms.
   - **Results:**
     - With per-packet logcat logging and driver debug on (run 1 conditions): 0-1/10 M115 intact in every run.
     - With logging off: intact in run 3, but M115 intact only 6/10, 7/10, 6/10 and 8/10 in the other four runs, and one M503 dump was 815 bytes instead of 820.
     - With a Kotlin thread keeping 4 `UsbRequest`s of 512 bytes queued (`AndroidUsbIo(queuedReader=true)`), Python's `bulkRead` just drains a queue: 10/10 M115 and 3/3 byte-identical 820-byte M503 in all 5 runs, plus 100/100 M105.
   - **Status:** the queued reader lives entirely in the Kotlin `UsbIo`; the driver is unchanged. Make it the default.
   - **Risk for streaming:** Marlin's replies are short, but corrupted `ok`/`Resend`/temperature lines would be dangerous, so this matters.
2. **`bulkTransfer` returns -1 for both "timeout" and "error".** A normal idle read shows up as -1, about 16-116 times per session in direct mode. A real disconnect would look the same, so the Python reader would spin forever. The queued reader (`requestWait` throws `TimeoutException` on timeout) and `USB_DEVICE_DETACHED` handling are needed for disconnect detection. Unplugging was not tested.
3. **Opening can return stale data.** In run 1 session 1 the first read, 11 ms after open, returned 7 garbage bytes. The chip's baud register read `02d9` instead of the driver's `cc83`, which means the chip had been re-powered (the printer had just been re-plugged) and was at its default rate.
   - Best explanation (not proven): the board's power-on banner, received at the wrong baud rate and left sitting in the FIFO.
   - Consequence: after init, drain or ignore input for about 0.5-1 s before trusting it.
4. **After a reset, Marlin's banner lasts a few seconds** (SD card init, "Init power off infomation", etc.). Commands sent during this are still answered (M115 replied in 50-560 ms after a 4 s wait). Wait for the banner to go quiet before streaming.
5. **Round-trip latency is about 13-21 ms average per M105**, and 30-50 ms at worst (100-command runs). The Termux `print_gcode_phone.py` overhead is about 90 ms per line, so this is roughly 4-6x better. It was not load-tested with motion commands or a real 4000-line stream.
6. **No USB STALL detection.** As expected from phase 1, the quirk probe didn't stall on this chip (v0x31), so it doesn't matter here.
7. **Setup and environment notes:**
   - Wireless debugging turned itself off once between pairing and connecting, and the connect port changes each time it's re-enabled. You can read it with `su -c getprop service.adb.tls.port` over SSH.
   - Pairing the laptop key persists (`/data/misc/adb/adb_keys`).
   - adb's mDNS daemon isn't available on the laptop, so use explicit IP:port.

## What was not tested

- Motion, heater or print streaming. Long-run and load behaviour.
- Unplug/replug handling and `USB_DEVICE_ATTACHED` auto-launch. The manifest filter is present but was not exercised.
- The permission-denied path.
- Whether the "DTR off" reset happens with every close. It was seen consistently in 4 of 4 cases, but that is a small sample.
- Whether the queued reader stays clean at sustained full-rate RX. M503 is about 820 bytes in a burst, which is the heaviest RX tested.

## Code added (driver unchanged)

- `AndroidUsbIo.kt`: the real `UsbIo`. It has a direct mode or a queued Kotlin `UsbRequest` reader, and a verbose flag.
- `HwActivity.kt`: device list, permission request, and a run button or `--ez run true`, with `--es suite v1|v2|v3`.
- `HwSpike.kt`: the v1 sessions 1-5, the v2 edges / reset-on-close / integrity checks, and the v3 integrity repeats.
- `spike_bridge.py`: `hw_session`, `hw_edges`, `hw_integrity` added. The phase-1 functions are unchanged.
- `res/xml/device_filter.xml`, the manifest (USB host feature, attach intent filter), and `app/build.gradle.kts` (`-Pabi=`).
- `phase2_deploy.sh`: pair / connect / install+launch / evidence.
