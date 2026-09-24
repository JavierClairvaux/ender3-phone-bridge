#!/usr/bin/env python3
"""Layer 2: poll temperatures with M105 every N seconds (read-only, safe).

Marlin 1.1.x answers M105 with e.g.
  ok T:21.5 /0.0 B:21.3 /0.0 @:0 B@:0
Ctrl+C to stop. Like the Linux tooling, opening pulses DTR and reboots the
board; pass --no-reset if the printer is busy with something that must not
be interrupted.

Run as root:
  su -c "$PREFIX/bin/python $PWD/test_status.py"
"""
import argparse
import re
import time

import ch340_serial as ch

TEMP_RE = re.compile(r"T:\s*([-\d.]+)\s*/\s*([-\d.]+).*?B:\s*([-\d.]+)\s*/\s*([-\d.]+)")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ch.add_cli_args(ap)
    ap.add_argument("--interval", type=float, default=5.0)
    args = ap.parse_args()

    ser = ch.open_from_args(args, timeout=2)
    print("Opened via %s backend, chip version 0x%02x" % (ser.backend_name, ser.version))
    try:
        if not args.no_reset:
            print("Waiting 5 s for the board to boot...")
            time.sleep(5)
        while ser.in_waiting:
            ser.readline()
        while True:
            ser.write(b"M105\n")
            deadline = time.time() + 5
            parsed = False
            while time.time() < deadline:
                line = ser.readline().decode(errors="replace").strip()
                if not line:
                    continue
                m = TEMP_RE.search(line)
                if m:
                    t, tt, b, bt = (float(x) for x in m.groups())
                    print("%s  hotend %6.1f / %5.1f C   bed %6.1f / %5.1f C"
                          % (time.strftime("%H:%M:%S"), t, tt, b, bt))
                    parsed = True
                elif not line.startswith("ok"):
                    print("    " + line)
                if line.startswith("ok"):
                    break
            if not parsed:
                print("%s  (no temperature line parsed this round)" % time.strftime("%H:%M:%S"))
            time.sleep(args.interval)
    except KeyboardInterrupt:
        print("\nStopped.")
    finally:
        ser.close()


if __name__ == "__main__":
    main()
