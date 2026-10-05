package com.javcabr.printerbridge.printer

import android.content.Context
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.javcabr.printerbridge.usb.AndroidUsbIo
import com.javcabr.printerbridge.usb.UsbIo
import java.io.IOException

/**
 * The real printer: Android USB Host API ([AndroidUsbIo], queued UsbRequest
 * reader) -> Chaquopy -> unmodified ch340_serial.py, through printer_link.py's
 * DtrSafeCH340Serial (DTR/RTS deasserted for the whole connection, on open and
 * on close, so connecting/disconnecting never reboots Marlin).
 *
 * [ioFactory] opens the UsbIo. In the app it builds an AndroidUsbIo for the
 * CH340; with kind="sim-usb" it builds a SimCh340UsbIo so this exact class
 * (and the Python path) can run on the emulator.
 *
 * Safety lock: while [readOnlyLock] returns true (the default for the real
 * backend until the final hardware pass), only M115/M105/M503/M114/M119 may be
 * written; anything else throws [SafetyLockException] before touching USB.
 */
class RealPrinterConnection(
    context: Context,
    override val kind: String,
    private val ioFactory: () -> UsbIo,
    private val readOnlyLock: () -> Boolean,
    private val settleMs: Long = 750,
) : PrinterConnection {
    private val appContext = context.applicationContext
    private var io: UsbIo? = null
    private var link: PyObject? = null
    @Volatile override var isOpen = false; private set
    private var openInfo: Map<String, Any?> = emptyMap()
    @Volatile var linesWritten = 0L; private set

    override fun open() {
        val py = py(appContext)
        val newIo = try { ioFactory() } catch (e: IOException) { throw e } catch (e: Exception) { throw IOException(e.message, e) }
        try {
            val l = py.getModule("printer_link").callAttr("open_link", newIo, settleMs / 1000.0, false)
            link = l; io = newIo; isOpen = true
            openInfo = mapOf(
                "chip_version" to "0x%02x".format(l.callAttr("chip_version").toInt()),
                "open_ms" to l.callAttr("open_ms").toInt(),
                "settle_ms" to settleMs,
                "stale_bytes_drained" to l.callAttr("stale_text").toString().length,
                "stale_text" to l.callAttr("stale_text").toString().take(300),
                "boot_markers_on_open" to l.callAttr("boot_markers").toString(),
                "reset_detected_on_open" to l.get("reset_detected")!!.toBoolean(),
                "opened_at" to System.currentTimeMillis(),
            )
            Log.i(TAG, "opened $kind: $openInfo handshake=${handshakeValues()}")
        } catch (e: PyException) {
            try { newIo.close() } catch (_: Throwable) {}
            throw IOException("driver open failed: ${e.message}", e)
        }
    }

    fun handshakeValues(): String = try { link?.callAttr("handshake_values")?.toString() ?: "" } catch (e: Exception) { "?" }
    fun dtrEverAsserted(): Boolean = try { link?.callAttr("dtr_ever_asserted")?.toBoolean() ?: false } catch (e: Exception) { false }

    override fun close() {
        val l = link ?: return
        isOpen = false
        try { l.callAttr("close") } catch (e: Exception) { Log.w(TAG, "close: $e") }
        try { io?.close() } catch (_: Throwable) {}
        link = null
        Log.i(TAG, "closed $kind")
    }

    override fun writeLine(line: String) {
        if (readOnlyLock()) {
            val code = commandWord(line)
            if (code !in READ_ONLY) throw SafetyLockException(
                "real-printer safety lock is ON: '$code' refused (only ${READ_ONLY.joinToString()} allowed; disable the lock in Settings for the hardware pass)")
        }
        val l = link ?: throw IOException("not open")
        try { l.callAttr("write_line", line); linesWritten++ } catch (e: PyException) { throw IOException("write failed: ${e.message}", e) }
    }

    override fun readLine(timeoutMs: Long): String? {
        val l = link ?: throw IOException("not open")
        return try { l.callAttr("read_line", timeoutMs)?.toString() } catch (e: PyException) { throw IOException("read failed: ${e.message}", e) }
    }

    override fun resetBoard() {
        if (readOnlyLock()) throw SafetyLockException("real-printer safety lock is ON: board reset refused")
        val l = link ?: throw IOException("not open")
        try { l.callAttr("deliberate_reset") } catch (e: PyException) { throw IOException("reset failed: ${e.message}", e) }
    }

    override fun allowsMotion() = !readOnlyLock()

    override fun info(): Map<String, Any?> {
        val usb = io as? AndroidUsbIo
        return mapOf("backend" to kind, "open" to isOpen, "safety_lock" to readOnlyLock(),
            "dtr_rts_values_sent" to handshakeValues(), "dtr_ever_asserted" to dtrEverAsserted(),
            "lines_written" to linesWritten,
            "usb_device" to usb?.let { AndroidUsbIo.describe(it.device) },
            "usb_rx_bytes" to usb?.rxBytes, "usb_failure" to usb?.failure) + openInfo
    }

    companion object {
        const val TAG = "PrinterBridge.real"
        val READ_ONLY = setOf("M115", "M105", "M503", "M114", "M119")

        /** First G/M word of a line, ignoring an "N123 " prefix and "*cs" suffix. */
        fun commandWord(line: String): String {
            var s = line.substringBefore('*').substringBefore(';').trim()
            if (s.startsWith("N")) s = s.substringAfter(' ', "").trim()
            return s.split(Regex("\\s+")).firstOrNull()?.uppercase() ?: ""
        }

        fun py(context: Context): Python {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
            return Python.getInstance()
        }
    }
}
