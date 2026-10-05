#!/usr/bin/env bash
# Printer Bridge: build / install / control on the real phone over wireless ADB.
# (Successor of spike-archive/phase2_deploy.sh.)
#
#   ./deploy_phone.sh connect <ip:port>     # wireless debugging port changes when re-enabled;
#                                           # read it with: ssh ... 'su -c getprop service.adb.tls.port'
#   ./deploy_phone.sh build [appId]         # arm64-v8a APK, default backend "real"
#   ./deploy_phone.sh install               # install -r -g the arm64 APK
#   ./deploy_phone.sh start [extra am args] # start the foreground service
#   ./deploy_phone.sh stop                  # disconnect (DTR untouched) then stop the service
#   ./deploy_phone.sh forward               # laptop 127.0.0.1:18081 -> phone :8080 (dashboard/API/MCP)
#   ./deploy_phone.sh logcat                # dump app logcat to evidence/phone_logcat.txt
#
# APP_ID: defaults to com.javcabr.printerbridge. Set it to an id that already has the
# USB "use by default" grant (e.g. an earlier install) to skip one permission dialog.
set -euo pipefail
ADB=~/Android/Sdk/platform-tools/adb
cd "$(dirname "$0")"
APP_ID=${APP_ID:-com.javcabr.printerbridge}
SVC="$APP_ID/com.javcabr.printerbridge.service.PrinterService"
DEV=${PHONE:-$($ADB devices | awk '/:[0-9]+\tdevice/{print $1; exit}')}
A() { $ADB -s "$DEV" "$@"; }
case "${1:-}" in
  connect) $ADB connect "$2"; $ADB devices -l ;;
  build)
    export JAVA_HOME=~/.local/opt/jdk-21.0.12.1+1 PATH=~/.local/opt/jdk-21.0.12.1+1/bin:$PATH
    ./gradlew -Pabi=arm64-v8a -Pbackend=real ${2:+-PappId=$2} assembleDebug ;;
  install) A install -r -g app/build/outputs/apk/debug/app-debug.apk ;;
  start) shift; A shell am start-foreground-service -n "$SVC" "$@" ;;
  stop)
    A shell am start-foreground-service -n "$SVC" --ez disconnect true; sleep 2
    A shell am start-foreground-service -n "$SVC" --ez stop true ;;
  forward) A forward tcp:18081 tcp:8080; echo "http://127.0.0.1:18081/" ;;
  logcat) mkdir -p evidence; A logcat -d -v threadtime | grep -E "PrinterBridge|python\.std|AndroidRuntime|UsbHostManager" > evidence/phone_logcat.txt; wc -l evidence/phone_logcat.txt ;;
  *) sed -n 2,17p "$0"; exit 1 ;;
esac
