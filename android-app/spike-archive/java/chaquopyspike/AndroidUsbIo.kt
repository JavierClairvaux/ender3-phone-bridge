package com.example.chaquopyspike

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Real [UsbIo] on top of Android's USB Host API: the same interface
 * [FakeCh340] implements, so the Python driver can't tell the difference.
 *
 * Opening = UsbManager.openDevice + claimInterface(force=true) on the first
 * interface that has a bulk IN and a bulk OUT endpoint (CH340: interface 0,
 * bulk IN 0x82, bulk OUT 0x02, plus an interrupt IN 0x81 we don't use).
 * Nothing is sent to the chip by the constructor.
 *
 * Two read strategies:
 *  - queuedReader=false ("direct", run 1): every Python bulkRead() does one
 *    synchronous UsbDeviceConnection.bulkTransfer of one packet. The bulk IN
 *    endpoint is only polled while Python is inside that call.
 *  - queuedReader=true: a Kotlin thread keeps [N_REQ] asynchronous UsbRequests
 *    queued on bulk IN at all times (like usb-serial-for-android), and puts what
 *    arrives into a queue; Python's bulkRead() just drains that queue.
 *
 * verbose=false suppresses per-packet logcat lines (they cost time).
 */
class AndroidUsbIo(
    manager: UsbManager,
    val device: UsbDevice,
    private val label: String = "",
    val queuedReader: Boolean = false,
    val verbose: Boolean = true,
) : UsbIo {
    data class Call(val tMs: Long, val what: String, val thread: String)

    val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
    val t0 = SystemClock.elapsedRealtime()
    private val conn: UsbDeviceConnection
    private val intf: UsbInterface
    private val epIn: UsbEndpoint
    private val epOut: UsbEndpoint
    @Volatile var closed = false
    @Volatile var bulkReadErrors = 0
    @Volatile var bulkReadCalls = 0
    val rxLog = StringBuilder()

    // queued-reader state
    private val rxQueue = LinkedBlockingQueue<ByteArray>()
    private var pending: ByteArray? = null
    private var readerThread: Thread? = null
    private val requests = mutableListOf<UsbRequest>()

    init {
        val c = manager.openDevice(device) ?: throw IllegalStateException("openDevice returned null (no permission?)")
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
        if (found == null) { c.close(); throw IllegalStateException("no interface with bulk IN+OUT") }
        conn = c
        intf = found.first; epIn = found.second; epOut = found.third
        if (!conn.claimInterface(intf, true)) {
            conn.close(); throw IllegalStateException("claimInterface failed")
        }
        record("open+claim if=%d epIn=0x%02x epOut=0x%02x maxPkt=%d queued=%s".format(
            intf.id, epIn.address, epOut.address, epIn.maxPacketSize, queuedReader), force = true)
        if (queuedReader) startQueuedReader()
    }

    fun elapsed() = SystemClock.elapsedRealtime() - t0

    private fun record(what: String, force: Boolean = false) {
        val c = Call(elapsed(), what, Thread.currentThread().name)
        calls.add(c)
        if (verbose || force) Log.i(TAG, "HW$label +${c.tMs}ms $what [thread=${c.thread}]")
    }

    private fun startQueuedReader() {
        val size = epIn.maxPacketSize * 16
        repeat(N_REQ) {
            val r = UsbRequest()
            check(r.initialize(conn, epIn)) { "UsbRequest.initialize failed" }
            val buf = ByteBuffer.allocate(size)
            r.clientData = buf
            check(r.queue(buf)) { "UsbRequest.queue failed" }
            requests.add(r)
        }
        readerThread = Thread({
            while (!closed) {
                val r = try { conn.requestWait(250) } catch (e: TimeoutException) { continue }
                    catch (e: Exception) { if (!closed) Log.e(TAG, "requestWait", e); break }
                    ?: break
                if (closed) break
                val buf = r.clientData as ByteBuffer
                val n = buf.position()
                if (n > 0) {
                    val out = ByteArray(n)
                    buf.flip(); buf.get(out)
                    rxQueue.put(out)
                    synchronized(rxLog) { rxLog.append(String(out, Charsets.ISO_8859_1)) }
                    if (verbose) record("usbRequest -> ${esc(out)}")
                }
                buf.clear()
                if (!r.queue(buf)) { Log.e(TAG, "re-queue failed"); break }
            }
        }, "usb-rx-kotlin").apply { isDaemon = true; start() }
    }

    private fun esc(b: ByteArray) =
        String(b, Charsets.ISO_8859_1).replace("\n", "\\n").replace("\r", "\\r")

    override fun maxPacketSize(): Int = epIn.maxPacketSize

    override fun controlOut(request: Int, value: Int, index: Int, timeoutMs: Int): Int {
        val n = conn.controlTransfer(0x40, request, value, index, null, 0, timeoutMs)
        record("ctrlOut req=0x%02x value=0x%04x index=0x%04x -> %d".format(request, value, index, n), force = true)
        return n
    }

    override fun controlIn(request: Int, value: Int, index: Int, length: Int, timeoutMs: Int): ByteArray? {
        val buf = ByteArray(length)
        val n = conn.controlTransfer(0xC0, request, value, index, buf, length, timeoutMs)
        val out = if (n < 0) null else buf.copyOf(n)
        record("ctrlIn req=0x%02x value=0x%04x index=0x%04x len=%d -> %s".format(
            request, value, index, length, out?.joinToString("") { "%02x".format(it) } ?: "FAIL($n)"), force = true)
        return out
    }

    override fun bulkRead(size: Int, timeoutMs: Int): ByteArray {
        bulkReadCalls++
        if (queuedReader) {
            val chunk = pending ?: rxQueue.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return ByteArray(0)
            pending = null
            if (chunk.size <= size) return chunk
            pending = chunk.copyOfRange(size, chunk.size)
            return chunk.copyOf(size)
        }
        val buf = ByteArray(size)
        val n = conn.bulkTransfer(epIn, buf, size, timeoutMs)
        // Android returns -1 for BOTH a timeout and a real error (e.g. device
        // unplugged), so we can only treat it as "no data".
        if (n <= 0) {
            if (n < 0) bulkReadErrors++
            return ByteArray(0)
        }
        val out = buf.copyOf(n)
        synchronized(rxLog) { rxLog.append(String(out, Charsets.ISO_8859_1)) }
        if (verbose) record("bulkRead -> ${esc(out)}") else calls.add(Call(elapsed(), "bulkRead", Thread.currentThread().name))
        return out
    }

    override fun bulkWrite(data: ByteArray, timeoutMs: Int): Int {
        val n = conn.bulkTransfer(epOut, data, data.size, timeoutMs)
        record("bulkWrite ${String(data, Charsets.US_ASCII).replace("\n", "\\n")} -> $n")
        return n
    }

    override fun close() {
        if (closed) return
        closed = true
        for (r in requests) try { r.cancel() } catch (_: Throwable) {}
        readerThread?.join(1000)
        for (r in requests) try { r.close() } catch (_: Throwable) {}
        try { conn.releaseInterface(intf) } catch (_: Throwable) {}
        conn.close()
        record("close", force = true)
    }

    companion object {
        const val TAG = FakeCh340.TAG
        const val VID = 0x1a86
        const val PID = 0x7523
        const val N_REQ = 4

        fun findCh340(manager: UsbManager): UsbDevice? =
            manager.deviceList.values.firstOrNull { it.vendorId == VID && it.productId == PID }

        fun describe(d: UsbDevice): String = buildString {
            append("%s %04x:%04x '%s' '%s' ifaces=%d".format(
                d.deviceName, d.vendorId, d.productId, d.manufacturerName, d.productName, d.interfaceCount))
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                append("\n  if%d class=%d:".format(itf.id, itf.interfaceClass))
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    append(" ep0x%02x(type=%d,max=%d)".format(ep.address, ep.type, ep.maxPacketSize))
                }
            }
        }
    }
}
