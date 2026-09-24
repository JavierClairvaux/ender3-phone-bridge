#!/usr/bin/env python3
"""Select and start a print already stored on the printer's SD card (M23+M24).

Use this after sd_upload.py has copied the file, or if the card was written
some other way (e.g. a card reader on a computer). Once the print starts you
can disconnect - Marlin continues autonomously from the SD card, no host
connection required for the rest of the print.

Run as root:
  su -c "$PREFIX/bin/python $PWD/sd_start.py SPOOL.GCO"
"""
import argparse
import time

import ch340_serial as ch


def read_until_ok(ser):
    while True:
        line = ser.readline().decode(errors="replace").strip()
        if not line:
            continue
        print("    " + line)
        if line.startswith("ok"):
            return


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sd_name", help="filename on the SD card to print")
    ch.add_cli_args(ap)
    args = ap.parse_args()

    ser = ch.open_from_args(args, timeout=10)
    print(f"Opened via {ser.backend_name} backend, chip version 0x{ser.version:02x}")
    time.sleep(5)
    while ser.in_waiting:
        ser.readline()

    print(f"M23 {args.sd_name}")
    ser.write(f"M23 {args.sd_name}\n".encode())
    read_until_ok(ser)

    print("M24 (start)")
    ser.write(b"M24\n")
    read_until_ok(ser)

    print("\nPrint started from SD card. Safe to disconnect now - the "
          "printer continues on its own. Use sd_status.py to check progress.")
    ser.close()


if __name__ == "__main__":
    main()
