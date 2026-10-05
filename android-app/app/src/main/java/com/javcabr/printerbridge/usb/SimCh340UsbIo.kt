package com.javcabr.printerbridge.usb

import com.javcabr.printerbridge.printer.FakeMarlin
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A simulated CH340 in front of a [FakeMarlin], at the USB-transfer level
 * (successor of the spike's FakeCh340). Lets the full RealPrinterConnection
 * path (Kotlin -> Chaquopy -> unmodified ch340_serial.py -> printer_link.py)
 * run on the emulator.
 *
 * Like the real Ender 3 board, any DTR edge on the modem-control request
 * (0xA4) reboots the simulated Marlin, so a test can prove the DTR-safe
 * subclass never produces one. [staleOnOpen] preloads garbage bytes (what a
 * freshly powered CH340 can hold) to exercise the post-open drain.
 */
class SimCh340UsbIo(
    private val marlin: FakeMarlin,
    staleOnOpen: ByteArray = ByteArray(0),
) : UsbIo {
    private val rx = LinkedBlockingQueue<Byte>()
    private val lineBuf = StringBuilder()
    val mcrLog: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    @Volatile var dtrEdges = 0; private set
    @Volatile var closed = false; private set
    private var dtr = false

    init {
        staleOnOpen.forEach { rx.put(it) }
        marlin.attach { line -> (line + "\n").toByteArray(Charsets.ISO_8859_1).forEach { rx.put(it) } }
    }

    override fun maxPacketSize() = 32

    override fun controlOut(request: Int, value: Int, index: Int, timeoutMs: Int): Int {
        if (request == 0xA4) {
            val mcr = value.inv() and 0xFF      // sent inverted on the wire
            mcrLog.add(mcr)
            val newDtr = mcr and 0x20 != 0
            if (newDtr != dtr) { dtrEdges++; dtr = newDtr; marlin.reboot() }
        }
        return 0
    }

    override fun controlIn(request: Int, value: Int, index: Int, length: Int, timeoutMs: Int): ByteArray? {
        val out = ByteArray(length)
        if (request == 0x5F) out[0] = 0x31   // chip version the real chip reported
        return out
    }

    override fun bulkRead(size: Int, timeoutMs: Int): ByteArray {
        if (closed) return ByteArray(0)
        val first = rx.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return ByteArray(0)
        val out = ArrayList<Byte>(size).apply { add(first) }
        while (out.size < size) out.add(rx.poll() ?: break)
        return out.toByteArray()
    }

    override fun bulkWrite(data: ByteArray, timeoutMs: Int): Int {
        synchronized(lineBuf) {
            lineBuf.append(String(data, Charsets.ISO_8859_1))
            var nl = lineBuf.indexOf("\n")
            while (nl >= 0) {
                marlin.feed(lineBuf.substring(0, nl))
                lineBuf.delete(0, nl + 1)
                nl = lineBuf.indexOf("\n")
            }
        }
        return data.size
    }

    override fun close() { closed = true; marlin.detach() }
}
