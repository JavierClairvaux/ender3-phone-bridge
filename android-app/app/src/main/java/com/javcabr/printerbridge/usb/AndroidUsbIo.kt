package com.javcabr.printerbridge.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Real [UsbIo] on Android's USB Host API (from the phase-2 spike, queued mode only).
 *
 * Opening = UsbManager.openDevice + claimInterface(force=true) on the first
 * interface with a bulk IN and a bulk OUT endpoint (CH340: interface 0, bulk IN
 * 0x82, bulk OUT 0x02; CDC-ACM board: data interface 1, bulk IN 0x82, OUT 0x01,
 * plus the class-0x02 control interface). Nothing is sent to the device here, and
 * nothing here touches the modem-control lines on its own: for a CH340 that is the
 * Python side's business (DTR-safe, see printer_link.py); for CDC it is
 * [CdcPrinterConnection] calling [setControlLines] according to the [UsbProfile].
 *
 * RX: a Kotlin thread keeps [N_REQ] asynchronous UsbRequests of [REQ_SIZE] bytes
 * queued on bulk IN at all times and pushes what arrives into a queue; the
 * driver's bulkRead() only drains that queue. This is the ONLY RX path: the
 * spike showed per-packet synchronous bulkTransfer calls let the CH340's FIFO
 * overflow (~3 ms at 115200) and silently drop bytes mid-line.
 *
 * Disconnect detection: requestWait(timeout) throws TimeoutException for "no
 * data" (unlike bulkTransfer, which returns -1 for both timeout and error), so
 * any other failure/null means the device is gone. That is latched in
 * [failure] and surfaces to Python as an IOException from bulkRead(). The
 * service additionally listens for USB_DEVICE_DETACHED.
 */
class AndroidUsbIo(manager: UsbManager, val device: UsbDevice) : UsbIo, CdcControl {
    private val conn: UsbDeviceConnection
    private val intf: UsbInterface
    /** CDC communications interface (class 0x02), claimed so class requests can be sent; null on a CH340. */
    private var ctrlIntf: UsbInterface? = null
    /**
     * CDC-ACM only (a CH340 is untouched and keeps [N_REQ] requests and its original handling order).
     * A native-USB board sends a reply as many tiny packets (1 to 2 bytes), each of which completes a whole
     * request; with only 4 requests, a burst can use them all up before the reader thread has handed them
     * back, the endpoint is then not polled for a moment and the board's USB stack may discard the next
     * piece (seen on the SKR Mini E3 V3.0: damaged `M105` replies, the board itself is clean on a Linux host).
     * So: [N_REQ_CDC] requests, and each one is re-queued before its bytes are handed on.
     */
    private val fastRx: Boolean
    private val nReq: Int
    private val epIn: UsbEndpoint
    private val epOut: UsbEndpoint
    @Volatile var closed = false
        private set
    @Volatile var failure: String? = null
        private set
    @Volatile var rxBytes = 0L
        private set

    private val rxQueue = LinkedBlockingQueue<ByteArray>()
    private var pending: ByteArray? = null
    private val requests = mutableListOf<UsbRequest>()
    private val readerThread: Thread

    init {
        val c = manager.openDevice(device) ?: throw IOException("openDevice returned null (no USB permission?)")
        var found: Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? = null
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            if (inEp != null && outEp != null) { found = Triple(itf, inEp, outEp); break }
        }
        if (found == null) { c.close(); throw IOException("no interface with bulk IN+OUT") }
        conn = c
        intf = found.first; epIn = found.second; epOut = found.third
        fastRx = intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA
        nReq = if (fastRx) N_REQ_CDC else N_REQ
        if (!conn.claimInterface(intf, true)) { conn.close(); throw IOException("claimInterface failed") }
        // CDC-ACM: the bulk endpoints are on the data interface (class 0x0A); SET_LINE_CODING and
        // SET_CONTROL_LINE_STATE address the communications interface (class 0x02), so claim that too.
        if (intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
            ctrlIntf = (0 until device.interfaceCount).map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
            ctrlIntf?.let { if (!conn.claimInterface(it, true)) Log.w(TAG, "claiming CDC control interface ${it.id} failed") }
        }
        try {
            repeat(nReq) {
                val r = UsbRequest()
                check(r.initialize(conn, epIn)) { "UsbRequest.initialize failed" }
                val buf = ByteBuffer.allocate(REQ_SIZE)
                r.clientData = buf
                check(r.queue(buf)) { "UsbRequest.queue failed" }
                requests.add(r)
            }
        } catch (e: Exception) {
            for (r in requests) try { r.close() } catch (_: Throwable) {}
            ctrlIntf?.let { try { conn.releaseInterface(it) } catch (_: Throwable) {} }
            try { conn.releaseInterface(intf) } catch (_: Throwable) {}
            conn.close()
            throw IOException("setting up queued reader: ${e.message}", e)
        }
        readerThread = Thread(::readerLoop, "usb-rx-kotlin").apply { isDaemon = true; start() }
        Log.i(TAG, "opened ${device.deviceName} if=${intf.id} epIn=0x%02x epOut=0x%02x maxPkt=%d, %d x %d B requests queued"
            .format(epIn.address, epOut.address, epIn.maxPacketSize, nReq, REQ_SIZE))
    }

    private fun readerLoop() {
        while (!closed) {
            val r = try {
                conn.requestWait(250)
            } catch (e: TimeoutException) {
                continue // idle, NOT a disconnect
            } catch (e: Exception) {
                if (!closed) fail("requestWait: $e")
                break
            }
            if (r == null) { if (!closed) fail("requestWait returned null"); break }
            if (closed) break
            val buf = r.clientData as ByteBuffer
            val n = buf.position()
            if (fastRx) {
                // copy out, give the request straight back to the controller, only then do anything else
                val out = if (n > 0) ByteArray(n).also { buf.flip(); buf.get(it) } else null
                buf.clear()
                if (!r.queue(buf)) { if (!closed) fail("re-queue failed"); break }
                if (rxTrace) Log.d(TAG, "rx n=$n")
                if (out != null) { rxBytes += n; rxQueue.put(out) }
                continue
            }
            if (rxTrace) Log.d(TAG, "rx n=$n")
            if (n > 0) {
                val out = ByteArray(n)
                buf.flip(); buf.get(out)
                rxBytes += n
                rxQueue.put(out)
            }
            buf.clear()
            if (!r.queue(buf)) { if (!closed) fail("re-queue failed"); break }
        }
    }

    private fun fail(why: String) {
        if (failure == null) { failure = why; Log.e(TAG, "USB RX failed: $why") }
    }

    override fun maxPacketSize(): Int = epIn.maxPacketSize

    override fun controlOut(request: Int, value: Int, index: Int, timeoutMs: Int): Int {
        val n = conn.controlTransfer(0x40, request, value, index, null, 0, timeoutMs)
        Log.d(TAG, "ctrlOut req=0x%02x value=0x%04x index=0x%04x -> %d".format(request, value, index, n))
        return n
    }

    override fun controlIn(request: Int, value: Int, index: Int, length: Int, timeoutMs: Int): ByteArray? {
        val buf = ByteArray(length)
        val n = conn.controlTransfer(0xC0, request, value, index, buf, length, timeoutMs)
        return if (n < 0) null else buf.copyOf(n)
    }

    // CDC class requests: bmRequestType 0x21 = host-to-device | class | interface.
    override fun setLineCoding(baud: Int): Boolean {
        val c = ctrlIntf ?: return false
        val lc = byteArrayOf(baud.toByte(), (baud shr 8).toByte(), (baud shr 16).toByte(), (baud shr 24).toByte(),
            0 /* 1 stop bit */, 0 /* no parity */, 8 /* data bits */)
        val n = conn.controlTransfer(0x21, 0x20, 0, c.id, lc, lc.size, 1000)
        Log.d(TAG, "SET_LINE_CODING $baud -> $n")
        return n >= 0
    }

    override fun setControlLines(dtr: Boolean, rts: Boolean): Boolean {
        val c = ctrlIntf ?: return false
        val v = (if (dtr) 1 else 0) or (if (rts) 2 else 0)
        val n = conn.controlTransfer(0x21, 0x22, v, c.id, null, 0, 1000)
        Log.d(TAG, "SET_CONTROL_LINE_STATE dtr=$dtr rts=$rts -> $n")
        return n >= 0
    }

    override fun bulkRead(size: Int, timeoutMs: Int): ByteArray {
        failure?.let { if (rxQueue.isEmpty() && pending == null) throw IOException("USB device lost: $it") }
        if (closed) return ByteArray(0)
        val chunk = pending ?: rxQueue.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return ByteArray(0)
        pending = null
        if (chunk.size <= size) return chunk
        pending = chunk.copyOfRange(size, chunk.size)
        return chunk.copyOf(size)
    }

    override fun bulkWrite(data: ByteArray, timeoutMs: Int): Int {
        if (failure != null || closed) return -1
        return conn.bulkTransfer(epOut, data, data.size, timeoutMs)
    }

    override fun close() {
        if (closed) return
        closed = true
        for (r in requests) try { r.cancel() } catch (_: Throwable) {}
        readerThread.join(1000)
        for (r in requests) try { r.close() } catch (_: Throwable) {}
        ctrlIntf?.let { try { conn.releaseInterface(it) } catch (_: Throwable) {} }
        try { conn.releaseInterface(intf) } catch (_: Throwable) {}
        conn.close()
        Log.i(TAG, "closed ${device.deviceName} (rx total $rxBytes B)")
    }

    companion object {
        const val TAG = "PrinterBridge.usb"
        const val N_REQ = 4
        /** Requests kept queued on a CDC-ACM data endpoint (see [fastRx]). */
        const val N_REQ_CDC = 32
        const val REQ_SIZE = 512
        /** Debug: log the length of every completed bulk-IN request (diagnosing lost bytes). */
        @Volatile var rxTrace = false

        fun describe(d: UsbDevice): String =
            "%s %04x:%04x '%s'".format(d.deviceName, d.vendorId, d.productId, d.productName)
    }
}
