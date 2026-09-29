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

print('>>> holding at 200C for 5 minutes, checking every 15s...')
start = time.time()
reached_logged = False
while time.time() - start < 300:
    ser.write(b'M105\n')
    while True:
        line = ser.readline().decode(errors='replace').strip()
        if not line:
            continue
        if line.startswith('ok') or 'T:' in line:
            print(f'  t+{int(time.time()-start):3d}s  {line}')
            if not reached_logged:
                m = re.search(r' T:([\d.]+)', line) or re.search(r'^ok T:([\d.]+)', line)
                if m and float(m.group(1)) >= 198.0:
                    print('  -- reached target --')
                    reached_logged = True
            if line.startswith('ok'):
                break
    time.sleep(15)

print('5 minutes elapsed. Nozzle still holding at 200C target (heater stays on in firmware).')
ser.close()
