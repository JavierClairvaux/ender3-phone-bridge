#!/usr/bin/env python3
"""Layer 0: can we open the CH340 over raw USB and talk to the chip itself?

Sends NOTHING to the printer. Only vendor control requests to the CH340:
  - read chip version        (0x5F)
  - read modem status reg    (0x95, 0x0706)
With --init-test it also runs the full init (baud/LCR, DTR+RTS on, no reset
pulse) and listens for 3 s, still without transmitting any bytes.

Run as root, e.g.:
  su -c "$PREFIX/bin/python $PWD/test_usb_probe.py"
"""
import argparse
import sys
import time

import ch340_serial as ch


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ch.add_cli_args(ap)
    ap.add_argument("--init-test", action="store_true",
                    help="also run full chip init and listen 3 s (no TX)")
    args = ap.parse_args()

    print("CH340 nodes found via sysfs:", ch.find_device_nodes() or "none")
    v = ch.ch341_get_divisor(args.baud)
    print("Divisor for %d baud: 0x%04x (0x%04x with bit 7), actual %.1f bps"
          % (args.baud, v, v | 0x80, ch.actual_baud(v)))

    fd = args.fd
    if fd is None:
        import os
        if os.environ.get("TERMUX_USB_FD"):
            fd = int(os.environ["TERMUX_USB_FD"])

    if fd is not None or args.backend == "usbfs":
        dev = ch.UsbfsBackend(path=args.device, fd=fd, debug=args.debug)
    elif args.backend == "pyusb":
        dev = ch.PyUSBBackend(debug=args.debug)
    else:
        try:
            dev = ch.PyUSBBackend(debug=args.debug)
        except Exception as e:
            print("pyusb backend failed: %s: %s" % (type(e).__name__, e))
            print("falling back to raw usbfs backend")
            dev = ch.UsbfsBackend(path=args.device, debug=args.debug)

    print("Opened with backend: %s  (bulk IN 0x%02x, OUT 0x%02x, max packet %d)"
          % (dev.name, dev.ep_in, dev.ep_out, dev.max_packet))
    try:
        ver = dev.ctrl_in(ch.REQ_READ_VERSION, 0, 0, 2)
        print("Chip version bytes: %s  -> version 0x%02x" % (ver.hex(), ver[0]))
        if ver[0] > 0x27:
            print("  (> 0x27: driver will set bit 7 so short replies are not held back)")
        if ver[0] < 0x30:
            print("  (< 0x30: LCR register write is skipped, chip defaults to 8N1)")
        st = dev.ctrl_in(ch.REQ_READ_REG, 0x0706, 0, 2)
        print("Modem status reg 0x0706: %s" % st.hex())
        try:
            dev.ctrl_in(ch.REQ_READ_REG, ch.REG_BREAK, 0, 2)
            print("Break register readable (no limited-prescaler quirk)")
        except Exception as e:
            print("Break register read failed (%s) -> limited-prescaler quirk chip" % e)
    finally:
        dev.close()

    if args.init_test:
        print("\nRunning full init (no reset pulse, no TX), listening 3 s...")
        ser = ch.CH340Serial(baudrate=args.baud, timeout=0.5, backend=args.backend,
                             device_path=args.device, fd=args.fd, init_mode=args.init,
                             reset_on_open=False, debug=args.debug)
        try:
            print("Init OK via %s, chip version 0x%02x" % (ser.backend_name, ser.version))
            end = time.time() + 3
            got = b""
            while time.time() < end:
                got += ser.read(256)
            print("Received %d bytes while idle: %r" % (len(got), got[:200]))
        finally:
            ser.close()
    print("\nPROBE OK")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print("\nPROBE FAILED: %s: %s" % (type(e).__name__, e))
        if "Access denied" in str(e) or "Errno 13" in str(e) or "Permission" in str(e):
            print("-> permissions: run as root (su -c ...), see README")
        raise SystemExit(1)
