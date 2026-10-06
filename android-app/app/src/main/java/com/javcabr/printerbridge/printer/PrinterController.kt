package com.javcabr.printerbridge.printer

import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * PAUSING  = pause requested: no further job lines are sent; first the in-flight
 *            command (e.g. a firmware M190/M109 heat wait the host cannot interrupt)
 *            completes, then the park sequence runs (pause.stage "waiting_in_flight"
 *            -> "parking"); PAUSED once the park move has finished (M400).
 * RESUMING = resume requested: reheat waits (M109/M190, in flight) and the restore
 *            sequence run; PRINTING only when streaming of the next job line resumes.
 */
enum class JobState { QUEUED, PRINTING, PAUSING, PAUSED, RESUMING, DONE, CANCELLED, ERROR;
    val wire get() = name.lowercase()
    val active get() = this == QUEUED || this == PRINTING || this == PAUSING || this == PAUSED || this == RESUMING
}

/** Pause/park behaviour (see docs/DESIGN.md "Pause design"). */
data class PauseSettings(
    val parkEnabled: Boolean = true,
    val parkX: Double = 10.0,          // Marlin NOZZLE_PARK_POINT default: X_MIN+10
    val parkY: Double = 210.0,         //   Y_MAX-10 (Ender 3: bed pulled toward the user)
    val zRaiseMm: Double = 10.0,
    val retractMm: Double = 5.0,
    val extraPurgeMm: Double = 1.5,
    val nozzleStandbyS: Int = 300,     // <= 0: never cool the nozzle while paused
    val zMaxMm: Double = 250.0,        // Ender 3
    val bedMaxX: Double = 220.0,
    val bedMaxY: Double = 220.0,
)

class PrinterHaltedException(msg: String) : IOException(msg)
class BusyException(msg: String) : IllegalStateException(msg)

data class ControllerSettings(
    val idleTempPoll: Boolean = true,
    val idlePollMs: Long = 2000,
    val printTempPollMs: Long = 3000,
    val idleShutdownS: Int = 300,      // M85 at print start: firmware heater cutoff if the host goes away
    val silenceMs: Long = 30_000,      // no bytes for this long while waiting for ok -> poke with M105
    val maxPokes: Int = 3,
    val notifyOnCancel: Boolean = false,
    val pause: PauseSettings = PauseSettings(),
)

interface ControllerEvents {
    /** Job reached DONE, CANCELLED or ERROR. */
    fun onJobFinished(job: JSONObject)
    /** Fatal printer error or lost connection while no job was running. */
    fun onPrinterError(message: String)
    fun onStateChanged() {}
}

/**
 * Owns the active [PrinterConnection] and all print-job state. Every
 * connection access happens on ONE worker thread ("printer-io"): API calls
 * post tasks to it and wait for the result, and between tasks it streams the
 * active job one line at a time (send, wait for "ok"), with Marlin line
 * numbers + checksums and Resend handling, polling M105 in between.
 */
class PrinterController(
    private val historyFile: File,
    private val gcodeDir: File,
    private val events: ControllerEvents,
    @Volatile var settings: ControllerSettings = ControllerSettings(),
) {
    private class Task(val name: String, val body: () -> Any?) { val result = CompletableFuture<Any?>() }

    private val tasks = LinkedBlockingQueue<Task>()
    @Volatile private var conn: PrinterConnection? = null
    @Volatile var connState = "disconnected"; private set
    @Volatile var connError: String? = null; private set
    @Volatile var firmware: String? = null; private set
    @Volatile var machineType: String? = null; private set
    @Volatile var halted = false; private set

    // temperatures / position
    @Volatile var hotend: Double? = null; @Volatile var hotendTarget: Double? = null
    @Volatile var bed: Double? = null; @Volatile var bedTarget: Double? = null
    @Volatile var tempsAt = 0L
    @Volatile var pos: DoubleArray? = null; @Volatile var posAt = 0L
    private val tempHistory = ArrayDeque<DoubleArray>()   // [t_ms, h, ht, b, bt]

    // job
    private inner class Job(val file: String, val lines: List<String>) {
        val id: String = UUID.randomUUID().toString().substring(0, 8)
        @Volatile var state = JobState.QUEUED
        var queuedAt = System.currentTimeMillis()
        var startedAt = 0L; var endedAt = 0L
        var startMono = 0L; var pausedMs = 0L; var pausedSince = 0L
        @Volatile var done = 0
        var slicerRemainingMin: Int? = null
        var resends = 0
        var error: String? = null
        @Volatile var cancelRequested = false
        @Volatile var inFlight: String? = null      // command written, "ok" not yet received
        @Volatile var inFlightSince = 0L
        @Volatile var pause: PauseInfo? = null       // current (or last) pause
        @Volatile var pauseRequested = false         // pause asked for while RESUMING
        @Volatile var resumeRequested = false        // resume asked for while the park is running
        @Volatile var pauses = 0
        val backend = conn?.kind
    }

    /** What a pause did and saved, for the restore and for /api/status. */
    private class PauseInfo {
        @Volatile var stage = "waiting_in_flight"
        val requestedAt = System.currentTimeMillis()
        var parked = false
        var parkSkipped: String? = null
        var retractedMm = 0.0
        @Volatile var nozzleCooled = false
        var standbyAt = 0L                           // elapsedRealtime deadline for the nozzle standby cooldown
        var x: Double? = null; var y: Double? = null; var z: Double? = null; var e: Double? = null
        var hotendTarget = 0.0; var bedTarget = 0.0
        var fan = 0; var feedrate: Double? = null
        var relativeXyz = false; var relativeE = false
        var reheated = false
        fun json(): JSONObject {
            fun n(v: Any?) = v ?: JSONObject.NULL
            return JSONObject().put("stage", stage).put("requested_at", requestedAt).put("parked", parked)
                .put("park_skipped", n(parkSkipped)).put("retracted_mm", retractedMm).put("nozzle_cooled", nozzleCooled)
                .put("nozzle_standby_in_s", if (standbyAt > 0 && !nozzleCooled) maxOf(0L, (standbyAt - SystemClock.elapsedRealtime()) / 1000) else JSONObject.NULL)
                .put("reheated", reheated)
                .put("saved", JSONObject().put("x", n(x)).put("y", n(y)).put("z", n(z)).put("e", n(e))
                    .put("hotend_target", hotendTarget).put("bed_target", bedTarget).put("fan", fan)
                    .put("feedrate", n(feedrate)).put("relative_xyz", relativeXyz).put("relative_e", relativeE))
        }
    }
    private class Abort : RuntimeException()
    @Volatile private var job: Job? = null
    private val history = mutableListOf<JSONObject>()

    // protocol
    private var lineNo = 0L
    private val sent = HashMap<Long, String>()
    private var lastPoll = 0L
    private var pollCount = 0L
    @Volatile var linesSentTotal = 0L; private set
    /** True after we sent M85 S<n> (n>0): Marlin 1.1.6 kill()s after n s without a (non-M105) command. */
    @Volatile var idleKillArmed = false; private set

    // Modal state tracked from every command we send (job lines included), needed to restore after a pause.
    @Volatile var relativeXyz = false; private set      // G91 (Marlin 1.1: also makes E relative)
    @Volatile var relativeE = false; private set        // M83
    @Volatile var feedrate: Double? = null; private set // last F on G0/G1
    @Volatile var fanSpeed = 0; private set             // M106 S / M107
    /** A full G28 completed on this connection, so M114 is a real machine position. */
    @Volatile var homed = false; private set
    @Volatile var lastLineRx: String? = null; private set
    private val log = ArrayDeque<String>()

    init {
        loadHistory()
        Thread(::workerLoop, "printer-io").apply { isDaemon = true; start() }
    }

    // ------------------------------------------------------------------ public API

    fun connection(): PrinterConnection? = conn

    /** Current job state without building JSON (safe to call from connection callbacks). */
    val currentJobState: String? get() = job?.state?.wire

    /** Replace the connection (closing the old one, never resetting anything) and open [c]. */
    fun connect(c: PrinterConnection): JSONObject = call("connect", 30_000) {
        if (job?.state?.active == true) throw BusyException("a job is active (${job?.state?.wire}); cancel it first")
        closeConn()
        connState = "connecting"; connError = null; halted = false; homed = false
        conn = c
        try {
            c.open()
            connState = "connected"
            say("connected (${c.kind})")
            // Firmware identity (M115, read-only). Also the only command sent on connect.
            val reply = sendAndWait("M115", numbered = false, poke = false, maxWaitMs = 10_000)
            reply.firstOrNull { it.contains("FIRMWARE_NAME:") }?.let { l ->
                firmware = l.substringAfter("FIRMWARE_NAME:").substringBefore(" SOURCE_CODE_URL").trim()
                machineType = Regex("MACHINE_TYPE:(.*?) EXTRUDER_COUNT").find(l)?.groupValues?.get(1)
            }
            Log.i(TAG, "M115 reply (${reply.size} lines): ${reply.joinToString(" | ")}")
            say("firmware=$firmware machine=$machineType")
            lastPoll = 0
            // A job that lost its link earlier may have left the board's M85 timer running
            // (reconnecting doesn't reset the board). Only sends anything if we armed it.
            disarmIdleKill("reconnect")
        } catch (e: Exception) {
            connState = "error"; connError = e.message
            try { c.close() } catch (_: Throwable) {}
            conn = null
            throw e
        }
        connectionJson()
    } as JSONObject

    fun disconnect(): JSONObject = call("disconnect", 10_000) {
        if (job?.state?.active == true) throw BusyException("a job is active (${job?.state?.wire}); cancel it first")
        disarmIdleKill("disconnect")
        closeConn(); connState = "disconnected"; connectionJson()
    } as JSONObject

    /** Called from the USB_DEVICE_DETACHED receiver: the link is gone, whatever it was doing. */
    fun onDeviceDetached(why: String) {
        tasks.put(Task("detached") { connectionLost(IOException(why)) })
    }

    fun startPrint(fileName: String): JSONObject = call("start_print", 10_000) {
        val c = conn
        if (c == null || !c.isOpen) throw IllegalStateException("printer not connected")
        if (halted) throw IllegalStateException("printer is halted (reset/power-cycle it first)")
        if (job?.state?.active == true) throw BusyException("a job is already ${job?.state?.wire}: ${job?.file}")
        val f = safeFile(fileName)
        if (!f.isFile) throw IllegalArgumentException("no such file: $fileName")
        val lines = f.bufferedReader().useLines { seq -> seq.map { it.substringBefore(';').trim() }.filter { it.isNotEmpty() }.toList() }
        if (lines.isEmpty()) throw IllegalArgumentException("file has no G-code commands: $fileName")
        val j = Job(fileName, lines)
        job = j
        say("job ${j.id} queued: $fileName (${lines.size} commands)")
        jobJson(j)
    } as JSONObject

    /**
     * Pause/resume/cancel do NOT go through the worker queue: the worker may be blocked
     * for minutes inside a firmware heat wait (M190/M109) that the host can't interrupt
     * (no EMERGENCY_PARSER on this Marlin). They flip job flags under the job's lock and
     * return at once with the truthful state: "paused" if no command is in flight (the
     * worker is between lines), else "pausing" plus "in_flight" = the command it waits on.
     */
    fun pause(): JSONObject {
        val j = job ?: throw IllegalStateException("no job")
        synchronized(j) {
            when (j.state) {
                JobState.PAUSED, JobState.PAUSING -> return jobJson(j)
                JobState.PRINTING -> {
                    j.state = JobState.PAUSING; j.pause = PauseInfo(); j.pauses++
                    say("job ${j.id} pause requested at ${j.done}/${j.lines.size}" + (j.inFlight?.let { " (waiting for in-flight '$it')" } ?: ""))
                }
                JobState.RESUMING -> { j.pauseRequested = true; say("job ${j.id} pause requested while resuming (takes effect after the in-flight command)") }
                else -> throw IllegalStateException("job is ${j.state.wire}, not printing")
            }
        }
        // Give a short in-flight line + the park a moment so the reply can already say "paused".
        settle(j) { it == JobState.PAUSING }
        events.onStateChanged()
        return jobJson(j)
    }

    fun resume(): JSONObject {
        val j = job ?: throw IllegalStateException("no job")
        synchronized(j) {
            when (j.state) {
                JobState.PAUSING ->
                    if (j.pause?.stage == "waiting_in_flight") {
                        j.state = JobState.PRINTING; j.pause = null; say("job ${j.id} pause withdrawn before it took effect")
                    } else { j.resumeRequested = true; say("job ${j.id} resume requested while parking (runs after the park completes)") }
                JobState.PAUSED -> { j.state = JobState.RESUMING; j.pause?.stage = "resuming"; say("job ${j.id} resume requested") }
                JobState.RESUMING -> if (j.pauseRequested) { j.pauseRequested = false; say("job ${j.id} pending pause withdrawn") }
                else -> throw IllegalStateException("job is ${j.state.wire}, not paused")
            }
        }
        settle(j) { it == JobState.RESUMING || it == JobState.PAUSING }
        events.onStateChanged()
        return jobJson(j)
    }

    private fun settle(j: Job, transient: (JobState) -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 1500
        while (transient(j.state) && SystemClock.elapsedRealtime() < end) Thread.sleep(20)
    }

    /**
     * Returns immediately with "cancel_requested": true. The worker then (after any
     * in-flight command, e.g. a heat wait, completes) disarms M85 and sends heaters off,
     * fan off, steppers off, and the job becomes "cancelled".
     */
    fun cancel(): JSONObject {
        val j = job ?: throw IllegalStateException("no job")
        if (!j.state.active) throw IllegalStateException("job is already ${j.state.wire}")
        j.cancelRequested = true
        say("job ${j.id} cancel requested" + (j.inFlight?.let { " (takes effect after in-flight '$it' completes)" } ?: ""))
        events.onStateChanged()
        return jobJson(j)
    }

    /** G28, then M114. Refused while a job is active. */
    fun home(): JSONObject {
        refuseIfJobActive()   // fast 409 even while the worker is busy inside a heat wait / park
        return homeTask()
    }

    private fun homeTask(): JSONObject = call("home", 120_000) {
        requireIdleConnected()
        val r = sendAndWait("G28", numbered = false)
        sendAndWait("M114", numbered = false)
        JSONObject().put("ok", true).put("reply", JSONArray(r)).put("position", posJson())
    } as JSONObject

    /** Fresh M105 if connected (else last known values). */
    fun checkTemps(): JSONObject {
        val c = conn
        if (c != null && c.isOpen && !halted) {
            try { call("check_temps", 10_000) { sendAndWait("M105", numbered = false) } } catch (e: Exception) { Log.w(TAG, "check_temps: $e") }
        }
        return tempsJson()
    }

    /** Send one ad-hoc command and return the reply lines. While a job is active, only M105/M114/M115/M119/M503. */
    fun sendGcode(cmd: String): JSONObject {
        if (RealPrinterConnection.commandWord(cmd) !in RealPrinterConnection.READ_ONLY) refuseIfJobActive(gcode = true)
        return gcodeTask(cmd)
    }

    private fun gcodeTask(cmd: String): JSONObject = call("gcode", 120_000) {
        val c = conn ?: throw IllegalStateException("printer not connected")
        if (!c.isOpen) throw IllegalStateException("printer not connected")
        val j = job
        // Any active job state (queued/printing/pausing/paused/resuming) owns the machine: a paused
        // job is parked and will move back to its saved position on resume, so manual motion or
        // heater changes in between would ruin the print or crash the head.
        if (j != null && j.state.active && RealPrinterConnection.commandWord(cmd) !in RealPrinterConnection.READ_ONLY)
            throw BusyException("refused: a print job is ${j.state.wire}; only read-only commands " +
                "(${RealPrinterConnection.READ_ONLY.joinToString()}) are allowed until it is done or cancelled")
        JSONObject().put("command", cmd).put("reply", JSONArray(sendAndWait(cmd.trim(), numbered = false)))
    } as JSONObject

    /** Deliberate board reboot (DTR pulse on real hardware). Never called implicitly. */
    fun resetBoard(): JSONObject {
        refuseIfJobActive()
        return resetTask()
    }

    private fun resetTask(): JSONObject = call("reset_board", 10_000) {
        requireIdleConnected()
        val c = conn!!
        // Discard anything already queued from before the reboot (e.g. the rest of a
        // kill() message), so it can't re-latch "halted" afterwards.
        var drained = 0
        while (c.readLine(150) != null && drained < 500) drained++
        c.resetBoard(); halted = false; lineNo = 0; idleKillArmed = false; homed = false
        relativeXyz = false; relativeE = false; fanSpeed = 0
        JSONObject().put("ok", true).put("note", "board reboot requested; wait for the boot banner to go quiet before printing")
    } as JSONObject

    fun history(limit: Int = 50): JSONArray = synchronized(history) {
        JSONArray(history.takeLast(limit).reversed())
    }

    fun listFiles(): JSONArray = JSONArray((gcodeDir.listFiles() ?: emptyArray()).filter { it.isFile }.sortedBy { it.name }.map {
        JSONObject().put("name", it.name).put("bytes", it.length()).put("modified", it.lastModified())
    })

    fun safeFile(name: String): File {
        require(name.isNotBlank() && !name.contains('/') && !name.contains("..") && name.length < 200) { "bad file name: $name" }
        return File(gcodeDir, name)
    }

    // ------------------------------------------------------------------ JSON views

    fun statusJson(): JSONObject = JSONObject()
        .put("connection", connectionJson())
        .put("temps", tempsJson())
        .put("position", posJson())
        .put("job", job?.let { jobJson(it) } ?: JSONObject.NULL)
        .put("server_time", System.currentTimeMillis())

    fun connectionJson(): JSONObject {
        val o = JSONObject().put("state", connState).put("backend", conn?.kind ?: JSONObject.NULL)
            .put("error", connError ?: JSONObject.NULL).put("firmware", firmware ?: JSONObject.NULL)
            .put("machine_type", machineType ?: JSONObject.NULL).put("halted", halted)
            .put("lines_sent_total", linesSentTotal).put("last_rx", lastLineRx ?: JSONObject.NULL)
        conn?.info()?.let { info -> o.put("details", JSONObject(info.mapValues { it.value ?: JSONObject.NULL })) }
        return o
    }

    fun tempsJson(): JSONObject = JSONObject()
        .put("hotend", hotend ?: JSONObject.NULL).put("hotend_target", hotendTarget ?: JSONObject.NULL)
        .put("bed", bed ?: JSONObject.NULL).put("bed_target", bedTarget ?: JSONObject.NULL)
        .put("updated_at", if (tempsAt > 0) tempsAt else JSONObject.NULL)

    fun tempHistoryJson(): JSONArray = synchronized(tempHistory) { JSONArray(tempHistory.map { JSONArray(it.toList()) }) }

    fun posJson(): JSONObject {
        val p = pos ?: return JSONObject().put("known", false)
        return JSONObject().put("known", true).put("x", p[0]).put("y", p[1]).put("z", p[2]).put("e", p[3]).put("updated_at", posAt)
    }

    fun recentLog(): JSONArray = synchronized(log) { JSONArray(log.toList()) }

    private fun jobJson(j: Job): JSONObject {
        val total = j.lines.size
        val elapsedMs = when {
            j.startMono == 0L -> 0L
            j.state.active && j.pausedSince > 0 -> j.pausedSince - j.startMono - j.pausedMs
            j.state.active -> SystemClock.elapsedRealtime() - j.startMono - j.pausedMs
            else -> j.endedAt - j.startedAt - j.pausedMs
        }
        val pct = if (total == 0) 0.0 else 100.0 * j.done / total
        var remainingSource = "none"
        val remaining: Long? = when {
            !j.state.active -> 0L
            j.slicerRemainingMin != null -> { remainingSource = "slicer_m73"; j.slicerRemainingMin!! * 60L }
            j.done > 0 -> { remainingSource = "linear_estimate"; (elapsedMs / 1000.0 * (total - j.done) / j.done).toLong() }
            else -> null
        }
        return JSONObject().put("id", j.id).put("file", j.file).put("state", j.state.wire)
            .put("progress_pct", Math.round(pct * 10) / 10.0).put("lines_done", j.done).put("lines_total", total)
            .put("queued_at", j.queuedAt).put("started_at", if (j.startedAt > 0) j.startedAt else JSONObject.NULL)
            .put("ended_at", if (j.endedAt > 0) j.endedAt else JSONObject.NULL)
            .put("elapsed_s", elapsedMs / 1000).put("remaining_s", remaining ?: JSONObject.NULL)
            .put("remaining_source", remainingSource).put("resends", j.resends)
            .put("backend", j.backend ?: JSONObject.NULL)
            .put("error", j.error ?: JSONObject.NULL)
            .put("cancel_requested", j.cancelRequested)
            .put("in_flight", j.inFlight ?: JSONObject.NULL)
            .put("in_flight_s", if (j.inFlight != null) (SystemClock.elapsedRealtime() - j.inFlightSince) / 1000 else JSONObject.NULL)
            .put("pause", j.pause?.json() ?: JSONObject.NULL)
            .put("pause_requested", j.pauseRequested).put("resume_requested", j.resumeRequested)
            .put("pauses", j.pauses)
    }

    // ------------------------------------------------------------------ worker

    private fun call(name: String, timeoutMs: Long, body: () -> Any?): Any? {
        val t = Task(name, body)
        tasks.put(t)
        try {
            return t.result.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } catch (e: TimeoutException) {
            throw IllegalStateException("$name timed out after ${timeoutMs}ms")
        }
    }

    private fun runTask(t: Task) {
        try { t.result.complete(t.body()) } catch (e: Throwable) {
            when (e) {
                is SafetyLockException -> say("safety lock: ${e.message}")
                is PrinterHaltedException -> fatal(e.message ?: "printer halted")
                is IOException -> if (conn?.isOpen == true) connectionLost(e)
            }
            t.result.completeExceptionally(e)
        }
        events.onStateChanged()
    }

    private fun workerLoop() {
        while (true) {
            try {
                val pendingTask = tasks.poll()
                if (pendingTask != null) { runTask(pendingTask); continue }
                val c = conn
                val j = job
                if (c == null || !c.isOpen) { tasks.poll(200, TimeUnit.MILLISECONDS)?.let(::runTask); continue }
                when {
                    j != null && j.state.active && j.cancelRequested -> finishCancel(j)
                    j != null && j.state == JobState.QUEUED -> beginJob(j)
                    j != null && j.state == JobState.PRINTING -> { streamOne(j); pollTempsIfDue(settings.printTempPollMs) }
                    // Reached only between commands, i.e. once the in-flight one has completed.
                    j != null && j.state == JobState.PAUSING -> doPause(j)
                    j != null && j.state == JobState.RESUMING -> doResume(j)
                    else -> {
                        if (j != null && j.state == JobState.PAUSED) pausedTick(j)
                        if (settings.idleTempPoll && !halted) pollTempsIfDue(settings.idlePollMs)
                        val t = tasks.poll(50, TimeUnit.MILLISECONDS)
                        if (t != null) runTask(t) else c.readLine(50)?.let { handleLine(it) }
                    }
                }
            } catch (e: PrinterHaltedException) {
                fatal(e.message ?: "printer halted")
            } catch (e: SafetyLockException) {
                job?.takeIf { it.state.active }?.let { finish(it, JobState.ERROR, e.message) }
                say("safety lock: ${e.message}")
            } catch (e: IOException) {
                connectionLost(e)
            } catch (e: Throwable) {
                Log.e(TAG, "worker error", e)
                job?.takeIf { it.state.active }?.let { finish(it, JobState.ERROR, "internal error: $e") }
                Thread.sleep(200)
            }
        }
    }

    private fun beginJob(j: Job) {
        lineNo = 0; sent.clear()
        sendAndWait("M110 N0", numbered = false)
        if (settings.idleShutdownS > 0) {
            sendAndWait("M85 S${settings.idleShutdownS}", numbered = false)
            idleKillArmed = true
        }
        j.startedAt = System.currentTimeMillis(); j.startMono = SystemClock.elapsedRealtime()
        j.state = JobState.PRINTING
        say("job ${j.id} printing: ${j.file}")
        events.onStateChanged()
    }

    private fun streamOne(j: Job) {
        val cmd = j.lines[j.done]
        Regex("^M73\\s.*R(\\d+)").find(cmd)?.let { j.slicerRemainingMin = it.groupValues[1].toInt() }
        j.inFlight = cmd; j.inFlightSince = SystemClock.elapsedRealtime()
        try {
            j.resends += sendAndWait(cmd, numbered = true).count { it.startsWith("Resend") || it.startsWith("rs ") }
        } finally { j.inFlight = null }
        j.done++
        if (j.done >= j.lines.size) {
            finish(j, JobState.DONE, null)
        } else if (j.done % 200 == 0) {
            Log.i(TAG, "job ${j.id} progress ${j.done}/${j.lines.size} (%.1f%%)".format(100.0 * j.done / j.lines.size))
        }
    }

    // ------------------------------------------------------------------ pause / resume sequences

    private fun fmtN(v: Double) = String.format(java.util.Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

    /** One command of a pause/resume sequence: aborts if a cancel arrived, shows up as in_flight. */
    private fun step(j: Job, cmd: String): List<String> {
        if (j.cancelRequested) throw Abort()
        j.inFlight = cmd; j.inFlightSince = SystemClock.elapsedRealtime()
        try { return sendAndWait(cmd, numbered = false) } finally { j.inFlight = null }
    }

    /** Non-essential command: false if the safety lock refused it. */
    private fun trySend(cmd: String): Boolean = try { sendAndWait(cmd, numbered = false); true } catch (e: SafetyLockException) { say("'$cmd' refused by safety lock"); false }

    /**
     * PAUSE (worker; the in-flight job line has completed):
     *  1. M85 S0 (a long pause must not trip the inactivity kill)
     *  2. save heater targets, fan, feedrate, G90/G91 + M82/M83
     *  3. if parking is enabled, motion is allowed (safety lock OFF) and the position is
     *     known (full G28 on this connection + fresh M114 after M400):
     *     retract (only if the nozzle is >= 170 C), raise Z (clamped to Z max), park XY, fan off, M400
     *     else: no motion at all, park_skipped says why
     *  4. PAUSED; bed stays on; the nozzle target is dropped to 0 after nozzleStandbyS (pausedTick)
     */
    private fun doPause(j: Job) {
        val p = synchronized(j) {
            if (j.state != JobState.PAUSING) return
            (j.pause ?: PauseInfo().also { j.pause = it }).also { it.stage = "parking" }
        }
        if (j.pausedSince == 0L) j.pausedSince = SystemClock.elapsedRealtime()
        say("job ${j.id} pausing at ${j.done}/${j.lines.size}")
        try {
            disarmIdleKill("pause")
            park(j, p)
        } catch (e: Abort) { return }
        synchronized(j) {
            if (j.state != JobState.PAUSING) return
            j.state = JobState.PAUSED
            p.stage = if (p.parked) "parked" else "paused_unparked"
            val ps = settings.pause
            if (ps.nozzleStandbyS > 0 && p.hotendTarget > 0) p.standbyAt = SystemClock.elapsedRealtime() + ps.nozzleStandbyS * 1000L
            say("job ${j.id} paused at ${j.done}/${j.lines.size}: " + (if (p.parked) "parked" else "not parked (${p.parkSkipped})"))
            if (j.resumeRequested) { j.resumeRequested = false; j.state = JobState.RESUMING; p.stage = "resuming" }
        }
        events.onStateChanged()
    }

    private fun park(j: Job, p: PauseInfo) {
        p.hotendTarget = hotendTarget ?: 0.0; p.bedTarget = bedTarget ?: 0.0
        p.fan = fanSpeed; p.feedrate = feedrate; p.relativeXyz = relativeXyz; p.relativeE = relativeE
        val ps = settings.pause
        val c = conn ?: return
        p.parkSkipped = when {
            !ps.parkEnabled -> "parking disabled in settings"
            !c.allowsMotion() -> "real-printer safety lock is ON (read-only): no parking motion"
            !homed -> "position unknown: no full G28 on this connection"
            else -> null
        }
        if (p.parkSkipped != null) return
        step(j, "M400")
        val before = posAt
        step(j, "M114")
        val q = pos
        if (q == null || posAt == before) { p.parkSkipped = "position unknown: no M114 position reply"; return }
        p.x = q[0]; p.y = q[1]; p.z = q[2]; p.e = q[3]
        if (ps.retractMm > 0 && (hotend ?: 0.0) >= MIN_EXTRUDE_C) {
            step(j, "M83"); step(j, "G1 E-${fmtN(ps.retractMm)} F1800"); p.retractedMm = ps.retractMm
        }
        step(j, "G90")
        val zPark = minOf(q[2] + ps.zRaiseMm, ps.zMaxMm)
        if (zPark > q[2]) step(j, "G1 Z${fmtN(zPark)} F600")
        val px = ps.parkX.coerceIn(0.0, ps.bedMaxX); val py = ps.parkY.coerceIn(0.0, ps.bedMaxY)
        step(j, "G1 X${fmtN(px)} Y${fmtN(py)} F6000")
        step(j, "M107")
        step(j, "M400")
        step(j, "M114")
        p.parked = true
    }

    /** While PAUSED (worker otherwise idle): nozzle standby cooldown once its deadline passes. */
    private fun pausedTick(j: Job) {
        val p = j.pause ?: return
        if (p.nozzleCooled || p.standbyAt == 0L || SystemClock.elapsedRealtime() < p.standbyAt) return
        if (trySend("M104 S0")) { p.nozzleCooled = true; say("job ${j.id} paused > ${settings.pause.nozzleStandbyS}s: nozzle standby cooldown (bed stays at ${fmtN(p.bedTarget)})"); events.onStateChanged() }
        else p.standbyAt = 0
    }

    /**
     * RESUME (worker): reheat (M104+M140, then M190/M109 waits, only if needed); a pause
     * requested during the reheat aborts back to PAUSED. Then, if parked: unretract +
     * extra purge, travel back at park Z, lower to the saved Z, G92 E<saved E>, restore
     * G90/G91 + M82/M83, feedrate and fan; re-arm M85; PRINTING (next job line).
     */
    private fun doResume(j: Job) {
        val p = j.pause
        if (p == null) { synchronized(j) { if (j.state == JobState.RESUMING) j.state = JobState.PRINTING }; return }
        fun pauseAgain(): Boolean = synchronized(j) {
            if (!j.pauseRequested) return@synchronized false
            j.pauseRequested = false; j.state = JobState.PAUSED
            p.stage = if (p.parked) "parked" else "paused_unparked"; p.nozzleCooled = false
            val ps = settings.pause
            p.standbyAt = if (ps.nozzleStandbyS > 0 && p.hotendTarget > 0) SystemClock.elapsedRealtime() + ps.nozzleStandbyS * 1000L else 0
            say("job ${j.id} paused again during reheat"); true
        }
        try {
            p.stage = "reheating"
            step(j, "M105")   // decide on fresh temperatures, not a possibly stale poll
            val needNozzle = p.hotendTarget > 0 && (p.nozzleCooled || (hotendTarget ?: 0.0) < p.hotendTarget - 0.5 || (hotend ?: 0.0) < p.hotendTarget - 5)
            val needBed = p.bedTarget > 0 && ((bedTarget ?: 0.0) < p.bedTarget - 0.5 || (bed ?: 0.0) < p.bedTarget - 3)
            if (needNozzle) step(j, "M104 S${fmtN(p.hotendTarget)}")
            if (needBed) { step(j, "M140 S${fmtN(p.bedTarget)}"); step(j, "M190 S${fmtN(p.bedTarget)}"); if (pauseAgain()) return }
            if (needNozzle) { step(j, "M109 S${fmtN(p.hotendTarget)}"); p.reheated = true; p.nozzleCooled = false; if (pauseAgain()) return }
            if (pauseAgain()) return
            p.stage = "restoring"
            if (p.parked) {
                val ps = settings.pause
                if (p.retractedMm > 0) {
                    step(j, "M83")
                    step(j, "G1 E${fmtN(p.retractedMm)} F1800")
                    if (ps.extraPurgeMm > 0) step(j, "G1 E${fmtN(ps.extraPurgeMm)} F300")
                }
                step(j, "G90")
                step(j, "G1 X${fmtN(p.x!!)} Y${fmtN(p.y!!)} F6000")
                step(j, "G1 Z${fmtN(p.z!!)} F600")
                step(j, "G92 E${String.format(java.util.Locale.US, "%.5f", p.e!!)}")
                step(j, if (p.relativeE) "M83" else "M82")
                if (p.relativeXyz) step(j, "G91")
                p.feedrate?.let { step(j, "G1 F${fmtN(it)}") }
                step(j, if (p.fan > 0) "M106 S${p.fan}" else "M107")
            }
            if (settings.idleShutdownS > 0 && trySend("M85 S${settings.idleShutdownS}")) idleKillArmed = true
        } catch (e: Abort) { return }
        synchronized(j) {
            if (j.state != JobState.RESUMING) return
            if (j.pausedSince > 0) { j.pausedMs += SystemClock.elapsedRealtime() - j.pausedSince; j.pausedSince = 0 }
            p.stage = "resumed"
            j.state = JobState.PRINTING
            say("job ${j.id} resumed at line ${j.done + 1}/${j.lines.size}")
            if (j.pauseRequested) {
                j.pauseRequested = false; j.state = JobState.PAUSING; j.pause = PauseInfo(); j.pauses++
                say("job ${j.id} pausing again (requested during resume)")
            }
        }
        events.onStateChanged()
    }

    private fun finishCancel(j: Job) {
        say("job ${j.id} cancelling at ${j.done}/${j.lines.size}: idle-kill timer off, heaters off, fan off, steppers off")
        disarmIdleKill("cancel")
        for (c in listOf("M104 S0", "M140 S0", "M107", "M84")) {
            try { sendAndWait(c, numbered = false) } catch (e: SafetyLockException) { say("cancel: $c refused by safety lock") }
        }
        finish(j, JobState.CANCELLED, null)
    }

    /**
     * Best-effort "M85 S0". Marlin 1.1.6 kill()s the board when the M85 timer expires
     * and our idle M105 polling doesn't count as activity, so a timer armed at job start
     * must be disarmed when the job ends, or the board halts n seconds after the print
     * (seen on the real printer: "KILL caused by too much inactive time"). Skipped if the
     * link is gone or the board already halted. Never throws.
     */
    private fun disarmIdleKill(why: String) {
        if (!idleKillArmed) return
        val c = conn
        if (c == null || !c.isOpen || halted) return
        try {
            sendAndWait("M85 S0", numbered = false, poke = false, maxWaitMs = 5_000)
            idleKillArmed = false
            say("M85 idle-kill timer disarmed ($why)")
        } catch (e: Exception) {
            if (e is PrinterHaltedException) halted = true
            say("could not disarm M85 ($why): ${e.message}")
        }
    }

    private fun finish(j: Job, st: JobState, err: String?) {
        if (!j.state.active) return
        disarmIdleKill("job ${st.wire}")
        if (j.pausedSince > 0) { j.pausedMs += SystemClock.elapsedRealtime() - j.pausedSince; j.pausedSince = 0 }
        j.state = st; j.error = err; j.endedAt = System.currentTimeMillis()
        if (j.startedAt == 0L) j.startedAt = j.endedAt
        val snap = jobJson(j)
        say("job ${j.id} ${st.wire}${err?.let { ": $it" } ?: ""}")
        synchronized(history) { history.add(snap); while (history.size > 200) history.removeAt(0) }
        saveHistory()
        events.onJobFinished(snap)
        events.onStateChanged()
    }

    private fun fatal(msg: String) {
        val wasHalted = halted
        halted = true; homed = false
        val j = job
        if (wasHalted && (j == null || !j.state.active)) { say("(already halted) $msg"); return }
        if (j != null && j.state.active) finish(j, JobState.ERROR, msg) else events.onPrinterError(msg)
    }

    private fun connectionLost(e: IOException) {
        val msg = "connection lost: ${e.message}"
        say(msg)
        closeConn()
        connState = "error"; connError = msg
        val j = job
        if (j != null && j.state.active) finish(j, JobState.ERROR, msg) else events.onPrinterError(msg)
    }

    private fun closeConn() {
        val c = conn ?: return
        try { c.close() } catch (t: Throwable) { Log.w(TAG, "close: $t") }
        conn = null
    }

    /** Caller-thread pre-check (the worker re-checks authoritatively before sending anything). */
    private fun refuseIfJobActive(gcode: Boolean = false) {
        val j = job ?: return
        if (!j.state.active) return
        throw BusyException(if (gcode) "refused: a print job is ${j.state.wire}; only read-only commands " +
            "(${RealPrinterConnection.READ_ONLY.joinToString()}) are allowed until it is done or cancelled"
            else "refused: a print job is ${j.state.wire}; homing, motion and board reset are not allowed until it is done or cancelled")
    }

    private fun requireIdleConnected() {
        val c = conn
        if (c == null || !c.isOpen) throw IllegalStateException("printer not connected")
        val j = job
        if (j != null && j.state.active) throw BusyException("refused: a print job is ${j.state.wire}; homing, motion and " +
            "board reset are not allowed until it is done or cancelled")
    }

    private fun pollTempsIfDue(everyMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPoll < everyMs) return
        lastPoll = now
        sendAndWait("M105", numbered = false)
        pollCount++
        if (job?.state != JobState.PRINTING && pollCount % 5 == 0L) sendAndWait("M114", numbered = false)
    }

    // ------------------------------------------------------------------ protocol

    private fun checksum(s: String): Int { var cs = 0; for (ch in s) cs = cs xor ch.code; return cs and 0xFF }

    /**
     * Write one command and read until its "ok". Numbered lines get "N<n> ... *<cs>"
     * framing and are kept for Resend. Silence for settings.silenceMs -> poke with
     * M105 (its ok then counts as the ack, like OctoPrint); after maxPokes pokes with
     * no byte at all, the printer is declared unresponsive.
     */
    private fun sendAndWait(cmd: String, numbered: Boolean, poke: Boolean = true, maxWaitMs: Long = 0): List<String> {
        val c = conn ?: throw IOException("not connected")
        if (halted && RealPrinterConnection.commandWord(cmd) != "M115") throw PrinterHaltedException("printer is halted")
        val payload = if (numbered) {
            lineNo++
            val body = "N$lineNo $cmd"
            "$body*${checksum(body)}".also { sent[lineNo] = it; sent.remove(lineNo - 64) }
        } else cmd
        c.writeLine(payload); linesSentTotal++
        noteTargets(cmd)
        noteModes(cmd)
        val out = ArrayList<String>()
        val resendQueue = ArrayDeque<Long>()
        var lastRx = SystemClock.elapsedRealtime()
        var pokes = 0
        val started = SystemClock.elapsedRealtime()
        while (true) {
            val l = c.readLine(250)
            val now = SystemClock.elapsedRealtime()
            if (maxWaitMs > 0 && now - started > maxWaitMs) throw IOException("no 'ok' to '$cmd' within ${maxWaitMs / 1000}s (printer powered off / not responding?)")
            if (l == null) {
                if (!poke) continue
                if (now - lastRx > settings.silenceMs) {
                    if (pokes >= settings.maxPokes) throw IOException("printer not responding (no data for ${(now - lastRx) / 1000}s after $pokes M105 pokes) while waiting for ok to '$cmd'")
                    pokes++; say("no reply for ${settings.silenceMs / 1000}s to '$cmd': poking with M105 ($pokes)")
                    c.writeLine("M105"); lastRx = now
                }
                continue
            }
            lastRx = now
            handleLine(l)
            out.add(l)
            val rs = Regex("^(?:Resend:|rs)\\s*N?(\\d+)").find(l)
            if (rs != null) {
                val n = rs.groupValues[1].toLong()
                if (!sent.containsKey(n)) throw PrinterHaltedException("printer asked to resend line $n which is not in the resend buffer")
                for (k in n..lineNo) if (k !in resendQueue) resendQueue.addLast(k)
                continue
            }
            if (l.startsWith("ok")) {
                val k = resendQueue.removeFirstOrNull() ?: return out.also { noteHomed(cmd) }
                say("resending line $k"); c.writeLine(sent[k]!!); linesSentTotal++
            }
        }
    }

    /** Positioning/extrusion modes, feedrate and fan from a command we sent (Marlin 1.1 semantics). */
    private fun noteModes(cmd: String) {
        val w = cmd.substringBefore(';').trim().split(Regex("\\s+"))
        fun p(k: Char) = w.drop(1).firstOrNull { it.length > 1 && it[0].uppercaseChar() == k }?.substring(1)?.toDoubleOrNull()
        when (w[0].uppercase()) {
            "G90" -> relativeXyz = false
            "G91" -> relativeXyz = true
            "M82" -> relativeE = false
            "M83" -> relativeE = true
            "G0", "G1" -> p('F')?.let { feedrate = it }
            "M106" -> fanSpeed = (p('S') ?: 255.0).toInt()
            "M107" -> fanSpeed = 0
        }
    }

    /** Called after a G28 got its ok: a bare G28 (or one naming X, Y and Z) homes everything. */
    private fun noteHomed(cmd: String) {
        val w = cmd.substringBefore(';').trim().uppercase().split(Regex("\\s+"))
        if (w[0] != "G28") return
        val axes = w.drop(1).map { it.take(1) }.toSet()
        if (axes.isEmpty() || axes.containsAll(setOf("X", "Y", "Z"))) homed = true
    }

    /** Heater targets we just commanded (Marlin's wait reports may lag behind). */
    private fun noteTargets(cmd: String) {
        val w = cmd.substringBefore(';').trim().split(Regex("\\s+"))
        val s = w.firstOrNull { it.startsWith("S") || it.startsWith("R") }?.substring(1)?.toDoubleOrNull() ?: return
        when (w[0].uppercase()) {
            "M104", "M109" -> hotendTarget = s
            "M140", "M190" -> bedTarget = s
        }
    }

    private val tRe = Regex("(?:^|\\s)T:\\s*(-?[\\d.]+)(?:\\s*/\\s*(-?[\\d.]+))?")
    private val bRe = Regex("(?:^|\\s)B:\\s*(-?[\\d.]+)(?:\\s*/\\s*(-?[\\d.]+))?")
    private val posRe = Regex("^X:(-?[\\d.]+) Y:(-?[\\d.]+) Z:(-?[\\d.]+) E:(-?[\\d.]+)")
    private val fatalWords = listOf("halted", "kill()", "Thermal Runaway", "MINTEMP", "MAXTEMP", "Heating failed", "system stopped")

    private fun handleLine(l: String) {
        lastLineRx = l
        if (l.startsWith("Error:") || l.startsWith("!!")) {
            say("printer: $l")
            if (fatalWords.any { l.contains(it, ignoreCase = true) }) throw PrinterHaltedException(l.removePrefix("Error:"))
            return
        }
        if (l.startsWith("echo:") && !l.startsWith("echo:busy")) say("printer: $l")
        val t = tRe.find(l)
        if (t != null) {
            hotend = t.groupValues[1].toDoubleOrNull(); t.groupValues[2].toDoubleOrNull()?.let { hotendTarget = it }
            bRe.find(l)?.let { b -> bed = b.groupValues[1].toDoubleOrNull(); b.groupValues[2].toDoubleOrNull()?.let { bedTarget = it } }
            tempsAt = System.currentTimeMillis()
            synchronized(tempHistory) {
                val last = tempHistory.lastOrNull()
                if (last == null || tempsAt - last[0] >= 2000) {
                    tempHistory.addLast(doubleArrayOf(tempsAt.toDouble(), hotend ?: 0.0, hotendTarget ?: 0.0, bed ?: 0.0, bedTarget ?: 0.0))
                    while (tempHistory.size > 900) tempHistory.removeFirst()
                }
            }
        }
        posRe.find(l)?.let { m -> pos = DoubleArray(4) { m.groupValues[it + 1].toDouble() }; posAt = System.currentTimeMillis() }
    }

    private fun say(s: String) {
        Log.i(TAG, s)
        synchronized(log) { log.addLast("${System.currentTimeMillis()} $s"); while (log.size > 100) log.removeFirst() }
    }

    // ------------------------------------------------------------------ history persistence

    private fun loadHistory() {
        try {
            if (!historyFile.isFile) return
            val a = JSONArray(historyFile.readText())
            synchronized(history) { for (i in 0 until a.length()) history.add(a.getJSONObject(i)) }
        } catch (e: Exception) { Log.w(TAG, "history load failed: $e") }
    }

    private fun saveHistory() {
        try {
            val txt = synchronized(history) { JSONArray(history).toString() }
            val tmp = File(historyFile.path + ".tmp"); tmp.writeText(txt); tmp.renameTo(historyFile)
        } catch (e: Exception) { Log.w(TAG, "history save failed: $e") }
    }

    companion object {
        const val TAG = "PrinterBridge.ctl"
        const val MIN_EXTRUDE_C = 170.0   // Marlin EXTRUDE_MINTEMP
    }
}
