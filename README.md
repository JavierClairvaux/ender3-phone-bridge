# ender3-phone-bridge

These scripts drive a Creality Ender 3 (Marlin 1.1.6.2) from a rooted Android phone in Termux, over the phone's USB-C OTG port. The printer's CH340 USB-serial chip (`1a86:7523`) is handled by a driver that runs entirely in userspace, because the phone's kernel has no `ch341` driver built in or loadable, so it never creates a `/dev/ttyUSB0`.

> **Status: confirmed working on real hardware.** Tested on a rooted Redmi Note 9 (LineageOS, Termux, `pyusb` backend) against a real Ender 3: chip version read back correctly, `M115` returned the real firmware banner after a DTR-triggered reboot, `M105` temp polling worked, `G28` physically homed all three axes, and a full ~3900-line sliced print job streamed and completed end to end with no kernel serial driver involved at any point. Use the numbered steps below to reproduce it on your own device.

## Files

| File | Purpose | Talks to printer? |
|---|---|---|
| `ch340_serial.py` | The driver. `CH340Serial` is a pyserial-like object: `write`, `read`, `readline`, `in_waiting`, `reset_input_buffer`, `close`, `dtr`/`rts`. It has two backends: `pyusb` (libusb) and `usbfs` (raw ioctls, no libusb). | – |
| `test_usb_probe.py` | Layer 0: opens the chip and reads its version and status registers. | No, chip only |
| `test_m115.py` | Layer 1: opens the port (resetting the board), shows the boot banner, sends `M115`. | Read-only |
| `test_status.py` | Layer 2: sends `M105` every 5 s and prints hotend and bed temperatures. | Read-only |
| `print_gcode_phone.py` | Layer 3: streams a G-code file and executes it live, one line at a time. Same flow control as `print_gcode.py` on the Linux host, plus an `M85` inactivity-shutdown timer sent at the start as a firmware-level safety net (see "SD card workflow" below for why this matters less there). | **Prints**, needs to stay connected the whole time |
| `sd_upload.py` | Writes a `.gcode` file onto the printer's SD card over serial (`M28`/`M29`) - fast disk I/O, not real print motion. Optional `--start` to also kick off the print immediately after. | Writes only (or starts, with `--start`) |
| `sd_start.py` | Selects and starts a file already on the SD card (`M23`+`M24`). | **Starts** a print, then you can disconnect |
| `sd_status.py` | Polls SD print progress (`M27`) every few seconds. | Read-only |
| `sd_list.py` | Lists files on the SD card (`M20`). | Read-only |
| `run_termux_usb.sh` | Fallback for running any of the above without root, through `termux-usb`. | – |

### SD card workflow (recommended for long prints)

`print_gcode_phone.py` streams G-code live and needs the phone connected for the entire print - for a multi-hour job that means staying vulnerable to a jostled cable, the phone's background-process policy, or an unverified long-duration driver session for the whole time (see the project's incident notes on a real 3-hour print for what this looks like in practice).

Printing from the SD card sidesteps nearly all of that: Marlin reads G-code directly off the card and runs the print autonomously, no host connection required once it starts. The phone's job shrinks from "stay connected for 3 hours" to "stay connected for the ~minute it takes to upload the file":

```sh
python sd_upload.py my_part.gcode PART.GCO --start   # upload + start in one step
# ...or separately:
python sd_upload.py my_part.gcode PART.GCO
python sd_start.py PART.GCO

# now you can disconnect entirely - the printer keeps going on its own

python sd_status.py                                   # check progress later, any time
```

Notes:
- **Use an 8.3-safe SD filename** (e.g. `PART.GCO`, not `my_cool_part_v2.gcode`). Marlin's file listing (`M20`) can show long names, but file *selection* (`M23`) resolves through the DOS short name the filesystem auto-generates for any long name - an 8.3-safe name from the start avoids any ambiguity about what that generated short name actually is.
- **exFAT isn't supported** by this era of Marlin's SD stack - format the card FAT16 or FAT32. Practically only matters above ~32GB anyway; anything smaller formats as FAT16/FAT32 by default on any OS.
- If `sd_upload.py` reports `M28 FAILED`, check that a card is actually inserted and formatted correctly - the same `echo:TF init fail` boot message covered in the failure-modes table below shows up here too if the card reader can't see a card.

Every script accepts the same options: `--backend {auto,pyusb,usbfs}`, `--device /dev/bus/usb/BBB/DDD`, `--fd N`, `--init {kernel,legacy}`, `--no-reset`, `--baud N` and `--debug`. `--debug` logs every control transfer and every byte received or sent.

## 1. Setup in Termux

```sh
pkg update
pkg install -y python libusb clang
pip install pyusb
```

`clang` is only there in case pip needs to build something; pyusb itself is pure Python.

To copy the folder onto the phone, use `scp -P 8022 -r ender3-phone-bridge u0_a184@<phone>:~/`, or put it in `~/storage/shared` and copy it from there. Then:

```sh
cd ~/ender3-phone-bridge
```

## 2. Running as root

On Android, `/dev/bus/usb/001/002` is owned by `root:usb` with mode `0660`. The Termux user isn't in the `usb` group, so libusb or usbfs has to run as root to open it. That's also why a plain run as the Termux user fails with `[Errno 13] Access denied`, which is expected.

`su` gives you a minimal shell that doesn't have Termux's `PATH` or `LD_LIBRARY_PATH`. So pass the full path to Python and export the library path yourself:

```sh
P=/data/data/com.termux/files/usr
su -c "export PATH=$P/bin:\$PATH LD_LIBRARY_PATH=$P/lib; cd $PWD && python test_usb_probe.py"
```

Or install `tsu` (`pkg install tsu`), which keeps the Termux environment:

```sh
tsu -c "python test_usb_probe.py"   # or just run `tsu`, then python ... at the # prompt
```

The driver looks for libusb at `$PREFIX/lib/libusb-1.0.so`, falling back to `/data/data/com.termux/files/usr/lib/libusb-1.0.so`. So under `su` it finds the library even if `find_library` doesn't.

SELinux shouldn't get in the way, since Magisk's `su` runs in the permissive `magisk` domain. If you use a different root solution and see `EACCES` even as uid 0, SELinux is the likely cause.

### Fallback without root: `termux-usb`

This needs the Termux:API app and `pkg install termux-api`.

```sh
termux-usb -l                        # lists e.g. ["/dev/bus/usb/001/002"]
./run_termux_usb.sh test_m115.py     # Android shows a "allow access" dialog the first time
```

The wrapper runs `termux-usb -r -E -e <script>`. Android opens the device and hands over its file descriptor in `TERMUX_USB_FD`, and `ch340_serial.py` detects that and uses its `usbfs` backend on it.

## 3. Finding the device when bus or device numbers change

The device number changes every time the cable is replugged. You normally don't need to care: `pyusb` looks the device up by VID:PID, and `usbfs` searches sysfs itself. To check by hand:

```sh
for d in /sys/bus/usb/devices/*/; do echo "$d -> $(cat "$d/idVendor" 2>/dev/null):$(cat "$d/idProduct" 2>/dev/null) bus=$(cat "$d/busnum" 2>/dev/null) dev=$(cat "$d/devnum" 2>/dev/null)"; done
```

`1a86:7523 bus=1 dev=2` corresponds to `/dev/bus/usb/001/002`. `python ch340_serial.py` prints the same thing and also runs a self-check of the baud-rate math. The self-check needs no hardware. To list what pyusb sees:

```sh
python -c "import usb.core; [print(hex(d.idVendor), hex(d.idProduct), d.bus, d.address) for d in usb.core.find(find_all=True)]"
```

## 4. Test in this order (all as root)

```sh
python ch340_serial.py                  # 0a. divisor math: 115200 -> 0xcc03 (0xcc83 with bit 7); lists CH340 node
python test_usb_probe.py                # 0b. opens chip, prints version; nothing sent to printer
python test_usb_probe.py --init-test    # 0c. full init + listens 3 s; still sends nothing
python test_m115.py                     # 1.  expect boot banner, then FIRMWARE_NAME:Marlin 1.1.6.2 ... MACHINE_TYPE:Ender-3, ok
python test_status.py                   # 2.  temps every 5 s, Ctrl+C to stop
python print_gcode_phone.py file.gcode  # 3.  real print, only after 1 and 2 pass
```

If the default `auto` backend fails, repeat the step with `--backend usbfs`. If it still fails, add `--debug` and save the output.

## 5. Failure modes and what they mean

| Symptom | Meaning / next step |
|---|---|
| `USBError: [Errno 13] Access denied` / `PermissionError` / `EACCES` opening `/dev/bus/usb/...` | Not running as root. Use `su -c` / `tsu` as in section 2, or the `termux-usb` fallback. |
| `pyusb could not load libusb-1.0` | `pkg install libusb`, and export `LD_LIBRARY_PATH=$PREFIX/lib` under `su`. |
| `pyusb/libusb found no 1a86:7523 device` while sysfs shows it | libusb couldn't enumerate devices on this Android build. The `auto` backend falls back to `usbfs` on its own; `--backend usbfs` forces it. |
| `[Errno 16] Resource busy` on claim | Something else holds the interface: another script, or an Android app that took the device. Kill it or replug. The driver already tries to detach a kernel driver. |
| Timeout or `EPIPE` on the **first** control transfer (`0x5F` read version) | The chip isn't answering vendor requests. Check the VID:PID and the cable, and replug. |
| Timeout or `EPIPE` on a **later** init transfer (`0xA1`, `0x9A`) | The request codes or values don't suit this chip revision. Try `--init legacy`, send the `--debug` output, and compare it against `ch341.c`. |
| Init OK, but `test_m115` shows **garbage** (`\xff`, `\x00`, random bytes) | The baud divisor is wrong. Try `--init legacy`. Also try `--baud 250000` in case this firmware build is set to 250000. |
| Init OK, **nothing at all** received, and no boot banner | Either the DTR pulse didn't reset the board, or RX isn't working. Run `test_m115.py --no-reset` and see whether `M115` gets an answer. If it answers, only the reset is missing, which is harmless. |
| Replies arrive in bursts of 32 bytes or late | The "don't wait for a full packet" bit (0x80 in the divisor register) isn't taking effect. Send the chip version from `test_usb_probe.py`. |
| `CH340Error: USB read failed` mid-run | The device disconnected, e.g. the cable was bumped or the phone suspended USB. Replug and reopen. |
| Boot banner shows `echo:TF init fail` | Marlin can't see an SD card: nothing inserted, card unreadable, or an unsupported filesystem. Harmless if you're only using `print_gcode_phone.py`'s live streaming, but `sd_upload.py`/`sd_start.py`/`sd_list.py` need this fixed first - check the card is inserted and formatted FAT16/FAT32 (not exFAT). |

## 6. Where the protocol comes from, and how sure we are

Primary source: the mainline Linux `drivers/usb/serial/ch341.c`, checked against current `torvalds/linux` master.

**High confidence.** These values are copied straight from `ch341.c`:
- Control request types: `0x40` (vendor, device, OUT) and `0xC0` (vendor, device, IN), with a 1000 ms timeout.
- Init order, as in `ch341_configure()`:
  1. `0x5F` read version (2 bytes)
  2. `0xA1` serial init (`wValue=0`, `wIndex=0`)
  3. `0x9A` write register pair `0x1312` (DIVISOR<<8 | PRESCALER), set to the divisor value
  4. `0x9A` write register pair `0x2518` (LCR2<<8 | LCR), set to `0xC3` (RX+TX enable, 8 data bits, no parity, 1 stop bit). This step is skipped when the chip version is below `0x30`, as in the kernel.
  5. `0xA4` modem control, set to `~mcr`
- Baud math: a line-by-line port of `ch341_get_divisor()`. **115200 → `0xCC03`**: prescaler 3, divisor 52 at the lower base clock, giving an actual rate of 115384.6 bps (+0.16 %). The older driver formula produces the same `0xCC03`, which is a useful cross-check.
- Bit 7 (`0x80`) is ORed into the divisor value when the chip version is above `0x27`, giving `0xCC83`. Without it the chip holds received data until a full 32-byte packet arrives, which would stall short replies like `ok`.
- Modem control lines: DTR is bit 5 and RTS is bit 6, sent inverted as `wValue` of request `0xA4`. `0xFF9F` means both are asserted, `0xFFFF` means both are released.
- Modem status: register pair `0x0706` read with `0x95`; the bits are inverted.
- Quirk detection: if reading register `0x05` with `0x95` returns `EPIPE`, the chip gets the limited-prescaler quirk. That quirk doesn't affect 115200.

**Confirmed on hardware** (Redmi Note 9, LineageOS, rooted, `pyusb` backend, default `kernel` init mode):
- **DTR auto-reset.** On open, the driver releases DTR and RTS, waits 100 ms, then asserts both — the same edge Linux produces when it opens the tty. The Ender 3's Melzi board couples DTR to RESET through a capacitor, and it does reset: `test_m115` showed the real `start` / `echo:Marlin 1.1.6.2` boot banner. If it doesn't on your board, use `--no-reset`.
- **The default init sequence and `pyusb` backend**, end to end, including a full real print job.

**Best-effort. Not needed on the test device, so still unverified:**
- **`--init legacy`.** This is the sequence from the older Linux driver (before about v5.5), also used by `usb-serial-for-android`'s `Ch34xSerialDriver`. It adds reads of `0x2518` and `0x0706`, writes `0xC3` to `0x2518`, sends `0xA1` with `0x501F`/`0xD90A`, and writes a second baud register, `0x0F2C` (set to `0x08` for 115200). It's there in case the default sequence misbehaves on a different chip revision.
- **The `usbfs` backend.** It calls `USBDEVFS_CONTROL`, `USBDEVFS_BULK` and `USBDEVFS_CLAIMINTERFACE` directly, with the struct layouts from `linux/usbdevice_fs.h` computed through ctypes. On 64-bit these come to `0xC0185500`, `0xC0185502` and `0x8004550F`, which match the known values. Descriptor parsing was tested on a synthetic CH340 descriptor, but `pyusb` is what actually ran on the phone, so this remains an untested fallback for Android builds where libusb can't enumerate devices as root.

**Design choices worth knowing about:**
- A background thread keeps reading from bulk IN, **one 32-byte packet per transfer**, into a buffer. That buffer drives `in_waiting`, `read` and `readline`. Reading one packet at a time matters: when a larger bulk read times out, both usbfs and pyusb throw away the bytes already received.
- `readline()` follows pyserial: it returns everything up to and including `\n`. On timeout it returns whatever partial data it has, or `b""` if there's none. `timeout=None` blocks forever.
- Writes are synchronous bulk OUT transfers in chunks of up to 1024 bytes, with a 5 s write timeout. `flush()` does nothing.
- `close()` releases DTR and RTS, like `HUPCL` on Linux. That doesn't reset the board.
