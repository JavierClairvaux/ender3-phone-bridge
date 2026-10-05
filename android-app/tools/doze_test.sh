#!/usr/bin/env bash
# Screen-off + forced Doze + app-standby test of the foreground service, against
# the FAKE backend on the emulator. Starts a multi-minute simulated print, backgrounds
# the app, turns the screen off, unplugs the (virtual) battery, forces deep Doze and
# app inactivity, then samples progress from the LAPTOP through the emulator's
# `emu redir` port (goes through the emulated NIC, not adb's loopback forward) until
# the print completes. Usage: doze_test.sh <api token> <gcode file on device> <log out>
set -u
TOK=$1; FILE=$2; OUT=$3
PKG=com.javcabr.printerbridge
B=http://127.0.0.1:28080
A() { ~/Android/Sdk/platform-tools/adb -s emulator-5554 "$@"; }
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT"; }
cmd() { log "\$ adb shell $*"; A shell "$*" 2>&1 | tr -d '\r' | sed 's/^/    /' | tee -a "$OUT"; }
status() { curl -s -m 10 $B/api/status; }
summ() { python3 -c '
import json,sys
s=json.load(sys.stdin); j=s.get("job") or {}; v=s["service"]
print("job=%s %s/%s (%s%%) elapsed=%ss | app sees: device_idle_mode=%s interactive=%s wake_lock=%s | hotend=%s" % (
 j.get("state"), j.get("lines_done"), j.get("lines_total"), j.get("progress_pct"), j.get("elapsed_s"),
 v["device_idle_mode"], v["interactive"], v["wake_lock_held"], s["temps"]["hotend"]))'; }
: > "$OUT"
log "=== doze test: $FILE ==="
curl -s -X POST -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{"line_delay_ms":15,"time_scale":5}' $B/api/sim/config >/dev/null
cmd am start -n $PKG/.ui.MainActivity
sleep 2
log "starting print via REST"
curl -s -X POST -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d "{\"file\":\"$FILE\"}" $B/api/print | tee -a "$OUT"; echo | tee -a "$OUT"
sleep 15
log "--- backgrounding app, screen off, unplug, force deep idle, app inactive ---"
cmd input keyevent KEYCODE_HOME
cmd input keyevent KEYCODE_SLEEP
sleep 2
cmd dumpsys battery unplug
cmd dumpsys deviceidle enable all
cmd dumpsys deviceidle force-idle deep
cmd am set-inactive $PKG true
cmd am get-inactive $PKG
cmd dumpsys deviceidle get deep
cmd "dumpsys power | grep -E 'mWakefulness=|Display Power: state'"
cmd "dumpsys activity services $PKG | grep -E 'isForeground'"
log "--- sampling every 20 s from the laptop via emu redir (non-loopback) ---"
while true; do
  S=$(status)
  if [ -z "$S" ]; then log "STATUS UNREACHABLE"; else log "$(echo "$S" | summ)   [deviceidle deep: $(A shell dumpsys deviceidle get deep | tr -d '\r')]"; fi
  st=$(echo "$S" | python3 -c 'import json,sys; print((json.load(sys.stdin).get("job") or {}).get("state"))' 2>/dev/null)
  case "$st" in done|error|cancelled) break;; esac
  sleep 20
done
log "--- job finished; state before undoing doze ---"
cmd dumpsys deviceidle get deep
cmd "dumpsys power | grep -E 'mWakefulness='"
cmd "dumpsys activity services $PKG | grep -E 'isForeground'"
log "--- restoring ---"
cmd dumpsys deviceidle unforce
cmd dumpsys deviceidle disable all
cmd dumpsys battery reset
cmd am set-inactive $PKG false
cmd input keyevent KEYCODE_WAKEUP
log "=== end ==="
