package com.example.chaquopyspike

import android.util.Log
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Kotlin-side stand-in for a CH340 + Marlin board. Records every call the
 * Python driver makes into it (with the calling thread), answers the chip
 * version read with 0x31 (the version the real chip reported), and answers
 * every G-code line written to it with "ok\n".
 */
class FakeCh340(private val chipVersion: Int = 0x31) : UsbIo {
    data class Call(val what: String, val thread: String)

    val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
    private val rx = LinkedBlockingQueue<Byte>()
    private val lineBuf = StringBuilder()
    @Volatile var closed = false

    private fun record(what: String) {
        val c = Call(what, Thread.currentThread().name)
        calls.add(c)
        Log.i(TAG, "Kotlin<-Python ${c.what} [thread=${c.thread}]")
    }

    override fun maxPacketSize(): Int = 32

    override fun controlOut(request: Int, value: Int, index: Int, timeoutMs: Int): Int {
        record("ctrlOut req=0x%02x value=0x%04x index=0x%04x".format(request, value, index))
        return 0
    }

    override fun controlIn(request: Int, value: Int, index: Int, length: Int, timeoutMs: Int): ByteArray? {
        record("ctrlIn req=0x%02x value=0x%04x index=0x%04x len=%d".format(request, value, index, length))
        val out = ByteArray(length)
        if (request == 0x5F) out[0] = chipVersion.toByte()        // REQ_READ_VERSION
        return out
    }

    override fun bulkRead(size: Int, timeoutMs: Int): ByteArray {
        // Called continuously by the driver's ch340-reader thread; only log non-empty reads.
        val first = rx.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return ByteArray(0)
        val out = ArrayList<Byte>(size).apply { add(first) }
        while (out.size < size) out.add(rx.poll() ?: break)
        record("bulkRead -> ${String(out.toByteArray()).replace("\n", "\\n")}")
        return out.toByteArray()
    }

    override fun bulkWrite(data: ByteArray, timeoutMs: Int): Int {
        val s = String(data, Charsets.US_ASCII)
        record("bulkWrite ${s.replace("\n", "\\n")}")
        synchronized(lineBuf) {
            lineBuf.append(s)
            var nl = lineBuf.indexOf("\n")
            while (nl >= 0) {
                lineBuf.delete(0, nl + 1)
                "ok\n".toByteArray().forEach { rx.put(it) }
                nl = lineBuf.indexOf("\n")
            }
        }
        return data.size
    }

    override fun close() {
        record("close")
        closed = true
    }

    companion object { const val TAG = "ChaquopySpike" }
}
