#!/usr/bin/env bash
# Real-device smoke test: M115 ONLY, inside the full app's service + USB permission flow.
# Two sessions (connect -> M115 -> disconnect), each opened with a 4 s listen window
# so any boot banner (= board reset) would be caught; idle M105 polling disabled.
# The real-printer safety lock stays ON (default) the whole time.
set -u
OUT=${1:-evidence/phone_smoke.txt}
PKG=com.example.chaquopyspike
SVC=$PKG/com.javcabr.printerbridge.service.PrinterService
P() { ~/Android/Sdk/platform-tools/adb -s "${PHONE_ADB:?set PHONE_ADB=<phone-ip>:<adb-port>}" "$@"; }
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT"; }
conn() { curl -s -m 10 http://127.0.0.1:18081/api/connection; }
show() { conn | python3 -c '
import json,sys
d=json.load(sys.stdin); x=d.get("details") or {}
keys=["state","backend","error","firmware","machine_type","halted","lines_sent_total","last_rx"]
print(json.dumps({k:d.get(k) for k in keys}, indent=1))
print(json.dumps({k:x.get(k) for k in ["usb_device","chip_version","open_ms","settle_ms","stale_bytes_drained","stale_text","boot_markers_on_open","reset_detected_on_open","dtr_rts_values_sent","dtr_ever_asserted","lines_written","safety_lock","usb_rx_bytes","usb_failure"]}, indent=1))' | tee -a "$OUT"; }
: > "$OUT"
log "install $PKG (full Printer Bridge app, arm64, backend=real)"
P install -r -g app/build/outputs/apk/debug/app-debug.apk 2>&1 | tee -a "$OUT"
P logcat -c
P forward tcp:18081 tcp:8080 >/dev/null
log "session 1: start service (idle_temp_poll=false, real_settle_ms=4000, backend=real); auto-connect sends M115 only"
P shell am start-foreground-service -n $SVC --es backend real --ez idle_temp_poll false --el real_settle_ms 4000 2>&1 | tee -a "$OUT"
sleep 9
show
log "config as seen by the app:"; curl -s http://127.0.0.1:18081/api/config | tee -a "$OUT"; echo | tee -a "$OUT"
log "disconnect (DTR untouched)"
P shell am start-foreground-service -n $SVC --ez disconnect true >/dev/null; sleep 3
conn | tee -a "$OUT"; echo | tee -a "$OUT"
log "session 2: reconnect; a reset caused by session 1's close would show as a boot banner in the 4 s listen window"
P shell am start-foreground-service -n $SVC --ez connect true >/dev/null; sleep 9
show
log "disconnect and stop service"
P shell am start-foreground-service -n $SVC --ez disconnect true >/dev/null; sleep 3
P shell am start-foreground-service -n $SVC --ez stop true >/dev/null; sleep 2
P shell "dumpsys activity services $PKG | grep -c ServiceRecord" | sed 's/^/service records still running: /' | tee -a "$OUT"
P forward --remove tcp:18081
P logcat -d -v threadtime | grep -E "PrinterBridge|python\.std|AndroidRuntime" > evidence/phone_smoke_logcat.txt
log "logcat lines: $(wc -l < evidence/phone_smoke_logcat.txt)"
