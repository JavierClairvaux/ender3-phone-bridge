package com.javcabr.printerbridge.printer

import android.util.Log
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * A simulated Ender 3 running Marlin 1.1.6, at the text-line level.
 *
 * Like the real machine it exists independently of any connection: opening or
 * closing a connection does not reset it (only [reboot] does), so heater
 * targets, position and line numbers survive a reconnect, which is exactly the
 * behaviour the DTR-safe real connection gives.
 *
 * Behaviour:
 *  - "ok" after each command, after [lineDelayMs] (moves also take that long).
 *  - N<line> ... *<checksum> framing is checked like Marlin: bad checksum or
 *    out-of-sequence line -> "Error:..." + "Resend: n" + "ok".
 *    [injectResendEvery] > 0 fakes a checksum error on every Nth numbered line.
 *  - Temperatures ramp toward M104/M140 targets (hotend ~2 C/s, bed ~0.7 C/s,
 *    times [timeScale]) and cool toward ambient; M109/M190 block and print
 *    temperature lines every second until reached; G28 prints "echo:busy".
 *  - Tracked XYZE position (G90/G91, M82/M83, G92), reported by M114.
 *  - [triggerError] "thermal_runaway" / "mintemp": emits Marlin's kill() error
 *    lines and halts (no more replies, like the real firmware).
 *  - M85 S<n> arms Marlin 1.1.6's inactivity kill(): after n (simulated) seconds
 *    without a command other than the read-only reports M105/M114/M115/M119/M503
 *    it prints "Error:KILL caused by too much inactive time - current command: M105"
 *    + "Error:Printer halted. kill() called!" and halts, as the real board did after
 *    a finished print. Heat waits (M109/M190) count as activity. M85 S0 disarms.
 *    (Which commands refresh the real timer is approximated: it is certain that M105
 *    does not.)
 *  - Marlin 1.1 positioning semantics: G90/G91 switch XYZ AND E (relative_mode),
 *    M82/M83 switch only E (axis_relative_modes[E]); E is relative if either says so.
 *    Tracks feedrate (F on G0/G1), fan (M106 S/M107), and refuses E moves below
 *    170 C ("echo: cold extrusion prevented", like PREVENT_COLD_EXTRUSION).
 *  - [commandLog]: every executed command (N/checksum stripped), for sequence tests.
 */
class FakeMarlin {
    @Volatile var lineDelayMs = 15L
    @Volatile var timeScale = 1.0
    @Volatile var injectResendEvery = 0

    @Volatile private var sink: ((String) -> Unit)? = null
    private val input = LinkedBlockingQueue<String>()

    // machine state
    @Volatile var hotend = AMBIENT; @Volatile var hotendTarget = 0.0
    @Volatile var bed = AMBIENT; @Volatile var bedTarget = 0.0
    @Volatile var x = 0.0; @Volatile var y = 0.0; @Volatile var z = 0.0; @Volatile var e = 0.0
    @Volatile var homed = false
    @Volatile var halted = false
    @Volatile var bootCount = 0
    @Volatile var linesProcessed = 0L
    /** Commands other than the read-only reports (so idle polling doesn't count). */
    @Volatile var nonReportCommands = 0L
    @Volatile var m85TimeoutS = 0.0
    @Volatile private var inactiveSimS = 0.0
    /** G91 (relative_mode): XYZ and E relative. */
    @Volatile var relativeAll = false
    /** M83 (axis_relative_modes[E]). */
    @Volatile var relativeE = false
    @Volatile var feedrate = 1500.0
    @Volatile var fan = 0
    @Volatile var coldExtrusionsPrevented = 0
    val commandLog: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    /** Test hook: state snapshot taken just BEFORE executing any command starting with one of these prefixes. */
    @Volatile var snapshotPrefixes: Set<String> = emptySet()
    val snapshots: MutableList<Pair<String, Map<String, Any>>> = java.util.Collections.synchronizedList(mutableListOf())
    fun state(): Map<String, Any> = mapOf("x" to x, "y" to y, "z" to z, "e" to e, "fan" to fan, "feedrate" to feedrate,
        "relativeAll" to relativeAll, "relativeE" to relativeE, "hotendTarget" to hotendTarget, "bedTarget" to bedTarget,
        "m85" to m85TimeoutS)
    private var lastN = 0L
    private var numberedCount = 0L
    private var injectedFor = -1L

    init {
        Thread(::commandLoop, "fake-marlin-cmd").apply { isDaemon = true; start() }
        Thread(::physicsLoop, "fake-marlin-physics").apply { isDaemon = true; start() }
    }

    fun attach(s: (String) -> Unit) { sink = s }
    fun detach() { sink = null; input.clear() }

    /** Host -> printer. */
    fun feed(line: String) { if (!halted) input.put(line) }

    private fun emit(s: String) { sink?.invoke(s) }

    fun reboot() {
        input.clear()
        synchronized(this) {
            hotendTarget = 0.0; bedTarget = 0.0; homed = false; halted = false
            lastN = 0; relativeAll = false; relativeE = false; fan = 0; bootCount++; m85TimeoutS = 0.0; inactiveSimS = 0.0
        }
        Thread {
            Thread.sleep(300)
            listOf("start", "echo: External Reset", "Marlin 1.1.6.2 (simulated)",
                "echo: Last Updated: 2018-01-01 | Author: (PrinterBridge FakeMarlin)",
                "Compiled: Jan 1 2018", "echo: Free Memory: 2403  PlannerBufferBytes: 1232",
                "echo:SD init fail").forEach { emit(it); Thread.sleep(40) }
        }.start()
    }

    fun triggerError(kind: String) {
        when (kind) {
            "mintemp" -> { emit("Error:MINTEMP triggered, system stopped! Heater_ID: 0"); emit("Error:Printer halted. kill() called!") }
            else -> { emit("Error:Thermal Runaway, system stopped! Heater_ID: 0"); emit("Error:Printer halted. kill() called!") }
        }
        synchronized(this) { halted = true; hotendTarget = 0.0; bedTarget = 0.0 }
        input.clear()
        Log.w(TAG, "simulated fatal error '$kind': printer halted")
    }

    private fun physicsLoop() {
        var last = System.nanoTime()
        while (true) {
            Thread.sleep(100)
            val now = System.nanoTime()
            val dt = (now - last) / 1e9 * timeScale
            last = now
            hotend = step(hotend, hotendTarget, 2.0 * dt, 0.03 * dt)
            bed = step(bed, bedTarget, 0.7 * dt, 0.01 * dt)
            inactiveSimS += dt
            if (m85TimeoutS > 0 && inactiveSimS > m85TimeoutS && !halted) {
                emit("Error:KILL caused by too much inactive time - current command: M105")
                emit("Error:Printer halted. kill() called!")
                synchronized(this) { halted = true; hotendTarget = 0.0; bedTarget = 0.0 }
                input.clear()
                Log.w(TAG, "M85 inactivity kill after ${"%.1f".format(inactiveSimS)} simulated s (timeout $m85TimeoutS s): printer halted")
            }
        }
    }

    private fun step(t: Double, target: Double, heatRate: Double, coolK: Double): Double {
        val goal = if (target > 0) target else AMBIENT
        val next = if (goal > t) t + min(heatRate, goal - t) else t + (goal - t) * min(1.0, coolK + 0.02)
        return next + (Random.nextDouble() - 0.5) * 0.1
    }

    private fun tempLine(ok: Boolean) = String.format(Locale.US,
        "%sT:%.2f /%.2f B:%.2f /%.2f @:%d B@:%d", if (ok) "ok " else "", hotend, hotendTarget, bed, bedTarget,
        if (hotendTarget > hotend) 127 else 0, if (bedTarget > bed) 127 else 0)

    private fun commandLoop() {
        while (true) {
            val raw = input.take()
            try { process(raw) } catch (t: Throwable) { Log.e(TAG, "fake marlin failed on '$raw'", t) }
        }
    }

    private fun checksum(s: String): Int { var cs = 0; for (c in s) cs = cs xor c.code; return cs and 0xFF }

    private fun process(raw: String) {
        if (halted) return
        var line = raw.trim()
        if (line.isEmpty()) return
        if (line.startsWith("N")) {
            val star = line.lastIndexOf('*')
            val m = Regex("^N(-?\\d+)\\s*(.*)$").find(if (star > 0) line.substring(0, star) else line)
            if (m == null || star < 0) {
                emit("Error:No Checksum with line number, Last Line: $lastN"); emit("Resend: ${lastN + 1}"); emit("ok"); return
            }
            val n = m.groupValues[1].toLong()
            val cmd = m.groupValues[2].trim()
            val given = line.substring(star + 1).trim().toIntOrNull()
            val isM110 = cmd.startsWith("M110")
            numberedCount++
            val inject = injectResendEvery > 0 && numberedCount % injectResendEvery == 0L && injectedFor != n && !isM110
            if (given != checksum(line.substring(0, star)) || inject) {
                if (inject) injectedFor = n
                emit("Error:checksum mismatch, Last Line: $lastN"); emit("Resend: ${lastN + 1}"); emit("ok"); return
            }
            if (!isM110 && n != lastN + 1) {
                emit("Error:Line Number is not Last Line Number+1, Last Line: $lastN"); emit("Resend: ${lastN + 1}"); emit("ok"); return
            }
            lastN = n
            line = cmd
        }
        val code = line.substringBefore(';').trim()
        execute(code)
        if (code.split(' ')[0].uppercase() !in REPORTS) inactiveSimS = 0.0   // long commands (G28) count until they finish
        linesProcessed++
    }

    private fun param(words: List<String>, key: Char): Double? =
        words.firstOrNull { it.length > 1 && it[0].uppercaseChar() == key }?.substring(1)?.toDoubleOrNull()

    private fun execute(cmd: String) {
        if (cmd.isEmpty()) { emit("ok"); return }
        val words = cmd.split(Regex("\\s+"))
        val code = words[0].uppercase()
        if (code !in REPORTS) {
            inactiveSimS = 0.0; nonReportCommands++
            if (snapshotPrefixes.any { cmd.startsWith(it) }) snapshots.add(cmd to state())
            synchronized(commandLog) { commandLog.add(cmd); if (commandLog.size > 5000) commandLog.removeAt(0) }
        }
        when (code) {
            "G0", "G1" -> {
                param(words, 'F')?.let { feedrate = it }
                param(words, 'X')?.let { x = if (relativeAll) x + it else it }
                param(words, 'Y')?.let { y = if (relativeAll) y + it else it }
                param(words, 'Z')?.let { z = if (relativeAll) z + it else it }
                param(words, 'E')?.let { ev ->
                    val newE = if (relativeAll || relativeE) e + ev else ev
                    if (newE != e && hotend < MIN_EXTRUDE_TEMP) { coldExtrusionsPrevented++; emit("echo: cold extrusion prevented") }
                    else e = newE
                }
                sleep(lineDelayMs); emit("ok")
            }
            "G28" -> {
                val end = System.currentTimeMillis() + (3000 / timeScale).toLong()
                while (System.currentTimeMillis() < end) { sleep(min(1000L, end - System.currentTimeMillis())); emit("echo:busy: processing") }
                x = 0.0; y = 0.0; z = 0.0; homed = true
                emit("X:0.00 Y:0.00 Z:0.00 E:${fmt(e)} Count X:0 Y:0 Z:0"); emit("ok")
            }
            "G90" -> { relativeAll = false; emit("ok") }
            "G91" -> { relativeAll = true; emit("ok") }
            "M82" -> { relativeE = false; emit("ok") }
            "M83" -> { relativeE = true; emit("ok") }
            "M106" -> { fan = (param(words, 'S') ?: 255.0).toInt(); emit("ok") }
            "M107" -> { fan = 0; emit("ok") }
            "G92" -> {
                param(words, 'X')?.let { x = it }; param(words, 'Y')?.let { y = it }
                param(words, 'Z')?.let { z = it }; param(words, 'E')?.let { e = it }
                if (words.size == 1) { x = 0.0; y = 0.0; z = 0.0; e = 0.0 }
                emit("ok")
            }
            "M104" -> { hotendTarget = param(words, 'S') ?: 0.0; emit("ok") }
            "M140" -> { bedTarget = param(words, 'S') ?: 0.0; emit("ok") }
            "M109" -> { hotendTarget = param(words, 'S') ?: param(words, 'R') ?: hotendTarget; waitFor { abs(hotend - hotendTarget) < 1.0 || hotendTarget <= 0 }; emit("ok") }
            "M190" -> { bedTarget = param(words, 'S') ?: param(words, 'R') ?: bedTarget; waitFor { bed >= bedTarget - 0.5 || bedTarget <= 0 }; emit("ok") }
            "M85" -> { m85TimeoutS = param(words, 'S') ?: 0.0; inactiveSimS = 0.0; emit("ok") }
            "M110" -> { param(words, 'N')?.let { lastN = it.toLong() }; emit("ok") }
            "M105" -> emit(tempLine(true))
            "M114" -> { emit("X:${fmt(x)} Y:${fmt(y)} Z:${fmt(z)} E:${fmt(e)} Count X:${(x * 80).toLong()} Y:${(y * 80).toLong()} Z:${(z * 400).toLong()}"); emit("ok") }
            "M115" -> {
                emit("FIRMWARE_NAME:Marlin FAKE-SIMULATOR (PrinterBridge FakeMarlin) SOURCE_CODE_URL:none PROTOCOL_VERSION:1.0 MACHINE_TYPE:Ender-3 (simulated) EXTRUDER_COUNT:1 UUID:00000000-0000-0000-0000-000000000000")
                listOf("SERIAL_XON_XOFF:0", "EEPROM:1", "VOLUMETRIC:1", "AUTOREPORT_TEMP:0", "PROGRESS:0", "PRINT_JOB:1",
                    "AUTOLEVEL:0", "Z_PROBE:0", "LEVELING_DATA:0", "SOFTWARE_POWER:0", "TOGGLE_LIGHTS:0").forEach { emit("Cap:$it") }
                emit("ok")
            }
            "M119" -> { emit("Reporting endstop status"); emit("x_min: ${if (x <= 0) "TRIGGERED" else "open"}"); emit("y_min: ${if (y <= 0) "TRIGGERED" else "open"}"); emit("z_min: ${if (z <= 0) "TRIGGERED" else "open"}"); emit("ok") }
            "M503" -> { listOf("echo:  G21    ; Units in mm", "echo:  M149 C ; Units in Celsius", "echo:Steps per unit:", "echo:  M92 X80.00 Y80.00 Z400.00 E93.00").forEach(::emit); emit("ok") }
            "M84", "M18", "M73", "M117", "M220", "M221", "M204", "M205", "M201", "M203", "M400", "M500", "M413", "G4", "G21" -> { if (code == "G4") sleep(((param(words, 'P') ?: 0.0) / timeScale).toLong()); emit("ok") }
            else -> { emit("echo:Unknown command: \"$cmd\""); emit("ok") }
        }
    }

    private fun waitFor(done: () -> Boolean) {
        var lastReport = 0L
        while (!done() && !halted) {
            inactiveSimS = 0.0     // Marlin refreshes the inactivity timer while waiting for heaters
            val now = System.currentTimeMillis()
            if (now - lastReport >= 1000) {
                lastReport = now
                emit(String.format(Locale.US, " T:%.2f /%.2f B:%.2f /%.2f @:0 B@:0 W:?", hotend, hotendTarget, bed, bedTarget))
            }
            sleep(100)
        }
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun sleep(ms: Long) { if (ms > 0) Thread.sleep(ms) }

    companion object {
        const val TAG = "PrinterBridge.fake"
        const val AMBIENT = 24.0
        val REPORTS = setOf("M105", "M114", "M115", "M119", "M503")
        const val MIN_EXTRUDE_TEMP = 170.0

        /** The one simulated machine, shared by every fake connection (it outlives connections). */
        val shared: FakeMarlin by lazy { FakeMarlin() }
    }
}

/** Fake backend: talks straight to the shared [FakeMarlin], no USB, no Python. */
class FakePrinterConnection(private val marlin: FakeMarlin = FakeMarlin.shared) : PrinterConnection {
    override val kind = "fake"
    @Volatile override var isOpen = false; private set
    @Volatile private var simulatedDisconnect = false
    private val out = LinkedBlockingQueue<String>()
    private var openedAt = 0L

    override fun open() {
        simulatedDisconnect = false
        out.clear()
        marlin.attach { out.put(it) }
        isOpen = true
        openedAt = System.currentTimeMillis()
    }

    override fun close() { isOpen = false; marlin.detach() }

    override fun writeLine(line: String) {
        if (simulatedDisconnect) throw java.io.IOException("USB device lost (simulated disconnect)")
        if (!isOpen) throw java.io.IOException("not open")
        marlin.feed(line)
    }

    override fun readLine(timeoutMs: Long): String? {
        if (simulatedDisconnect) throw java.io.IOException("USB device lost (simulated disconnect)")
        return out.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun resetBoard() { marlin.reboot() }

    /** Simulates the USB cable being pulled: every later read/write throws. */
    fun simulateDisconnect() { simulatedDisconnect = true }

    override fun info(): Map<String, Any?> = mapOf(
        "backend" to kind, "open" to isOpen, "opened_at" to openedAt,
        "sim_line_delay_ms" to marlin.lineDelayMs, "sim_time_scale" to marlin.timeScale,
        "sim_inject_resend_every" to marlin.injectResendEvery, "sim_boot_count" to marlin.bootCount,
        "sim_halted" to marlin.halted, "sim_lines_processed" to marlin.linesProcessed,
        "sim_m85_timeout_s" to marlin.m85TimeoutS, "sim_non_report_commands" to marlin.nonReportCommands,
        "sim_x" to marlin.x, "sim_y" to marlin.y, "sim_z" to marlin.z, "sim_e" to marlin.e,
        "sim_fan" to marlin.fan, "sim_feedrate" to marlin.feedrate, "sim_relative_xyz" to marlin.relativeAll,
        "sim_relative_e" to marlin.relativeE, "sim_homed" to marlin.homed, "sim_hotend_target" to marlin.hotendTarget,
        "sim_bed_target" to marlin.bedTarget)

    override fun allowsMotion() = true
}
