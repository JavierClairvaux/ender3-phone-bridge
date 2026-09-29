#!/usr/bin/env python3
"""Home, then travel to one bed corner/point and lower to a target Z, all in
ONE continuous connection - so a possible reset-on-open can't silently
invalidate position partway through the sequence (each separate script
invocation tonight may have been resetting the board even with
reset_on_open=False, wiping position between steps).

Usage:
  su -c "$PREFIX/bin/python $PWD/check_corner.py --x 10 --y 10 --z 0.2"
"""
import argparse
import time
import ch340_serial as ch


def wait_ok(ser, label=""):
    while True:
        line = ser.readline().decode(errors="replace").strip()
        if line:
            print("  ", line)
        if line == "ok":
            return


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--x", type=float, required=True)
    ap.add_argument("--y", type=float, required=True)
    ap.add_argument("--z", type=float, default=0.2, help="final height to lower to (default 0.2)")
    ap.add_argument("--travel-z", type=float, default=5.0, help="safe travel height (default 5)")
    ch.add_cli_args(ap)
    args = ap.parse_args()

    ser = ch.CH340Serial(baudrate=args.baud, timeout=5, reset_on_open=True)
    print(f"backend={ser.backend_name} chip_version=0x{ser.version:02x}")
    print("Waiting 5s for boot...")
    time.sleep(5)
    while ser.in_waiting:
        ser.readline()

    print(">>> G28 (home all axes)")
    ser.write(b"G28\n")
    wait_ok(ser)

    print(f">>> travel to X{args.x} Y{args.y} at Z{args.travel_z} (safe height)")
    ser.write(f"G1 X{args.x} Y{args.y} Z{args.travel_z} F3000\n".encode())
    wait_ok(ser)

    print(f">>> lowering to Z{args.z}")
    ser.write(f"G1 Z{args.z} F300\n".encode())
    wait_ok(ser)

    print(">>> M114")
    ser.write(b"M114\n")
    wait_ok(ser)

    print(f"\nNozzle should now be at X{args.x} Y{args.y} Z{args.z}. Check the paper-drag test now.")
    ser.close()


if __name__ == "__main__":
    main()
