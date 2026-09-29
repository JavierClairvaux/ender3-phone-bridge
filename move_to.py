#!/usr/bin/env python3
"""Move to an absolute X/Y/Z position, carefully, one command at a time.
Prints the resulting position (M114) so we can confirm where it actually is.

Does NOT reset the board (assumes it's already homed this session).

Usage:
  su -c "$PREFIX/bin/python $PWD/move_to.py --z 5"                # Z only
  su -c "$PREFIX/bin/python $PWD/move_to.py --x 10 --y 10 --z 5"  # full move
  su -c "$PREFIX/bin/python $PWD/move_to.py --z 0.2 --f 100"      # slow final approach
"""
import argparse
import time
import ch340_serial as ch


def wait_ok(ser):
    while True:
        line = ser.readline().decode(errors="replace").strip()
        if line:
            print("  ", line)
        if line == "ok":
            return


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--x", type=float, default=None)
    ap.add_argument("--y", type=float, default=None)
    ap.add_argument("--z", type=float, default=None)
    ap.add_argument("--f", type=int, default=1500, help="feedrate mm/min (default 1500)")
    ch.add_cli_args(ap)
    args = ap.parse_args()

    ser = ch.CH340Serial(baudrate=args.baud, timeout=5, reset_on_open=False)
    print(f"backend={ser.backend_name} chip_version=0x{ser.version:02x}")
    time.sleep(1)
    while ser.in_waiting:
        ser.readline()

    parts = ["G1"]
    if args.x is not None:
        parts.append(f"X{args.x}")
    if args.y is not None:
        parts.append(f"Y{args.y}")
    if args.z is not None:
        parts.append(f"Z{args.z}")
    parts.append(f"F{args.f}")
    cmd = " ".join(parts) + "\n"

    print(f">>> {cmd.strip()}")
    ser.write(cmd.encode())
    wait_ok(ser)

    print(">>> M114")
    ser.write(b"M114\n")
    wait_ok(ser)

    ser.close()


if __name__ == "__main__":
    main()
