#!/usr/bin/env bash
# Phase 2 helper: install the arm64 APK on the phone over wireless ADB and
# launch the hardware-check screen. Usage:
#   ./phase2_deploy.sh pair <ip:pairport> <code>
#   ./phase2_deploy.sh connect <ip:port>
#   ./phase2_deploy.sh install          # installs, clears logcat, launches HwActivity with run=true
#   ./phase2_deploy.sh evidence         # dumps logcat + screenshot into evidence/
set -euo pipefail
ADB=~/Android/Sdk/platform-tools/adb
cd "$(dirname "$0")"
case "$1" in
  pair)    $ADB pair "$2" "$3" ;;
  connect) $ADB connect "$2"; $ADB devices -l ;;
  install)
    $ADB install -r app/build/outputs/apk/debug/app-debug.apk
    $ADB logcat -c
    $ADB shell am start -n com.example.chaquopyspike/.HwActivity --ez run true ;;
  evidence)
    mkdir -p evidence
    $ADB logcat -d -v threadtime > evidence/phase2_logcat_full.txt
    grep -E "ChaquopySpike|python\.std|UsbHostManager|UsbUserPermissionManager|UsbPermission|AndroidRuntime" \
      evidence/phase2_logcat_full.txt > evidence/phase2_logcat.txt || true
    $ADB exec-out screencap -p > evidence/phase2_hwactivity_screenshot.png
    ls -la evidence ;;
esac
