#!/usr/bin/env python3
"""Layer 1: open the printer link and ask Marlin for firmware info (M115).

M115 is read-only. Expected answer on this printer (seen on the Linux host):
  FIRMWARE_NAME:Marlin 1.1.6.2 ... MACHINE_TYPE:Ender-3 ...
  ok

By default the port is opened with a DTR pulse, which should reboot the
board just like opening /dev/ttyUSB0 on Linux; the boot banner ("start",
"echo:Marlin ...") printed during the wait confirms the reset works.

Run as root:
  su -c "$PREFIX/bin/python $PWD/test_m115.py"
"""
import argparse
import time

import ch340_serial as ch


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ch.add_cli_args(ap)
    ap.add_argument("--boot-wait", type=float, default=5.0,
                    help="seconds to wait/listen after open (default 5)")
    args = ap.parse_args()

    ser = ch.open_from_args(args, timeout=1)
    print("Opened via %s backend, chip version 0x%02x, init=%s, reset=%s"
          % (ser.backend_name, ser.version, args.init, not args.no_reset))
    try:
        print("Listening %.1f s for boot messages..." % args.boot_wait)
        boot = b""
        end = time.time() + args.boot_wait
        while time.time() < end:
            boot += ser.read(256)
        if boot:
            for line in boot.decode(errors="replace").splitlines():
                print("  boot| " + line)
            if boot.count(b"\xff") + boot.count(b"\x00") > len(boot) // 3:
                print("  !! bytes look like garbage -> likely baud/divisor problem "
                      "(try --init legacy, or --debug)")
        else:
            print("  (nothing received - fine with --no-reset; with reset it means "
                  "the DTR pulse did not reboot the board, or RX is not working)")

        print("\n>>> M115")
        ser.write(b"M115\n")
        got_ok = False
        end = time.time() + 10
        while time.time() < end:
            line = ser.readline().decode(errors="replace").strip()
            if not line:
                continue
            print("  " + line)
            if line.startswith("ok"):
                got_ok = True
                break
        if got_ok:
            print("\nM115 TEST OK")
        else:
            print("\nM115 TEST FAILED: no 'ok' within 10 s")
            raise SystemExit(1)
    finally:
        ser.close()


if __name__ == "__main__":
    main()
