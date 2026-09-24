#!/usr/bin/env python3
"""Stream a G-code file to the Ender 3 over the userspace CH340 driver.

Same flow control as the proven print_gcode.py on the Linux host: one
command at a time, wait for "ok", tolerate busy/temperature chatter, and
turn heaters off on Ctrl+C. Only the transport differs (CH340Serial
instead of serial.Serial).

Run as root:
  su -c "$PREFIX/bin/python $PWD/print_gcode_phone.py file.gcode"
"""
import argparse
import sys
import time

import ch340_serial as ch

BAUD = 115200


def strip_line(raw):
    line = raw.split(";", 1)[0].strip()
    return line


def wait_for_ok(ser, label=""):
    """Read lines until an 'ok' is seen. Tolerates busy/temperature chatter."""
    while True:
        line = ser.readline().decode(errors="replace").strip()
        if not line:
            continue
        if line.startswith("ok"):
            return
        if line.startswith("echo:busy"):
            continue
        if line.startswith("T:") or " T:" in line:
            print(f"    (temp) {line}")
            continue
        if line.lower().startswith("error") or line.lower().startswith("!!"):
            print(f"    !! PRINTER ERROR: {line}")
            continue
        print(f"    {line}")


def main(gcode_path, args):
    with open(gcode_path) as f:
        raw_lines = f.readlines()
    commands = []
    for raw in raw_lines:
        line = strip_line(raw)
        if line:
            commands.append(line)
    total = len(commands)
    print(f"Loaded {total} G-code commands from {gcode_path}")
    print("Connecting to CH340 over raw USB...")
    ser = ch.CH340Serial(baudrate=args.baud, timeout=10, backend=args.backend,
                         device_path=args.device, fd=args.fd, init_mode=args.init,
                         reset_on_open=not args.no_reset, debug=args.debug)
    print(f"  backend={ser.backend_name} chip_version=0x{ser.version:02x}")
    time.sleep(5)
    while ser.in_waiting:
        ser.readline()

    # Firmware-level safety net: if the host/USB link dies mid-print (cable
    # jostled loose from printer vibration, phone kills the process, etc.),
    # the printer itself turns off its heaters after this many idle seconds
    # with no new commands. This works independently of anything the host
    # script can do once the connection is actually gone.
    print(f"Setting inactivity shutdown timer (M85 S{args.idle_shutdown})...")
    ser.write(f"M85 S{args.idle_shutdown}\n".encode())
    wait_for_ok(ser)

    print("Connected. Starting print.\n")
    start_time = time.time()
    try:
        for i, cmd in enumerate(commands, 1):
            ser.write((cmd + "\n").encode())
            wait_for_ok(ser, cmd)
            if i % 200 == 0 or i == total:
                pct = 100 * i / total
                elapsed = time.time() - start_time
                print(f"[{elapsed:6.1f}s] {i}/{total} ({pct:.1f}%) - last: {cmd}")
        elapsed = time.time() - start_time
        print(f"\nDone. {total} commands sent in {elapsed:.1f}s.")
    except KeyboardInterrupt:
        print("\nInterrupted - turning off heaters for safety.")
        try:
            ser.write(b"M104 S0\n")
            ser.write(b"M140 S0\n")
            time.sleep(1)
        except Exception as e:
            print(f"  (heater-off command failed too: {e}; "
                  f"relying on the M85 inactivity timer set at start)")
    except Exception as e:
        # Connection died mid-print (USB dropout, etc). The write below will
        # usually fail too since the link is already gone - that's fine,
        # the M85 timer set at the start of the print is the real safety
        # net here, not this best-effort attempt.
        print(f"\nPRINT FAILED: {type(e).__name__}: {e}")
        print("Attempting heater-off anyway (may not succeed if the link is dead)...")
        try:
            ser.write(b"M104 S0\n")
            ser.write(b"M140 S0\n")
            time.sleep(1)
        except Exception as e2:
            print(f"  heater-off failed as expected: {e2}")
        raise
    finally:
        ser.close()


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="Stream G-code over userspace CH340")
    ap.add_argument("gcode", help="path to .gcode file")
    ap.add_argument("--idle-shutdown", type=int, default=300,
                    help="M85 inactivity timer in seconds: firmware turns off "
                         "heaters if it gets no commands for this long, "
                         "independent of the host/USB connection (default 300)")
    ch.add_cli_args(ap)
    a = ap.parse_args()
    if a.baud != BAUD:
        print(f"note: using non-default baud {a.baud}", file=sys.stderr)
    main(a.gcode, a)
