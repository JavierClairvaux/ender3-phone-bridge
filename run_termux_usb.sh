#!/data/data/com.termux/files/usr/bin/bash
# FALLBACK path (no root needed): get a USB fd from Android via the
# Termux:API `termux-usb` broker, then run one of our scripts with it.
# Requires: the Termux:API app installed + `pkg install termux-api`.
#
# Usage: ./run_termux_usb.sh test_m115.py [script args...]
#   env USB_DEV=/dev/bus/usb/001/002 to pick a device explicitly.
#
# termux-usb -E exports the fd as TERMUX_USB_FD; ch340_serial.py picks that
# up automatically and uses its raw usbfs backend on it.
set -e
here="$(cd "$(dirname "$0")" && pwd)"
[ $# -ge 1 ] || { echo "usage: $0 script.py [args...]"; exit 1; }

dev="${USB_DEV:-}"
if [ -z "$dev" ]; then
    # termux-usb -l prints a JSON list like ["/dev/bus/usb/001/002"]
    dev="$(termux-usb -l | tr -d '[]", ' | grep -m1 '^/dev/bus/usb/' || true)"
fi
[ -n "$dev" ] || { echo "no USB device reported by termux-usb -l"; exit 1; }
echo "Using $dev"

# termux-usb -e takes a single executable, so bake the args into a wrapper.
wrapper="$(mktemp "${TMPDIR:-/data/data/com.termux/files/usr/tmp}/ch340run.XXXXXX")"
{
    echo '#!/data/data/com.termux/files/usr/bin/bash'
    printf 'cd %q\nexec python' "$here"
    for a in "$@"; do printf ' %q' "$a"; done
    echo
} > "$wrapper"
chmod 700 "$wrapper"
set +e
termux-usb -r -E -e "$wrapper" "$dev"
rc=$?
rm -f "$wrapper"
exit $rc
