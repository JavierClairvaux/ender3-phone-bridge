#!/usr/bin/env python3
"""Poll SD print progress (M27) every few seconds. Read-only.

Reports "Not SD printing" if nothing is currently running from the card, or
the byte offset / total bytes Marlin is reporting through the file.

Run as root:
  su -c "$PREFIX/bin/python $PWD/sd_status.py"
"""
import argparse
import re
import time

import ch340_serial as ch

PROGRESS_RE = re.compile(r"SD printing byte (\d+)/(\d+)")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--interval", type=float, default=10.0,
                    help="seconds between polls (default 10)")
    ch.add_cli_args(ap)
    args = ap.parse_args()

    ser = ch.open_from_args(args, timeout=3)
    print(f"Opened via {ser.backend_name} backend, chip version 0x{ser.version:02x}")
    print("Waiting 5s for the board to boot...")
    time.sleep(5)
    while ser.in_waiting:
        ser.readline()

    print(f"Polling M27 every {args.interval}s. Ctrl+C to stop.\n")
    try:
        while True:
            ser.reset_input_buffer()
            ser.write(b"M27\n")
            deadline = time.time() + 3
            progress = None
            raw_lines = []
            while time.time() < deadline:
                line = ser.readline().decode(errors="replace").strip()
                if not line:
                    continue
                raw_lines.append(line)
                m = PROGRESS_RE.search(line)
                if m:
                    progress = (int(m.group(1)), int(m.group(2)))
                if line.startswith("ok"):
                    break

            ts = time.strftime("%H:%M:%S")
            if progress:
                done, total = progress
                pct = 100 * done / total if total else 0.0
                print(f"[{ts}] {done}/{total} bytes ({pct:.1f}%)")
            elif raw_lines:
                print(f"[{ts}] {' / '.join(raw_lines)}")
            else:
                print(f"[{ts}] no response")
            time.sleep(args.interval)
    except KeyboardInterrupt:
        print("\nStopped.")
    finally:
        ser.close()


if __name__ == "__main__":
    main()
