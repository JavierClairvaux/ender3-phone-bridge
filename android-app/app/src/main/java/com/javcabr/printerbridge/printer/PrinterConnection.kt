package com.javcabr.printerbridge.printer

import java.io.IOException

/**
 * A line-oriented link to a Marlin printer. The Marlin protocol logic (ok /
 * Resend / busy / temperature parsing, job streaming) lives in
 * [PrinterController] and is shared by every implementation, so the fake
 * backend exercises the same code paths the real one uses.
 *
 * Implementations are driven from a single worker thread.
 */
interface PrinterConnection {
    /** "fake", "real", or "sim-usb". */
    val kind: String
    val isOpen: Boolean

    /** Open the link. Must NOT reset the printer. */
    @Throws(IOException::class)
    fun open()

    /** Close the link. Must NOT reset the printer. */
    fun close()

    /** Write one line (no trailing newline). */
    @Throws(IOException::class)
    fun writeLine(line: String)

    /** Next complete line from the printer, or null if none arrived within [timeoutMs]. */
    @Throws(IOException::class)
    fun readLine(timeoutMs: Long): String?

    /** Deliberately reboot the board (DTR pulse on real hardware). Never called implicitly. */
    @Throws(IOException::class)
    fun resetBoard()

    /** False while motion/heater commands would be refused (real-printer safety lock ON). */
    fun allowsMotion(): Boolean

    /** Diagnostic info for /api/connection and the dashboard. */
    fun info(): Map<String, Any?>
}

/** Raised when a command is refused by the real-hardware safety lock. */
class SafetyLockException(msg: String) : IOException(msg)
