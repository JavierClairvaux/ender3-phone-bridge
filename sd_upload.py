#!/usr/bin/env python3
"""Write a local .gcode file onto the printer's SD card over serial (M28/M29).

This does NOT print anything by itself - it copies the file. Marlin treats
every line sent after M28 as raw data to save to the SD file, not as G-code
to execute, so the original file (comments included) is preserved as-is.
It's fast: this is disk I/O, not real print motion, so even a multi-MB file
uploads in well under a minute, unlike the hours the same file takes to
actually print. That's the point of this whole approach: the host only
needs to stay connected for the brief upload, not for the multi-hour print.

Use --start to immediately select and start printing the file after upload
(M23 + M24) - or run sd_start.py separately later to do that on its own.

Run as root:
  su -c "$PREFIX/bin/python $PWD/sd_upload.py local.gcode SPOOL.GCO"
  su -c "$PREFIX/bin/python $PWD/sd_upload.py local.gcode SPOOL.GCO --start"
"""
import argparse
import os
import re
import sys
import time

import ch340_serial as ch

# Marlin's SD file *selection* (M23) resolves through the DOS 8.3 short name
# the filesystem generates for any long filename, even though M20 (list)
# can display long names. Uploading directly under an 8.3-safe name avoids
# any ambiguity about what that auto-generated short name actually is.
SD83_RE = re.compile(r"^[A-Za-z0-9_\-]{1,8}(\.[A-Za-z0-9_\-]{1,3})?$")


def check_83(name):
    if not SD83_RE.match(name):
        print(f"WARNING: '{name}' doesn't look 8.3-safe (<=8 char name, "
              f"<=3 char extension, letters/digits/_/- only). Marlin's file "
              f"selection uses the DOS short name, so an odd filename here "
              f"can end up selecting the wrong file later. Consider "
              f"something like SPOOL.GCO instead.", file=sys.stderr)


def read_until_ok(ser, collect=False):
    """Read lines until one starting with 'ok'. Returns the collected lines
    if collect=True (used to sanity-check the M28 response for a failure)."""
    lines = []
    while True:
        line = ser.readline().decode(errors="replace").strip()
        if not line:
            continue
        if collect:
            lines.append(line)
        else:
            print("    " + line)
        if line.startswith("ok"):
            return lines


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("local_file", help="local .gcode file to upload")
    ap.add_argument("sd_name", help="filename to save as on the SD card (8.3-safe recommended)")
    ap.add_argument("--start", action="store_true",
                    help="select and start the print immediately after upload (M23+M24)")
    ch.add_cli_args(ap)
    args = ap.parse_args()

    check_83(args.sd_name)

    with open(args.local_file, "rb") as f:
        raw = f.read()
    lines = raw.decode(errors="replace").splitlines()
    size = os.path.getsize(args.local_file)
    print(f"Uploading {args.local_file} ({size} bytes, {len(lines)} lines) as '{args.sd_name}'...")

    ser = ch.open_from_args(args, timeout=10)
    print(f"  backend={ser.backend_name} chip_version=0x{ser.version:02x}")
    time.sleep(5)
    while ser.in_waiting:
        ser.readline()

    print(f"M28 {args.sd_name}")
    ser.write(f"M28 {args.sd_name}\n".encode())
    resp = read_until_ok(ser, collect=True)
    resp_text = " / ".join(resp).lower()
    if "error" in resp_text or "fail" in resp_text or "no sd card" in resp_text:
        print(f"M28 FAILED: {' / '.join(resp)}")
        print("(no SD card inserted, card unreadable, or filesystem the card "
              "reader library can't parse - re-check the card.)")
        ser.close()
        sys.exit(1)
    for l in resp:
        print("    " + l)

    start_time = time.time()
    try:
        for i, line in enumerate(lines, 1):
            ser.write((line + "\n").encode())
            read_until_ok(ser)
            if i % 2000 == 0 or i == len(lines):
                elapsed = time.time() - start_time
                print(f"[{elapsed:5.1f}s] {i}/{len(lines)} lines written")
        ser.write(b"M29\n")
        read_until_ok(ser)
        elapsed = time.time() - start_time
        print(f"\nUpload done in {elapsed:.1f}s.")
    except Exception:
        print("\nUpload failed partway through. Sending M29 anyway so the SD "
              "card isn't left stuck in write mode - but the file on the "
              "card is now incomplete/corrupt, don't try to print it.")
        try:
            ser.write(b"M29\n")
            read_until_ok(ser)
        except Exception:
            pass
        ser.close()
        raise

    if args.start:
        print(f"\nM23 {args.sd_name}")
        ser.write(f"M23 {args.sd_name}\n".encode())
        read_until_ok(ser)
        print("M24 (start print)")
        ser.write(b"M24\n")
        read_until_ok(ser)
        print("\nPrint started from SD card. Safe to disconnect now - the "
              "printer continues on its own. Use sd_status.py to check "
              "progress later.")

    ser.close()


if __name__ == "__main__":
    main()
