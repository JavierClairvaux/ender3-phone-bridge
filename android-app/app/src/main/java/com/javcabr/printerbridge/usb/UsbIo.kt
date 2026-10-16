package com.javcabr.printerbridge.usb

/**
 * What the Python driver (ch340_serial.py, via printer_link.KotlinUsbBackend)
 * calls back into for USB I/O. Implemented by [AndroidUsbIo] on the real USB
 * Host API and by [SimCh340UsbIo] (a simulated CH340 + Marlin) for tests.
 */
interface UsbIo {
    fun maxPacketSize(): Int
    /** Vendor OUT control transfer (bmRequestType 0x40). Returns >= 0 on success. */
    fun controlOut(request: Int, value: Int, index: Int, timeoutMs: Int): Int
    /** Vendor IN control transfer (bmRequestType 0xC0). Returns null on failure. */
    fun controlIn(request: Int, value: Int, index: Int, length: Int, timeoutMs: Int): ByteArray?
    /** Bulk IN. Returns an empty array on timeout; throws java.io.IOException if the device is gone. */
    fun bulkRead(size: Int, timeoutMs: Int): ByteArray
    /** Bulk OUT. Returns bytes written or < 0 on failure. */
    fun bulkWrite(data: ByteArray, timeoutMs: Int): Int
    fun close()
}

/**
 * Class-specific requests of a CDC-ACM serial port (native-USB boards such as the
 * SKR Mini E3 V3.0). Implemented next to [UsbIo] by [AndroidUsbIo] and [SimCdcUsbIo].
 * Both only configure the port; neither can reset a native-USB Marlin board.
 */
interface CdcControl {
    /** SET_LINE_CODING, 8N1. Returns true on success. */
    fun setLineCoding(baud: Int): Boolean
    /** SET_CONTROL_LINE_STATE. Returns true on success. */
    fun setControlLines(dtr: Boolean, rts: Boolean): Boolean
}
