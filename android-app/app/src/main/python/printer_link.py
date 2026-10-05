"""Kotlin <-> ch340_serial.py glue for RealPrinterConnection.

ch340_serial.py is used UNCHANGED. Two hooks, both by subclassing:

1. `_open_backend` returns KotlinUsbBackend, which forwards every USB transfer
   to a Kotlin `UsbIo` (AndroidUsbIo on the phone: queued UsbRequest reader).
2. `_set_handshake` is DTR-safe: every modem-control write sends "all lines
   off" (DTR and RTS deasserted), for the whole life of the connection, on
   open AND on close. On the Ender 3 board any DTR edge (off->on or on->off)
   reboots Marlin; USB open/claim and the rest of chip init never do (phase 2).
   So: CH340Serial(reset_on_open=False) inits with lines off, then its
   "raise DTR+RTS" step is turned into "lines off" (no edge), and close()'s
   "drop DTR+RTS" is also "lines off" (no edge). This is the spike's
   `lines_never_asserted` mode, which was reset-free in ~12 real sessions.

The only way to pulse DTR is DtrSafeCH340Serial.deliberate_reset(), which
nothing calls unless a board reboot is explicitly requested.
"""

import time

import ch340_serial as drv

# Lines Marlin 1.1.x prints from setup() after a reset. Idle Marlin sends
# nothing unsolicited, so any of these arriving right after open means the
# board rebooted (or had just been powered up).
BOOT_MARKERS = (b"start", b"External Reset", b"Power-Up", b"Brown out",
                b"Watchdog Reset", b"Software Reset", b"Compiled:",
                b"Free Memory")


class KotlinUsbBackend:
    """ch340_serial backend that forwards every USB transfer to Kotlin."""
    name = "kotlin"

    def __init__(self, io):
        self.io = io
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
        # Android's controlTransfer only returns -1: a STALL can't be told
        # apart from other errors (irrelevant at 115200, see SUMMARY.md).
        return False

    def bulk_read(self, size, timeout_ms):
        # Kotlin's AndroidUsbIo.bulkRead just drains its UsbRequest queue; it
        # raises java.io.IOException once the device is gone, which makes the
        # driver's reader thread stop and later reads raise CH340Error.
        data = self.io.bulkRead(size, timeout_ms)
        return bytes(data) if data is not None else b""

    def bulk_write(self, data, timeout_ms):
        n = self.io.bulkWrite(bytes(data), timeout_ms)
        if n < 0:
            raise drv.CH340Timeout("bulkWrite failed (%d)" % n)
        return n

    def close(self):
        self.io.close()


class DtrSafeCH340Serial(drv.CH340Serial):
    """CH340Serial that never asserts DTR/RTS (see module docstring)."""

    def __init__(self, io, **kw):
        self._io = io
        self._reset_allowed = False
        self.handshake_log = []   # every MCR value actually sent to the chip
        kw["reset_on_open"] = False
        super().__init__(**kw)

    def _open_backend(self, backend, device_path, fd):
        return KotlinUsbBackend(self._io)

    def _set_handshake(self, mcr):
        if not self._reset_allowed:
            mcr = 0
        self.handshake_log.append(mcr & 0xFF)
        super()._set_handshake(mcr)

    def deliberate_reset(self):
        """Explicit board reboot: DTR off->on (reset edge), then back off so
        that the line is deasserted again and a later close() is edge-free.
        NOTE: the on->off edge was also seen to reboot the board in phase 2,
        and this sequence has NOT been run on real hardware yet."""
        self._reset_allowed = True
        try:
            self._set_handshake(drv.BIT_DTR)
            time.sleep(0.1)
            self._set_handshake(0)
        finally:
            self._reset_allowed = False


class Link:
    """Line-oriented wrapper used by RealPrinterConnection.

    open: driver init with lines off, then drain/ignore input for `settle_s`
    (after the printer is (re)powered, the CH340 FIFO can hold garbage from the
    power-on banner at the wrong baud rate). Boot markers in that drained data
    mean the board had just reset/powered up.
    """

    def __init__(self, io, settle_s=0.75, debug=False):
        t0 = time.monotonic()
        self.ser = DtrSafeCH340Serial(io, baudrate=115200, timeout=0.1,
                                      debug=debug)
        self.open_s = time.monotonic() - t0
        self._pending = bytearray()
        stale = bytearray()
        end = time.monotonic() + settle_s
        while time.monotonic() < end:
            time.sleep(0.05)
            n = self.ser.in_waiting
            if n:
                stale += self.ser.read(n)
        self.stale = bytes(stale)
        self.reset_detected = any(m in self.stale for m in BOOT_MARKERS)

    # -- info for Kotlin --
    def chip_version(self):
        return int(self.ser.version)

    def open_ms(self):
        return int(self.open_s * 1000)

    def stale_text(self):
        return self.stale.decode("latin-1")

    def boot_markers(self):
        return ",".join(m.decode() for m in BOOT_MARKERS if m in self.stale)

    def handshake_values(self):
        return ",".join("0x%02x" % v for v in self.ser.handshake_log)

    def dtr_ever_asserted(self):
        return any(v & drv.BIT_DTR for v in self.ser.handshake_log)

    # -- I/O --
    def write_line(self, s):
        self.ser.write((s + "\n").encode("ascii", "replace"))

    def read_line(self, timeout_ms):
        """Return one complete line (without newline) or None on timeout.
        Partial lines are kept until their newline arrives. Raises
        ch340_serial.CH340Error if the USB reader died (device gone)."""
        deadline = time.monotonic() + timeout_ms / 1000.0
        while True:
            nl = self._pending.find(b"\n")
            if nl >= 0:
                line = bytes(self._pending[:nl + 1])
                del self._pending[:nl + 1]
                return line.decode("latin-1").rstrip("\r\n")
            left = deadline - time.monotonic()
            if left <= 0:
                return None
            self.ser.timeout = left
            chunk = self.ser.readline()
            if chunk:
                self._pending += chunk

    def deliberate_reset(self):
        self.ser.deliberate_reset()
        self._pending.clear()

    def close(self):
        self.ser.close()


def open_link(io, settle_s=0.75, debug=False):
    return Link(io, settle_s=settle_s, debug=debug)
