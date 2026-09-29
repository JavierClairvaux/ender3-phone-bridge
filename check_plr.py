#!/usr/bin/env python3
"""Check Power Loss Recovery status (M413) without resetting the board.
Drains any backlog thoroughly first so the M413 reply isn't mixed up with
stale boot text still trickling out from earlier."""
import time
import ch340_serial as ch

ser = ch.CH340Serial(baudrate=115200, timeout=2, reset_on_open=False)
print(f"backend={ser.backend_name} chip_version=0x{ser.version:02x}")

print("Draining any backlog for 8s...")
end = time.time() + 8
drained = []
while time.time() < end:
    line = ser.readline().decode(errors="replace").strip()
    if line:
        drained.append(line)
print(f"  drained {len(drained)} lines: {drained}")

ser.reset_input_buffer()
print("\n>>> M413")
ser.write(b"M413\n")
deadline = time.time() + 5
while time.time() < deadline:
    line = ser.readline().decode(errors="replace").strip()
    if line:
        print("  ", line)
    if line == "ok":
        break

ser.close()
