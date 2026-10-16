package com.javcabr.printerbridge.usb

import com.javcabr.printerbridge.printer.FakeMarlin
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A simulated native-USB CDC-ACM board (the SKR Mini E3 V3.0 running Marlin 2) in front of a
 * [FakeMarlin], at the USB-transfer level. Lets the full [com.javcabr.printerbridge.printer.CdcPrinterConnection]
 * path run on the emulator without hardware.
 *
 * Behaviour copied from the real board (checked 2026-10-14): the board keeps running when the
 * host opens or closes the port, and a DTR edge does NOT reboot it. The simulator also keeps
 * count of line-state changes ([controlLog]) so a test can see what the app sent. [requireDtr]
 * models an STM32 CDC port that stays silent until the host raises DTR; it is off by default
 * because that behaviour is not yet confirmed on the real board.
 */
class SimCdcUsbIo(
    private val marlin: FakeMarlin,
    private val requireDtr: Boolean = false,
    staleOnOpen: ByteArray = ByteArray(0),
) : UsbIo, CdcControl {
    private val rx = LinkedBlockingQueue<Byte>()
    private val lineBuf = StringBuilder()
    /** One entry per class request, e.g. "line_coding:115200" or "lines:dtr=true,rts=true". */
    val controlLog: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile var closed = false; private set
    @Volatile var dtr = false; private set

    init {
        marlin.marlin2 = true
        staleOnOpen.forEach { rx.put(it) }
        marlin.attach { line ->
            if (!requireDtr || dtr) (line + "\n").toByteArray(Charsets.ISO_8859_1).forEach { rx.put(it) }
        }
    }

    override fun maxPacketSize() = 64

    override fun setLineCoding(baud: Int): Boolean { controlLog.add("line_coding:$baud"); return true }

    override fun setControlLines(dtr: Boolean, rts: Boolean): Boolean {
        controlLog.add("lines:dtr=$dtr,rts=$rts")
        this.dtr = dtr
        return true
    }

    // The CH340 vendor requests do not exist on a CDC device.
    override fun controlOut(request: Int, value: Int, index: Int, timeoutMs: Int) = -1
    override fun controlIn(request: Int, value: Int, index: Int, length: Int, timeoutMs: Int): ByteArray? = null

    override fun bulkRead(size: Int, timeoutMs: Int): ByteArray {
        if (closed) return ByteArray(0)
        val first = rx.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return ByteArray(0)
        val out = ArrayList<Byte>(size).apply { add(first) }
        while (out.size < size) out.add(rx.poll() ?: break)
        return out.toByteArray()
    }

    override fun bulkWrite(data: ByteArray, timeoutMs: Int): Int {
        if (requireDtr && !dtr) return data.size   // swallowed, like a closed port
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
