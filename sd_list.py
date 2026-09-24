#!/usr/bin/env python3
"""List files on the printer's SD card (M20). Read-only.

Run as root:
  su -c "$PREFIX/bin/python $PWD/sd_list.py"
"""
import argparse
import time

import ch340_serial as ch


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ch.add_cli_args(ap)
    args = ap.parse_args()

    ser = ch.open_from_args(args, timeout=5)
    print(f"Opened via {ser.backend_name} backend, chip version 0x{ser.version:02x}")
    time.sleep(5)
    while ser.in_waiting:
        ser.readline()

    ser.write(b"M20\n")
    deadline = time.time() + 10
    while time.time() < deadline:
        line = ser.readline().decode(errors="replace").strip()
        if not line:
            continue
        print(line)
        if line.startswith("ok"):
            break
    else:
        print("(timed out waiting for full listing)")
    ser.close()


if __name__ == "__main__":
    main()
