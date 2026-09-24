#!/usr/bin/env python3
"""Send G28 (home all axes) over the CH340 userspace driver.

Run as root:
  su -c "$PREFIX/bin/python $PWD/home_printer_phone.py"
"""
import argparse
import time

import ch340_serial as ch


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ch.add_cli_args(ap)
    ap.add_argument("--timeout", type=float, default=90.0,
                    help="seconds to wait for homing to finish (default 90)")
    args = ap.parse_args()

    ser = ch.open_from_args(args, timeout=2)
    print("Opened via %s backend, chip version 0x%02x" % (ser.backend_name, ser.version))
    try:
        print("Waiting 5s for boot...")
        time.sleep(5)
        while ser.in_waiting:
            ser.readline()

        print("Sending G28 (home all axes)...")
        ser.write(b"G28\n")

        deadline = time.time() + args.timeout
        while time.time() < deadline:
            line = ser.readline().decode(errors="replace").strip()
            if line:
                print("  ", line)
            if line == "ok":
                print("Homing complete.")
                break
        else:
            print("Timed out waiting for homing to finish.")
    finally:
        ser.close()


if __name__ == "__main__":
    main()
