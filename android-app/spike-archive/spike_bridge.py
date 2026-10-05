"""Glue between Kotlin and the real, unmodified ch340_serial.py driver.

The real app needs the Python driver to do its USB I/O through Android's USB
Host API (UsbDeviceConnection), which only exists on the Kotlin/Java side. So
the driver's "backend" object (ctrl_out / ctrl_in / bulk_read / bulk_write /
is_pipe_error / close / name / max_packet) is implemented here by delegating
every call to a Kotlin object that implements the `UsbIo` interface.

Nothing in ch340_serial.py is changed: the only hook used is overriding
CH340Serial._open_backend in a subclass so it returns this backend instead of
PyUSBBackend / UsbfsBackend.
"""

import sys
import threading

import ch340_serial as drv


# --- 1. Trivial reverse-direction tests -------------------------------------

def python_info():
    import platform
    return "Python %s on %s (%s)" % (platform.python_version(), sys.platform,
                                      platform.machine())


def call_listener(listener, n):
    """Call listener.onEvent(msg, i) n times from the calling thread and return
    the sum of what Kotlin returned."""
    total = 0
    for i in range(n):
        total += listener.onEvent("py-event-%d" % i, i)
    return total


def call_listener_from_thread(listener, n):
    """Same, but from a Python-created background thread (like the driver's
    ch340-reader thread). Returns the sum."""
    out = {}

    def run():
        try:
            out["total"] = call_listener(listener, n)
        except BaseException as e:  # surface it to the caller
            out["error"] = e

    t = threading.Thread(target=run, name="py-worker")
    t.start()
    t.join(10)
    if t.is_alive():
        raise RuntimeError("worker thread did not finish")
    if "error" in out:
        raise out["error"]
    return out["total"]


def binary_roundtrip(probe):
    """Send all 256 byte values Python->Kotlin as byte[], get a byte[] back and
    convert it to Python bytes. Checks signed Java byte handling both ways."""
    data = bytes(range(256))
    back = bytes(probe.reverse(data))
    if back != data[::-1]:
        raise AssertionError("binary round trip mismatch: %r" % back[:16])
    return len(back)


def catch_kotlin_exception(listener):
    """Kotlin throws inside a callback; Python must see it as a catchable
    exception carrying the Kotlin message."""
    from java import jclass
    ISE = jclass("java.lang.IllegalStateException")
    try:
        listener.boom("thrown-from-kotlin")
    except ISE as e:
        return "caught %s: %s" % (type(e).__name__, e.getMessage())
    return "NOT CAUGHT"


def raise_python_error():
    raise drv.CH340Error("raised-from-python")


def probe_pyusb():
    """What happens if the driver's pyusb backend is requested on Android?
    (pyusb is imported lazily inside PyUSBBackend.__init__.)"""
    try:
        drv.PyUSBBackend()
    except Exception as e:
        return "%s: %s" % (type(e).__name__, e)
    return "pyusb backend unexpectedly constructed"


# --- 2. The real driver running on top of Kotlin USB I/O --------------------

class KotlinUsbBackend:
    """ch340_serial backend that forwards every USB transfer to Kotlin."""
    name = "kotlin"

    def __init__(self, io, debug=False):
        self.io = io
        self.debug = debug
        self.max_packet = int(io.maxPacketSize())

    def ctrl_out(self, req, value, index):
        n = self.io.controlOut(req, value & 0xFFFF, index & 0xFFFF,
                               drv.CTRL_TIMEOUT_MS)
        if n < 0:
            raise drv.CH340Error("controlOut req=0x%02x failed (%d)" % (req, n))

    def ctrl_in(self, req, value, index, length):
        data = self.io.controlIn(req, value & 0xFFFF, index & 0xFFFF, length,
                                 drv.CTRL_TIMEOUT_MS)
        if data is None:
            raise drv.CH340Error("controlIn req=0x%02x failed" % req)
        return bytes(data)

    def is_pipe_error(self, e):
        # Android's UsbDeviceConnection.controlTransfer only returns -1 on
        # failure, so a STALL can't be told apart from other errors.
        return False

    def bulk_read(self, size, timeout_ms):
        data = self.io.bulkRead(size, timeout_ms)
        return bytes(data) if data is not None else b""

    def bulk_write(self, data, timeout_ms):
        n = self.io.bulkWrite(bytes(data), timeout_ms)
        if n < 0:
            raise drv.CH340Timeout("bulkWrite failed (%d)" % n)
        return n

    def close(self):
        self.io.close()


def open_via_kotlin(io, baudrate=115200, timeout=2.0, debug=True):
    class KotlinCH340Serial(drv.CH340Serial):
        def _open_backend(self, backend, device_path, fd):
            return KotlinUsbBackend(io, debug=self.debug)

    return KotlinCH340Serial(baudrate=baudrate, timeout=timeout, debug=debug,
                             reset_on_open=True)


def driver_roundtrip(io, command="M115"):
    """Open the real CH340Serial on the Kotlin backend (runs the real chip init
    sequence: version read, quirk detect, serial init, divisor+LCR, DTR pulse),
    write a G-code line, read the reply line via the driver's background reader
    thread, close. Returns the reply as str."""
    ser = open_via_kotlin(io)
    try:
        ser.reset_input_buffer()
        ser.write((command + "\n").encode("ascii"))
        reply = ser.readline()
        return "version=0x%02x backend=%s reply=%r" % (ser.version, ser.backend_name,
                                                        reply)
    finally:
        ser.close()


# --- 3. Phase 2: the real driver on a real CH340 via Android's USB Host API --
#
# Same KotlinUsbBackend as above; the Kotlin `io` object is now
# AndroidUsbIo (UsbDeviceConnection) instead of FakeCh340. Only read-type
# G-code (M115, M105) is ever sent from here.

import json
import time

# Lines Marlin 1.1.x prints from setup() after a reset. Idle Marlin sends
# nothing unsolicited, so any of these arriving before we send a command
# means the board rebooted.
BOOT_MARKERS = (b"start", b"External Reset", b"Power-Up", b"Brown out",
                b"Watchdog Reset", b"Software Reset", b"Compiled:",
                b"Free Memory")

# How the DTR/RTS lines are handled on open:
#   driver_reset_on_open : CH340Serial(reset_on_open=True) exactly as the
#                          Termux scripts and phase 1 use it: init sets lines
#                          off, then off -> 100ms -> DTR+RTS on (the reset pulse)
#   driver_no_reset      : CH340Serial(reset_on_open=False) as-is: init sets
#                          lines off, then immediately DTR+RTS on
#   lines_never_asserted : same init, but DTR/RTS are never asserted (every
#                          modem-control write sends "all off")
MODES = ("driver_reset_on_open", "driver_no_reset", "lines_never_asserted")


def _open_mode(io, mode, debug=True):
    if mode not in MODES:
        raise ValueError(mode)

    class KotlinCH340Serial(drv.CH340Serial):
        def _open_backend(self, backend, device_path, fd):
            return KotlinUsbBackend(io, debug=self.debug)

        def _set_handshake(self, mcr):
            if mode == "lines_never_asserted":
                mcr = 0
            super()._set_handshake(mcr)

    return KotlinCH340Serial(baudrate=115200, timeout=0.5, debug=debug,
                             reset_on_open=(mode == "driver_reset_on_open"))


def _read_reply(ser, timeout_s):
    """Collect lines until one starting with b'ok' or timeout."""
    lines = []
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        line = ser.readline()
        if not line:
            continue
        lines.append(line)
        if line.startswith(b"ok"):
            break
    return lines


def hw_session(io, mode, listen_s=4.0, command="M115", reply_timeout_s=8.0,
               extra_m105=0, debug=True):
    """Open the unmodified driver on the real chip in `mode`, listen
    `listen_s` seconds for unsolicited output (boot banner = board reset),
    send `command`, read its reply, optionally time `extra_m105` M105
    round trips, close. Returns a JSON string."""
    res = {"mode": mode}
    t0 = time.monotonic()
    ser = _open_mode(io, mode, debug=debug)
    res["open_s"] = round(time.monotonic() - t0, 3)
    res["chip_version"] = "0x%02x" % ser.version
    res["quirk_limited_prescaler"] = ser.quirks_limited_prescaler
    try:
        time.sleep(listen_s)
        n = ser.in_waiting
        unsolicited = ser.read(n) if n else b""
        res["unsolicited"] = unsolicited.decode("latin-1")
        res["boot_markers"] = [m.decode() for m in BOOT_MARKERS if m in unsolicited]
        res["reset_detected"] = bool(res["boot_markers"])

        ser.reset_input_buffer()
        t1 = time.monotonic()
        ser.write((command + "\n").encode("ascii"))
        lines = _read_reply(ser, reply_timeout_s)
        res["reply_s"] = round(time.monotonic() - t1, 3)
        res["reply"] = [l.decode("latin-1") for l in lines]
        res["reply_ok"] = bool(lines) and lines[-1].startswith(b"ok")
        res["is_marlin"] = any(b"FIRMWARE_NAME:Marlin" in l for l in lines)

        if extra_m105:
            lat, oks, sample = [], 0, None
            for _ in range(extra_m105):
                t = time.monotonic()
                ser.write(b"M105\n")
                ls = _read_reply(ser, 3.0)
                lat.append(time.monotonic() - t)
                if ls and ls[-1].startswith(b"ok"):
                    oks += 1
                    sample = sample or [l.decode("latin-1") for l in ls]
            res["m105"] = {"n": extra_m105, "ok": oks,
                           "avg_ms": round(1000 * sum(lat) / len(lat), 1),
                           "max_ms": round(1000 * max(lat), 1),
                           "sample": sample}
        res["leftover"] = ser.read(ser.in_waiting).decode("latin-1") if ser.in_waiting else ""
    finally:
        ser.close()
    return json.dumps(res)


# --- 4. Phase 2, run 2: which line edges reset the board; RX integrity -------

import re

def _drain(ser, seconds):
    time.sleep(seconds)
    n = ser.in_waiting
    return ser.read(n) if n else b""


def hw_edges(io, listen_s=5.0):
    """One session: init with DTR/RTS held off, then change one line at a time
    and listen `listen_s` after each change for a boot banner. Ends with DTR+RTS
    on, then close() drops both (the driver's normal close) - whether THAT
    resets is seen by the next session's first listen."""
    class S(drv.CH340Serial):
        force_low = True
        def _open_backend(self, backend, device_path, fd):
            return KotlinUsbBackend(io, debug=self.debug)
        def _set_handshake(self, mcr):
            super()._set_handshake(0 if self.force_low else mcr)

    ser = S(baudrate=115200, timeout=0.5, debug=True, reset_on_open=False)
    ser.force_low = False
    steps = [("open, lines off", None),
             ("RTS on", lambda: setattr(ser, "rts", True)),
             ("RTS off", lambda: setattr(ser, "rts", False)),
             ("DTR on", lambda: setattr(ser, "dtr", True)),
             ("DTR off", lambda: setattr(ser, "dtr", False)),
             ("DTR+RTS on", lambda: (setattr(ser, "dtr", True), setattr(ser, "rts", True)))]
    out = []
    try:
        for name, act in steps:
            if act:
                act()
            got = _drain(ser, listen_s)
            out.append({"step": name,
                        "reset": any(m in got for m in BOOT_MARKERS),
                        "markers": [m.decode() for m in BOOT_MARKERS if m in got],
                        "bytes": len(got), "text": got.decode("latin-1")[:120]})
        ser.write(b"M115\n")
        lines = _read_reply(ser, 5.0)
        ok = bool(lines) and lines[-1].startswith(b"ok")
    finally:
        ser.close()   # drops DTR+RTS
    return json.dumps({"steps": out, "m115_after_ok": ok})


M115_FIRST = re.compile(rb"^FIRMWARE_NAME:Marlin Creality 3D SOURCE_CODE_URL:\S+ "
                        rb"PROTOCOL_VERSION:1\.0 MACHINE_TYPE:Ender-3 EXTRUDER_COUNT:1 "
                        rb"UUID:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\n$")
CAP = re.compile(rb"^Cap:[A-Z_]+:[01]\n$")
# Run 2 used ^(echo:...|ok)$ and wrongly rejected real output: Marlin 1.1.6's
# M503 prints a blank line and "  M145 S1 ..." lines without "echo:". Line
# shape is now just "printable ASCII"; dropped bytes are caught by requiring
# all repeated dumps to be byte-identical (and matching the reference length).
M503_LINE = re.compile(rb"^[ -~]*\n$")


def _m115_valid(lines):
    return (len(lines) >= 3 and bool(M115_FIRST.match(lines[0]))
            and all(CAP.match(l) for l in lines[1:-1]) and lines[-1] == b"ok\n")


def hw_integrity(io, n_m115=10, n_m503=3, n_m105=100, debug=False):
    """Lines-never-asserted session (no reset from us). Checks received data is
    intact: n x M115 (each line must match the expected shape and all replies
    must be identical), n x M503 (settings dump, ~1 KB, every line 'echo:...'
    printable, all identical), then n x M105 round-trip latency."""
    t0 = time.monotonic()
    ser = _open_mode(io, "lines_never_asserted", debug=debug)
    res = {"open_s": round(time.monotonic() - t0, 3)}
    try:
        pre = _drain(ser, 3.0)
        res["unsolicited_bytes"] = len(pre)
        res["unsolicited_reset"] = any(m in pre for m in BOOT_MARKERS)
        replies = []
        for _ in range(n_m115):
            ser.write(b"M115\n")
            replies.append(tuple(_read_reply(ser, 5.0)))
        good = [r for r in replies if _m115_valid(list(r))]
        res["m115"] = {"n": n_m115, "valid": len(good),
                       "distinct_replies": len(set(replies)),
                       "bytes": sum(map(len, replies[0])) if replies else 0,
                       "first_bad": next(([l.decode("latin-1") for l in r] for r in replies
                                          if not _m115_valid(list(r))), None),
                       "sample": [l.decode("latin-1") for l in replies[0]] if replies else None}
        dumps = []
        for _ in range(n_m503):
            ser.write(b"M503\n")
            dumps.append(tuple(_read_reply(ser, 5.0)))
        okd = [d for d in dumps if d and d[-1] == b"ok\n" and all(M503_LINE.match(l) for l in d)]
        res["m503"] = {"n": n_m503, "valid": len(okd), "distinct": len(set(dumps)),
                       "bytes": [sum(map(len, d)) for d in dumps],
                       "lines": [len(d) for d in dumps],
                       "sample": [l.decode("latin-1") for l in dumps[0]] if dumps else None}
        lat, oks = [], 0
        for _ in range(n_m105):
            t = time.monotonic()
            ser.write(b"M105\n")
            ls = _read_reply(ser, 3.0)
            lat.append(time.monotonic() - t)
            oks += bool(ls) and ls[-1].startswith(b"ok")
        lat.sort()
        res["m105"] = {"n": n_m105, "ok": oks,
                       "avg_ms": round(1000 * sum(lat) / len(lat), 1),
                       "p50_ms": round(1000 * lat[len(lat) // 2], 1),
                       "max_ms": round(1000 * lat[-1], 1)}
    finally:
        ser.close()
    return json.dumps(res)
