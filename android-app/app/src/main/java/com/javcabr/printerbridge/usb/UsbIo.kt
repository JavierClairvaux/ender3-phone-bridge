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
