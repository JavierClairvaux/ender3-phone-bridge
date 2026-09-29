#!/usr/bin/env python3
import time
import re
import ch340_serial as ch

ser = ch.CH340Serial(baudrate=115200, timeout=5, reset_on_open=False)
print(f'backend={ser.backend_name}')
time.sleep(2)
while ser.in_waiting:
    ser.readline()

def wait_ok():
    while True:
        line = ser.readline().decode(errors='replace').strip()
        if line:
            print('  ', line)
        if line == 'ok':
            return

print('>>> M104 S200 (set nozzle target 200C, non-blocking)')
ser.write(b'M104 S200\n'); wait_ok()

print('>>> polling M105 every 5s for up to 3 minutes...')
start = time.time()
last_temp = None
while time.time() - start < 180:
    ser.write(b'M105\n')
    while True:
        line = ser.readline().decode(errors='replace').strip()
        if not line:
            continue
        if line.startswith('ok') or 'T:' in line:
            print(f'  t+{int(time.time()-start):3d}s  {line}')
            m = re.search(r'^ok T:([\d.]+)', line) or re.search(r' T:([\d.]+)', line)
            if m:
                last_temp = float(m.group(1))
            if line.startswith('ok'):
                break
    if last_temp is not None and last_temp >= 198.0:
        print('Reached target. Nozzle is hot -- clean carefully, avoid burns.')
        break
    time.sleep(5)

print('Done. Leaving nozzle heater ON at 200C target (not turning off) so you have time to clean.')
ser.close()
