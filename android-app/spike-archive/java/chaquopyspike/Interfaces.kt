package com.example.chaquopyspike

/**
 * What the Python driver calls back into for USB I/O. In the real app this is
 * implemented on top of android.hardware.usb.UsbDeviceConnection; in the spike
 * it is implemented by [FakeCh340].
 */
interface UsbIo {
    fun maxPacketSize(): Int
    /** Vendor OUT control transfer (bmRequestType 0x40). Returns >= 0 on success. */
    fun controlOut(request: Int, value: Int, index: Int, timeoutMs: Int): Int
    /** Vendor IN control transfer (bmRequestType 0xC0). Returns null on failure. */
    fun controlIn(request: Int, value: Int, index: Int, length: Int, timeoutMs: Int): ByteArray?
    /** Bulk IN. Returns an empty array on timeout. */
    fun bulkRead(size: Int, timeoutMs: Int): ByteArray
    /** Bulk OUT. Returns bytes written or < 0 on failure. */
    fun bulkWrite(data: ByteArray, timeoutMs: Int): Int
    fun close()
}

/** Plain callback used for the simple reverse-direction tests. */
interface Listener {
    fun onEvent(msg: String, i: Int): Int
    fun boom(msg: String)
}

interface BinaryProbe {
    fun reverse(data: ByteArray): ByteArray
}
