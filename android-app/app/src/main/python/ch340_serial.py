"""
ch340_serial.py - userspace driver for the WCH CH340/CH341 USB-to-serial chip.

Built for a rooted Android phone (Termux) whose kernel has no ch341 driver,
so there is no /dev/ttyUSB0. We talk to the chip directly over raw USB and
expose a small pyserial-like object (CH340Serial) that the existing Marlin
tooling can use in place of serial.Serial.

STATUS: CONFIRMED WORKING ON REAL HARDWARE (rooted Redmi Note 9, LineageOS,
Termux, "pyusb" backend) against a Creality Ender 3 (Marlin 1.1.6.2).
Verified: test_usb_probe.py read chip version 0x31; test_m115.py's DTR
pulse rebooted the board and M115 returned the real firmware banner
(FIRMWARE_NAME:Marlin Creality 3D, MACHINE_TYPE:Ender-3, ok); test_status.py
polled live hotend/bed temps; home_printer_phone.py physically homed all
three axes; print_gcode_phone.py streamed and completed a full ~3900-line
sliced print job end to end.

  HIGH confidence (copied from mainline Linux drivers/usb/serial/ch341.c),
  and now hardware-confirmed:
    - vendor request codes: 0x5F read version, 0xA1 serial init,
      0x9A write register pair, 0x95 read register pair, 0xA4 modem control
    - bmRequestType 0x40 (vendor|device|OUT) / 0xC0 (vendor|device|IN)
    - init order: read version -> 0xA1(0,0) -> baud regs -> LCR -> handshake
    - baud divisor math (ch341_get_divisor); 115200 -> 0xCC03, plus bit 7
      (0x0080) on chips with version > 0x27 -> 0xCC83 written to reg pair
      0x1312 (DIVISOR<<8 | PRESCALER)
    - LCR 0xC3 (RX enable | TX enable | CS8, i.e. 8N1) to reg pair 0x2518,
      only on chip version >= 0x30
    - DTR = bit 5, RTS = bit 6, sent INVERTED as wValue of request 0xA4

  BEST-EFFORT (plausible, not yet exercised on hardware - "kernel" init
  mode and the "pyusb" backend worked first try, so these remain untested):
    - "legacy" init mode (the pre-2020 kernel / usb-serial-for-android
      sequence with the 0xA1 0x501F/0xD90A magic and the 0x0F2C register)
    - the raw usbfs ioctl backend (struct layouts are the standard 64-bit
      Linux ones, computed with ctypes, but pyusb is what actually ran)

Backends:
  "pyusb" - pyusb + libusb-1.0 (primary; needs root to open /dev/bus/usb/...)
  "usbfs" - no libusb at all: raw ioctls on /dev/bus/usb/BBB/DDD, or on a
            file descriptor handed over by `termux-usb -E` (TERMUX_USB_FD)
  "auto"  - usbfs if an fd was given (or TERMUX_USB_FD is set), else try
            pyusb, and fall back to usbfs if pyusb fails
"""

import ctypes
import ctypes.util
import errno
import fcntl
import glob
import os
import struct
import sys
import threading
import time

VENDOR_ID = 0x1A86
PRODUCT_ID = 0x7523  # CH340. (0x5523 = CH341 in serial mode also works.)

# --- Protocol constants (from Linux drivers/usb/serial/ch341.c) -------------
REQ_READ_VERSION = 0x5F
REQ_WRITE_REG = 0x9A
REQ_READ_REG = 0x95
REQ_SERIAL_INIT = 0xA1
REQ_MODEM_CTRL = 0xA4

REG_BREAK = 0x05
REG_PRESCALER = 0x12
REG_DIVISOR = 0x13
REG_LCR = 0x18
REG_LCR2 = 0x25

LCR_ENABLE_RX = 0x80
LCR_ENABLE_TX = 0x40
LCR_CS8 = 0x03

BIT_RTS = 1 << 6
BIT_DTR = 1 << 5

# Modem status (read via REQ_READ_REG 0x0706; bits are inverted on the wire)
BIT_CTS = 0x01
BIT_DSR = 0x02
BIT_RI = 0x04
BIT_DCD = 0x08

CTRL_OUT = 0x40  # USB_TYPE_VENDOR | USB_RECIP_DEVICE | USB_DIR_OUT
CTRL_IN = 0xC0   # USB_TYPE_VENDOR | USB_RECIP_DEVICE | USB_DIR_IN
CTRL_TIMEOUT_MS = 1000  # ch341.c DEFAULT_TIMEOUT

CLKRATE = 48000000

TERMUX_PREFIX = "/data/data/com.termux/files/usr"


class CH340Error(IOError):
    pass


class CH340Timeout(CH340Error):
    pass


def _log(enabled, *a):
    if enabled:
        print("[ch340]", *a, file=sys.stderr, flush=True)


# --- Baud rate math ----------------------------------------------------------

def _clk_div(ps, fact):
    return 1 << (12 - 3 * ps - fact)


def ch341_get_divisor(speed, limited_prescaler=False):
    """Port of ch341_get_divisor() from mainline Linux ch341.c.

    Returns the 16-bit value written to register pair 0x1312:
    (0x100 - div) << 8 | fact << 2 | ps.  Bit 7 (the "don't buffer until a
    full packet" flag) is added by the caller depending on chip version.
    """
    min_bps = -(-CLKRATE // (_clk_div(0, 0) * 256))  # DIV_ROUND_UP
    max_bps = CLKRATE // (_clk_div(3, 0) * 2)
    speed = max(min_bps, min(max_bps, int(speed)))
    min_rates = [CLKRATE // (_clk_div(ps, 1) * 512) for ps in range(4)]

    fact = 1
    ps = 3
    while ps >= 0:
        if speed > min_rates[ps]:
            break
        ps -= 1
    if ps < 0:
        raise ValueError("unsupported baud rate %d" % speed)

    clk_div = _clk_div(ps, fact)
    div = CLKRATE // (clk_div * speed)
    force_fact0 = ps < 3 and limited_prescaler
    if div < 9 or div > 255 or force_fact0:
        div //= 2
        clk_div *= 2
        fact = 0
    if div < 2:
        raise ValueError("unsupported baud rate %d" % speed)
    # Pick the next divisor if it lands closer to the requested rate.
    if (16 * CLKRATE // (clk_div * div) - 16 * speed
            >= 16 * speed - 16 * CLKRATE // (clk_div * (div + 1))):
        div += 1
    # Prefer the lower base clock for even divisors.
    if fact == 1 and div % 2 == 0:
        div //= 2
        fact = 0
    return ((0x100 - div) << 8) | (fact << 2) | ps


def ch341_legacy_divisor(speed):
    """Pre-2020 Linux ch341.c / usb-serial-for-android computation.

    Returns (value_for_reg_0x1312, value_for_reg_0x0F2C).  For 115200 this is
    (0xCC03, 0x08); the caller ORs 0x80 into the first value.
    """
    factor = 1532620800 // int(speed)
    divisor = 3
    while factor > 0xFFF0 and divisor:
        factor >>= 3
        divisor -= 1
    if factor > 0xFFF0:
        raise ValueError("unsupported baud rate %d" % speed)
    factor = 0x10000 - factor
    return (factor & 0xFF00) | divisor, factor & 0xFF


def actual_baud(divisor_value):
    """Effective baud rate for a 0x1312 register value (for diagnostics)."""
    ps = divisor_value & 0x03
    fact = (divisor_value >> 2) & 0x01
    div = 0x100 - ((divisor_value >> 8) & 0xFF)
    return CLKRATE / (_clk_div(ps, fact) * div)


# --- Device discovery via sysfs (works as root without libusb) --------------

def find_device_nodes(vid=VENDOR_ID, pid=PRODUCT_ID):
    """Return a list of /dev/bus/usb/BBB/DDD paths matching vid:pid."""
    found = []
    for d in sorted(glob.glob("/sys/bus/usb/devices/*")):
        try:
            with open(os.path.join(d, "idVendor")) as f:
                v = int(f.read().strip(), 16)
            with open(os.path.join(d, "idProduct")) as f:
                p = int(f.read().strip(), 16)
            if v != vid or p != pid:
                continue
            with open(os.path.join(d, "busnum")) as f:
                bus = int(f.read().strip())
            with open(os.path.join(d, "devnum")) as f:
                dev = int(f.read().strip())
        except (OSError, ValueError):
            continue
        found.append("/dev/bus/usb/%03d/%03d" % (bus, dev))
    return found


# --- Backend: pyusb / libusb -------------------------------------------------

class PyUSBBackend:
    name = "pyusb"

    def __init__(self, vid=VENDOR_ID, pid=PRODUCT_ID, interface=0, debug=False):
        import usb.core
        import usb.util
        import usb.backend.libusb1 as libusb1
        self._usb = usb
        self.debug = debug
        self.interface = interface

        backend = None
        candidates = [
            os.path.join(os.environ.get("PREFIX", TERMUX_PREFIX), "lib", "libusb-1.0.so"),
            os.path.join(TERMUX_PREFIX, "lib", "libusb-1.0.so"),
        ]
        for lib in candidates:
            if os.path.exists(lib):
                backend = libusb1.get_backend(find_library=lambda _n, lib=lib: lib)
                if backend is not None:
                    _log(debug, "pyusb using", lib)
                    break
        if backend is None:
            backend = libusb1.get_backend()  # default ctypes.util lookup
        if backend is None:
            raise CH340Error("pyusb could not load libusb-1.0 "
                             "(pkg install libusb; check LD_LIBRARY_PATH under su)")

        dev = usb.core.find(idVendor=vid, idProduct=pid, backend=backend)
        if dev is None:
            raise CH340Error("pyusb/libusb found no %04x:%04x device "
                             "(not plugged in, or libusb cannot enumerate on "
                             "this Android build - try --backend usbfs)" % (vid, pid))
        self.dev = dev

        try:
            if dev.is_kernel_driver_active(interface):
                _log(debug, "detaching kernel driver from interface", interface)
                dev.detach_kernel_driver(interface)
        except (NotImplementedError, usb.core.USBError) as e:
            _log(debug, "kernel driver check skipped:", e)

        # Only set the configuration if the device is unconfigured; calling
        # set_configuration() on a configured device can reset endpoints.
        try:
            cfg = dev.get_active_configuration()
        except usb.core.USBError:
            cfg = None
        if cfg is None:
            dev.set_configuration()
            cfg = dev.get_active_configuration()

        usb.util.claim_interface(dev, interface)
        intf = cfg[(interface, 0)]
        self.ep_in = self.ep_out = None
        self.max_packet = 32
        for ep in intf:
            attr = ep.bmAttributes & 0x03
            if attr != 0x02:  # bulk only
                continue
            if ep.bEndpointAddress & 0x80:
                self.ep_in = ep.bEndpointAddress
                self.max_packet = ep.wMaxPacketSize or 32
            else:
                self.ep_out = ep.bEndpointAddress
        if self.ep_in is None or self.ep_out is None:
            raise CH340Error("bulk endpoints not found on interface %d" % interface)
        _log(debug, "endpoints in=0x%02x out=0x%02x maxpkt=%d"
             % (self.ep_in, self.ep_out, self.max_packet))

    def ctrl_out(self, req, value, index):
        self.dev.ctrl_transfer(CTRL_OUT, req, value & 0xFFFF, index & 0xFFFF,
                               None, CTRL_TIMEOUT_MS)

    def ctrl_in(self, req, value, index, length):
        data = self.dev.ctrl_transfer(CTRL_IN, req, value & 0xFFFF, index & 0xFFFF,
                                      length, CTRL_TIMEOUT_MS)
        return bytes(data)

    def _is_timeout(self, e):
        tcls = getattr(self._usb.core, "USBTimeoutError", None)
        if tcls is not None and isinstance(e, tcls):
            return True
        return (getattr(e, "errno", None) in (errno.ETIMEDOUT,)
                or getattr(e, "backend_error_code", None) == -7)

    def is_pipe_error(self, e):
        return (getattr(e, "errno", None) == errno.EPIPE
                or getattr(e, "backend_error_code", None) == -9)

    def bulk_read(self, size, timeout_ms):
        try:
            return bytes(self.dev.read(self.ep_in, size, timeout_ms))
        except self._usb.core.USBError as e:
            if self._is_timeout(e):
                return b""
            raise

    def bulk_write(self, data, timeout_ms):
        try:
            return self.dev.write(self.ep_out, data, timeout_ms)
        except self._usb.core.USBError as e:
            if self._is_timeout(e):
                raise CH340Timeout("bulk write timed out")
            raise

    def close(self):
        try:
            self._usb.util.release_interface(self.dev, self.interface)
        except Exception:
            pass
        try:
            self._usb.util.dispose_resources(self.dev)
        except Exception:
            pass


# --- Backend: raw Linux usbfs ioctls (no libusb) -----------------------------

class _CtrlTransfer(ctypes.Structure):
    # struct usbdevfs_ctrltransfer (include/uapi/linux/usbdevice_fs.h)
    _fields_ = [("bRequestType", ctypes.c_uint8),
                ("bRequest", ctypes.c_uint8),
                ("wValue", ctypes.c_uint16),
                ("wIndex", ctypes.c_uint16),
                ("wLength", ctypes.c_uint16),
                ("timeout", ctypes.c_uint32),
                ("data", ctypes.c_void_p)]


class _BulkTransfer(ctypes.Structure):
    # struct usbdevfs_bulktransfer
    _fields_ = [("ep", ctypes.c_uint),
                ("len", ctypes.c_uint),
                ("timeout", ctypes.c_uint),
                ("data", ctypes.c_void_p)]


class _UsbfsIoctl(ctypes.Structure):
    # struct usbdevfs_ioctl
    _fields_ = [("ifno", ctypes.c_int),
                ("ioctl_code", ctypes.c_int),
                ("data", ctypes.c_void_p)]


def _IOC(d, t, nr, size):
    return (d << 30) | (size << 16) | (ord(t) << 8) | nr


_IOC_NONE, _IOC_WRITE, _IOC_READ = 0, 1, 2
USBDEVFS_CONTROL = _IOC(_IOC_READ | _IOC_WRITE, "U", 0, ctypes.sizeof(_CtrlTransfer))
USBDEVFS_BULK = _IOC(_IOC_READ | _IOC_WRITE, "U", 2, ctypes.sizeof(_BulkTransfer))
USBDEVFS_CLAIMINTERFACE = _IOC(_IOC_READ, "U", 15, ctypes.sizeof(ctypes.c_uint))
USBDEVFS_RELEASEINTERFACE = _IOC(_IOC_READ, "U", 16, ctypes.sizeof(ctypes.c_uint))
USBDEVFS_IOCTL = _IOC(_IOC_READ | _IOC_WRITE, "U", 18, ctypes.sizeof(_UsbfsIoctl))
USBDEVFS_DISCONNECT = _IOC(_IOC_NONE, "U", 22, 0)


class UsbfsBackend:
    """Talks to the chip with ioctls on a usbfs device node or fd.

    Works with either a path opened as root (/dev/bus/usb/001/002) or an fd
    from `termux-usb -E` (Android's UsbDeviceConnection fd is a usbfs fd).
    """
    name = "usbfs"

    def __init__(self, path=None, fd=None, vid=VENDOR_ID, pid=PRODUCT_ID,
                 interface=0, debug=False):
        self.debug = debug
        self.interface = interface
        self._own_fd = False
        if fd is None:
            if path is None:
                nodes = find_device_nodes(vid, pid)
                if not nodes:
                    raise CH340Error("no %04x:%04x device found in /sys/bus/usb/devices"
                                     % (vid, pid))
                path = nodes[0]
            _log(debug, "usbfs opening", path)
            fd = os.open(path, os.O_RDWR)  # EACCES here => need root
            self._own_fd = True
        self.fd = fd
        self.path = path

        self.ep_in, self.ep_out, self.max_packet = self._parse_endpoints()
        _log(debug, "endpoints in=0x%02x out=0x%02x maxpkt=%d"
             % (self.ep_in, self.ep_out, self.max_packet))

        ifno = ctypes.c_uint(interface)
        try:
            fcntl.ioctl(self.fd, USBDEVFS_CLAIMINTERFACE, ifno)
        except OSError as e:
            if e.errno != errno.EBUSY:
                raise
            _log(debug, "interface busy, disconnecting kernel driver")
            req = _UsbfsIoctl(interface, USBDEVFS_DISCONNECT, None)
            fcntl.ioctl(self.fd, USBDEVFS_IOCTL, req)
            fcntl.ioctl(self.fd, USBDEVFS_CLAIMINTERFACE, ifno)
        self._claimed = True

    def _parse_endpoints(self):
        """Read the cached device + config descriptors from the usbfs node."""
        try:
            os.lseek(self.fd, 0, os.SEEK_SET)
            raw = os.read(self.fd, 4096)
        except OSError as e:
            _log(self.debug, "descriptor read failed (%s); assuming CH340 defaults" % e)
            return 0x82, 0x02, 32
        ep_in = ep_out = None
        maxpkt = 32
        cur_if = cur_alt = None
        i = 0
        while i + 2 <= len(raw):
            blen, btype = raw[i], raw[i + 1]
            if blen < 2:
                break
            if btype == 0x04 and blen >= 9:  # interface
                cur_if, cur_alt = raw[i + 2], raw[i + 3]
            elif btype == 0x05 and blen >= 7 and cur_if == self.interface and cur_alt == 0:
                addr, attr = raw[i + 2], raw[i + 3]
                mps = struct.unpack_from("<H", raw, i + 4)[0]
                if attr & 0x03 == 0x02:
                    if addr & 0x80:
                        ep_in, maxpkt = addr, (mps & 0x7FF) or 32
                    else:
                        ep_out = addr
            i += blen
        if ep_in is None or ep_out is None:
            _log(self.debug, "endpoints not found in descriptors; assuming 0x82/0x02")
            return 0x82, 0x02, 32
        return ep_in, ep_out, maxpkt

    def _control(self, rtype, req, value, index, buf, length, timeout_ms):
        ct = _CtrlTransfer(rtype, req, value & 0xFFFF, index & 0xFFFF, length,
                           timeout_ms,
                           ctypes.cast(buf, ctypes.c_void_p) if buf is not None else None)
        return fcntl.ioctl(self.fd, USBDEVFS_CONTROL, ct)

    def ctrl_out(self, req, value, index):
        self._control(CTRL_OUT, req, value, index, None, 0, CTRL_TIMEOUT_MS)

    def ctrl_in(self, req, value, index, length):
        buf = ctypes.create_string_buffer(length)
        n = self._control(CTRL_IN, req, value, index, buf, length, CTRL_TIMEOUT_MS)
        return buf.raw[:n]

    def is_pipe_error(self, e):
        return getattr(e, "errno", None) == errno.EPIPE

    def bulk_read(self, size, timeout_ms):
        buf = ctypes.create_string_buffer(size)
        bt = _BulkTransfer(self.ep_in, size, timeout_ms, ctypes.cast(buf, ctypes.c_void_p))
        try:
            n = fcntl.ioctl(self.fd, USBDEVFS_BULK, bt)
        except OSError as e:
            if e.errno == errno.ETIMEDOUT:
                return b""
            raise
        return buf.raw[:n]

    def bulk_write(self, data, timeout_ms):
        buf = ctypes.create_string_buffer(bytes(data), len(data))
        bt = _BulkTransfer(self.ep_out, len(data), timeout_ms, ctypes.cast(buf, ctypes.c_void_p))
        try:
            return fcntl.ioctl(self.fd, USBDEVFS_BULK, bt)
        except OSError as e:
            if e.errno == errno.ETIMEDOUT:
                raise CH340Timeout("bulk write timed out")
            raise

    def close(self):
        if getattr(self, "_claimed", False):
            try:
                fcntl.ioctl(self.fd, USBDEVFS_RELEASEINTERFACE, ctypes.c_uint(self.interface))
            except OSError:
                pass
            self._claimed = False
        if self._own_fd:
            try:
                os.close(self.fd)
            except OSError:
                pass
            self._own_fd = False


# --- The serial-like object --------------------------------------------------

class CH340Serial:
    """Minimal pyserial.Serial look-alike on top of a raw-USB CH340.

    Supported: write, read, readline, in_waiting, reset_input_buffer, flush,
    close, dtr/rts properties, context manager. `timeout` has pyserial
    semantics: None = block forever, 0 = non-blocking, >0 seconds.
    """

    def __init__(self, baudrate=115200, timeout=None, write_timeout=5.0,
                 backend="auto", device_path=None, fd=None,
                 init_mode="kernel", reset_on_open=True, debug=False):
        self.baudrate = baudrate
        self.timeout = timeout
        self.write_timeout = write_timeout
        self.debug = debug
        self.init_mode = init_mode
        self.version = None
        self.quirks_limited_prescaler = False
        self._mcr = 0
        self._buf = bytearray()
        self._cond = threading.Condition()
        self._stop = threading.Event()
        self._reader_error = None
        self._reader = None
        self.is_open = False

        if fd is None and os.environ.get("TERMUX_USB_FD"):
            fd = int(os.environ["TERMUX_USB_FD"])
            _log(debug, "using TERMUX_USB_FD =", fd)

        self._dev = self._open_backend(backend, device_path, fd)
        try:
            self._init_chip()
            if reset_on_open:
                self._reset_pulse()
            else:
                self._set_handshake(BIT_DTR | BIT_RTS)
            self._start_reader()
        except Exception:
            self._dev.close()
            raise
        self.is_open = True

    # -- open / init --

    def _open_backend(self, backend, device_path, fd):
        if backend == "usbfs" or fd is not None:
            return UsbfsBackend(path=device_path, fd=fd, debug=self.debug)
        if backend == "pyusb":
            return PyUSBBackend(debug=self.debug)
        if backend != "auto":
            raise ValueError("backend must be auto, pyusb or usbfs")
        try:
            return PyUSBBackend(debug=self.debug)
        except Exception as e:  # ImportError, USBError, CH340Error, ...
            print("[ch340] pyusb backend failed (%s: %s); falling back to raw usbfs"
                  % (type(e).__name__, e), file=sys.stderr)
            return UsbfsBackend(path=device_path, debug=self.debug)

    @property
    def backend_name(self):
        return self._dev.name

    def _ctrl_out(self, req, value, index):
        _log(self.debug, "ctrl_out  req=0x%02x value=0x%04x index=0x%04x"
             % (req, value & 0xFFFF, index & 0xFFFF))
        self._dev.ctrl_out(req, value, index)

    def _ctrl_in(self, req, value, index, length=2):
        data = self._dev.ctrl_in(req, value, index, length)
        _log(self.debug, "ctrl_in   req=0x%02x value=0x%04x index=0x%04x -> %s"
             % (req, value, index, data.hex()))
        return data

    def read_version(self):
        data = self._ctrl_in(REQ_READ_VERSION, 0, 0, 2)
        if len(data) < 1:
            raise CH340Error("short version read: %r" % data)
        return data[0]

    def _detect_quirks(self):
        # ch341_detect_quirks: if reading the BREAK register stalls (EPIPE),
        # the chip only supports a limited prescaler. Irrelevant at 115200
        # (prescaler 3 is chosen) but done for correctness at other rates.
        try:
            self._ctrl_in(REQ_READ_REG, REG_BREAK, 0, 2)
        except Exception as e:
            if self._dev.is_pipe_error(e):
                self.quirks_limited_prescaler = True
                _log(self.debug, "quirk: limited prescaler")
            else:
                _log(self.debug, "quirk detection read failed:", e)

    def _init_chip(self):
        self.version = self.read_version()
        _log(self.debug, "chip version 0x%02x" % self.version)
        if self.init_mode == "legacy":
            self._init_legacy()
        else:
            self._detect_quirks()
            # ch341_configure(): serial init, baud+LCR, handshake (lines off)
            self._ctrl_out(REQ_SERIAL_INIT, 0, 0)
            self._set_baudrate_lcr(self.baudrate, LCR_ENABLE_RX | LCR_ENABLE_TX | LCR_CS8)
            self._set_handshake(0)

    def _set_baudrate_lcr(self, baud, lcr):
        val = ch341_get_divisor(baud, self.quirks_limited_prescaler)
        _log(self.debug, "baud %d -> divisor reg 0x%04x (actual %.1f bps)"
             % (baud, val, actual_baud(val)))
        # Without bit 7 the chip holds RX data until a full 32-byte packet,
        # which would stall short replies like "ok\n".
        if self.version > 0x27:
            val |= 0x80
        self._ctrl_out(REQ_WRITE_REG, (REG_DIVISOR << 8) | REG_PRESCALER, val)
        if self.version < 0x30:
            return  # older chips: separate LCR registers, default 8N1
        self._ctrl_out(REQ_WRITE_REG, (REG_LCR2 << 8) | REG_LCR, lcr)

    def _set_baud_legacy(self, baud):
        a, b = ch341_legacy_divisor(baud)
        a |= 0x80
        self._ctrl_out(REQ_WRITE_REG, 0x1312, a)
        self._ctrl_out(REQ_WRITE_REG, 0x0F2C, b)

    def _init_legacy(self):
        # Sequence used by Linux ch341.c before ~v5.5 and by
        # usb-serial-for-android's Ch34xSerialDriver. BEST-EFFORT fallback.
        self._ctrl_out(REQ_SERIAL_INIT, 0, 0)
        self._set_baud_legacy(self.baudrate)
        self._ctrl_in(REQ_READ_REG, 0x2518, 0, 2)
        self._ctrl_out(REQ_WRITE_REG, 0x2518, LCR_ENABLE_RX | LCR_ENABLE_TX | LCR_CS8)
        self._ctrl_in(REQ_READ_REG, 0x0706, 0, 2)
        self._ctrl_out(REQ_SERIAL_INIT, 0x501F, 0xD90A)
        self._set_baud_legacy(self.baudrate)
        self._set_handshake(0)

    # -- modem control lines --

    def _set_handshake(self, mcr):
        self._mcr = mcr & 0xFF
        # ch341_set_handshake sends ~control (active-low on the wire)
        self._ctrl_out(REQ_MODEM_CTRL, (~self._mcr) & 0xFFFF, 0)

    def _reset_pulse(self):
        """Mimic what opening /dev/ttyUSB0 does on Linux: DTR/RTS go from
        deasserted to asserted. On the Ender 3 (Melzi / ATmega1284P) board the
        DTR line is capacitor-coupled to RESET, so that edge reboots Marlin.
        BEST-EFFORT: the request itself is documented, whether this board
        revision resets on it has to be seen on the hardware."""
        self._set_handshake(0)
        time.sleep(0.1)
        self._set_handshake(BIT_DTR | BIT_RTS)

    @property
    def dtr(self):
        return bool(self._mcr & BIT_DTR)

    @dtr.setter
    def dtr(self, on):
        self._set_handshake((self._mcr | BIT_DTR) if on else (self._mcr & ~BIT_DTR))

    @property
    def rts(self):
        return bool(self._mcr & BIT_RTS)

    @rts.setter
    def rts(self, on):
        self._set_handshake((self._mcr | BIT_RTS) if on else (self._mcr & ~BIT_RTS))

    def modem_status(self):
        """Return dict of CTS/DSR/RI/DCD (register 0x0706, inverted bits)."""
        raw = self._ctrl_in(REQ_READ_REG, 0x0706, 0, 2)
        msr = (~raw[0]) & 0x0F
        return {"CTS": bool(msr & BIT_CTS), "DSR": bool(msr & BIT_DSR),
                "RI": bool(msr & BIT_RI), "DCD": bool(msr & BIT_DCD),
                "raw": raw.hex()}

    # -- background reader --

    def _start_reader(self):
        self._reader = threading.Thread(target=self._reader_loop,
                                        name="ch340-reader", daemon=True)
        self._reader.start()

    def _reader_loop(self):
        # Read exactly one max-size packet per transfer. A bulk transfer that
        # ends in a timeout can lose any bytes received before it timed out
        # (usbfs drops them and pyusb raises without returning them), so
        # asking for a single packet means nothing is lost.
        size = self._dev.max_packet
        while not self._stop.is_set():
            try:
                data = self._dev.bulk_read(size, 200)
            except Exception as e:
                if self._stop.is_set():
                    break
                with self._cond:
                    self._reader_error = e
                    self._cond.notify_all()
                break
            if data:
                if self.debug:
                    _log(True, "rx", repr(data))
                with self._cond:
                    self._buf.extend(data)
                    self._cond.notify_all()

    def _check_reader(self):
        if self._reader_error is not None:
            raise CH340Error("USB read failed: %r" % (self._reader_error,))

    # -- pyserial-ish API --

    @property
    def in_waiting(self):
        with self._cond:
            return len(self._buf)

    def reset_input_buffer(self):
        with self._cond:
            self._buf.clear()

    def _wait(self, predicate, timeout):
        """Wait (in short slices, so Ctrl+C stays responsive) until
        predicate() is true or timeout expires. Call with self._cond held."""
        deadline = None if timeout is None else time.monotonic() + timeout
        while not predicate():
            if self._reader_error is not None:
                return
            if deadline is None:
                self._cond.wait(0.5)
            else:
                left = deadline - time.monotonic()
                if left <= 0:
                    return
                self._cond.wait(min(left, 0.5))

    def read(self, size=1):
        if not self.is_open:
            raise CH340Error("port closed")
        with self._cond:
            self._wait(lambda: len(self._buf) >= size, self.timeout)
            if not self._buf:
                self._check_reader()
            out = bytes(self._buf[:size])
            del self._buf[:size]
        return out

    def readline(self, size=-1):
        if not self.is_open:
            raise CH340Error("port closed")

        def ready():
            if b"\n" in self._buf:
                return True
            return size is not None and size >= 0 and len(self._buf) >= size

        with self._cond:
            self._wait(ready, self.timeout)
            idx = self._buf.find(b"\n")
            if idx >= 0:
                n = idx + 1
            else:
                if not self._buf:
                    self._check_reader()
                n = len(self._buf)  # timeout: return partial line like pyserial
            if size is not None and size >= 0:
                n = min(n, size)
            out = bytes(self._buf[:n])
            del self._buf[:n]
        return out

    def write(self, data):
        if not self.is_open:
            raise CH340Error("port closed")
        self._check_reader()
        data = bytes(data)
        tmo = int((self.write_timeout or 5.0) * 1000)
        sent = 0
        while sent < len(data):
            chunk = data[sent:sent + 1024]
            n = self._dev.bulk_write(chunk, tmo)
            if n <= 0:
                raise CH340Timeout("bulk write sent 0 bytes")
            sent += n
        if self.debug:
            _log(True, "tx", repr(data))
        return sent

    def flush(self):
        pass  # bulk writes are synchronous; nothing to flush

    def close(self):
        if not self.is_open:
            return
        self.is_open = False
        self._stop.set()
        if self._reader is not None:
            self._reader.join(timeout=2)
        try:
            # Like HUPCL on Linux: drop DTR/RTS on close (does not reset)
            self._set_handshake(0)
        except Exception:
            pass
        self._dev.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def __del__(self):
        try:
            self.close()
        except Exception:
            pass


# --- Shared CLI helpers for the scripts --------------------------------------

def add_cli_args(parser):
    parser.add_argument("--baud", type=int, default=115200)
    parser.add_argument("--backend", choices=["auto", "pyusb", "usbfs"], default="auto",
                        help="USB access method (default auto: pyusb, fallback usbfs)")
    parser.add_argument("--device", default=None,
                        help="usbfs node, e.g. /dev/bus/usb/001/002 (usbfs backend)")
    parser.add_argument("--fd", type=int, default=None,
                        help="already-open usbfs fd (termux-usb); also read from TERMUX_USB_FD")
    parser.add_argument("--init", choices=["kernel", "legacy"], default="kernel",
                        help="chip init sequence (default: mainline Linux ch341.c)")
    parser.add_argument("--no-reset", action="store_true",
                        help="do not pulse DTR on open (printer will not reboot)")
    parser.add_argument("--debug", action="store_true", help="log every USB transfer")
    return parser


def open_from_args(args, timeout=10):
    return CH340Serial(baudrate=args.baud, timeout=timeout, backend=args.backend,
                       device_path=args.device, fd=args.fd, init_mode=args.init,
                       reset_on_open=not args.no_reset, debug=args.debug)


if __name__ == "__main__":
    # Self-check of the divisor math (runs anywhere, no hardware needed).
    for b in (9600, 57600, 115200, 250000):
        v = ch341_get_divisor(b)
        print("%7d baud -> reg 0x1312 = 0x%04x (| 0x80 = 0x%04x), actual %.1f bps; legacy %s"
              % (b, v, v | 0x80, actual_baud(v),
                 tuple(hex(x) for x in ch341_legacy_divisor(b))))
    print("CH340 nodes via sysfs:", find_device_nodes() or "none")
