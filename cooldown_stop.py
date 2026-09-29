#!/usr/bin/env python3
import time
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

print('>>> M104 S0 (hotend off)')
ser.write(b'M104 S0\n'); wait_ok()
print('>>> M140 S0 (bed off)')
ser.write(b'M140 S0\n'); wait_ok()
print('>>> M84 (disable steppers)')
ser.write(b'M84\n'); wait_ok()
print('Cooldown/stop commands sent.')
ser.close()
