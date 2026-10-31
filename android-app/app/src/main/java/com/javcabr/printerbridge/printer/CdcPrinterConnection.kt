package com.javcabr.printerbridge.printer

import android.util.Log
import com.javcabr.printerbridge.usb.AndroidUsbIo
import com.javcabr.printerbridge.usb.CdcControl
import com.javcabr.printerbridge.usb.UsbIo
import com.javcabr.printerbridge.usb.UsbProfile
import java.io.IOException

/**
 * A Marlin board on a native-USB CDC-ACM port (SKR Mini E3 V3.0 and similar): the same
 * line protocol as [RealPrinterConnection], without the CH340 Python driver. Bytes go
 * straight through the [UsbIo] bulk endpoints ([AndroidUsbIo]'s queued reader on the phone,
 * [com.javcabr.printerbridge.usb.SimCdcUsbIo] in tests); the port itself is set up with the
 * CDC class requests in [CdcControl].
 *
 * Opening never resets the board: it sets 115200 8N1 and, if the [profile] says so,
 * raises DTR+RTS (a native-USB Marlin only talks to a host that has opened the port, and a
 * DTR edge does not reboot it; checked on the SKR Mini E3 V3.0 on 2026-10-14). Unknown CDC
 * devices keep the lines off, because an Arduino-style USB-serial bridge reboots on a DTR edge.
 *
 * Safety lock and command filtering are identical to [RealPrinterConnection].
 */
class CdcPrinterConnection(
    override val kind: String,
    private val profile: UsbProfile,
    private val ioFactory: () -> UsbIo,
    private val readOnlyLock: () -> Boolean,
    private val settleMs: Long = 750,
    private val baud: Int = 115200,
) : PrinterConnection {
    private var io: UsbIo? = null
    private var cdc: CdcControl? = null
    @Volatile override var isOpen = false; private set
    @Volatile var linesWritten = 0L; private set
    @Volatile private var dtrAsserted = false
    private val pending = StringBuilder()
    private var openInfo: Map<String, Any?> = emptyMap()

    override fun open() {
        val newIo = ioFactory()
        val control = newIo as? CdcControl
        if (control == null) { try { newIo.close() } catch (_: Throwable) {}; throw IOException("USB transport for $kind has no CDC control") }
        try {
            if (!control.setLineCoding(baud)) Log.w(TAG, "SET_LINE_CODING failed (continuing: native USB ignores the baud rate)")
            if (profile.assertDtr) {
                if (!control.setControlLines(dtr = true, rts = true)) throw IOException("SET_CONTROL_LINE_STATE failed")
                dtrAsserted = true
            }
            io = newIo; cdc = control
            pending.setLength(0)
            val stale = drain(settleMs)
            val markers = BOOT_MARKERS.filter { stale.contains(it) }
            isOpen = true
            openInfo = mapOf(
                "transport" to profile.transport.wire,
                "usb_profile" to profile.id,
                "usb_profile_name" to profile.name,
                "settle_ms" to settleMs,
                "stale_bytes_drained" to stale.length,
                "stale_text" to stale.take(300),
                "boot_markers_on_open" to markers.joinToString(","),
                "reset_detected_on_open" to markers.isNotEmpty(),
                "opened_at" to System.currentTimeMillis(),
            )
            Log.i(TAG, "opened $kind (${profile.id}): $openInfo dtr=$dtrAsserted")
        } catch (e: Exception) {
            try { newIo.close() } catch (_: Throwable) {}
            io = null; cdc = null
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }

    /** Read and discard whatever the port holds for [ms]; returns it as text. */
    private fun drain(ms: Long): String {
        val i = io ?: return ""
        val sb = StringBuilder()
        val end = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < end) {
            val left = ((end - System.nanoTime()) / 1_000_000).coerceIn(1, 100)
            val b = i.bulkRead(512, left.toInt())
            if (b.isNotEmpty()) sb.append(String(b, Charsets.ISO_8859_1))
        }
        return sb.toString()
    }

    override fun close() {
        val i = io ?: return
        isOpen = false
        if (dtrAsserted) try { cdc?.setControlLines(dtr = false, rts = false) } catch (e: Exception) { Log.w(TAG, "close: $e") }
        dtrAsserted = false
        try { i.close() } catch (_: Throwable) {}
        io = null; cdc = null
        Log.i(TAG, "closed $kind")
    }

    override fun writeLine(line: String) {
        if (readOnlyLock()) {
            val code = RealPrinterConnection.commandWord(line)
            if (!RealPrinterConnection.isReadOnly(line)) throw SafetyLockException(
                "real-printer safety lock is ON: '$code' refused (only ${RealPrinterConnection.READ_ONLY.joinToString()} and M211 without arguments allowed; disable the lock in Settings for the hardware pass)")
        }
        val i = io ?: throw IOException("not open")
        val bytes = (line + "\n").toByteArray(Charsets.ISO_8859_1)
        val n = i.bulkWrite(bytes, WRITE_TIMEOUT_MS)
        if (n < bytes.size) throw IOException("USB write failed ($n of ${bytes.size} bytes)")
        linesWritten++
    }

    override fun readLine(timeoutMs: Long): String? {
        val i = io ?: throw IOException("not open")
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val nl = pending.indexOf("\n")
            if (nl >= 0) {
                val line = pending.substring(0, nl).trimEnd('\r')
                pending.delete(0, nl + 1)
                if (AndroidUsbIo.rxTrace && line != "ok") Log.d(TAG, "rx line %s (%d bytes)".format(
                    "'" + line.replace("\r", "\\r") + "'", line.length))
                return line
            }
            val left = (end - System.nanoTime()) / 1_000_000
            if (left <= 0) return null
            val b = i.bulkRead(512, minOf(left, 100L).toInt())   // throws IOException once the device is gone
            if (b.isNotEmpty()) pending.append(String(b, Charsets.ISO_8859_1))
        }
    }

    override fun resetBoard() {
        if (readOnlyLock()) throw SafetyLockException("real-printer safety lock is ON: board reset refused")
        throw IOException("board reset is not available on a native-USB board: a DTR edge does not reboot it. Power-cycle the printer or send M999 / M112 as appropriate")
    }

    override fun allowsMotion() = !readOnlyLock()

    override fun info(): Map<String, Any?> {
        val usb = io as? AndroidUsbIo
        return mapOf("backend" to kind, "open" to isOpen, "safety_lock" to readOnlyLock(),
            "dtr_asserted" to dtrAsserted, "lines_written" to linesWritten,
            "usb_device" to usb?.let { AndroidUsbIo.describe(it.device) },
            "usb_rx_bytes" to usb?.rxBytes, "usb_failure" to usb?.failure) + openInfo
    }

    companion object {
        const val TAG = "PrinterBridge.cdc"
        const val WRITE_TIMEOUT_MS = 2000
        /** Lines Marlin prints from setup() after a reset; arriving right after open means a (re)boot. */
        val BOOT_MARKERS = listOf("start", "External Reset", "Power-Up", "Brown out", "Watchdog Reset",
            "Software Reset", "Compiled:", "Free Memory")
    }
}
