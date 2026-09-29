#!/usr/bin/env python3
"""Move the nozzle up a small, safe relative amount without resetting the
board or trusting its (possibly stale) absolute position tracking.

Run as root:
  su -c "$PREFIX/bin/python $PWD/nudge_z.py"
"""
import time
import ch340_serial as ch

ser = ch.CH340Serial(baudrate=115200, timeout=5, reset_on_open=False)
print(f"backend={ser.backend_name} chip_version=0x{ser.version:02x}")
time.sleep(2)
while ser.in_waiting:
    ser.readline()

for cmd in (b"G91\n", b"G1 Z10 F300\n", b"G90\n"):
    ser.write(cmd)
    deadline = time.time() + 10
    print(f">>> {cmd.decode().strip()}")
    while time.time() < deadline:
        line = ser.readline().decode(errors="replace").strip()
        if line:
            print("  ", line)
        if line == "ok":
            break

ser.close()
