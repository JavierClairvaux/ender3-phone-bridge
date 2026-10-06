package com.javcabr.printerbridge

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javcabr.printerbridge.printer.ControllerEvents
import com.javcabr.printerbridge.printer.ControllerSettings
import com.javcabr.printerbridge.printer.FakeMarlin
import com.javcabr.printerbridge.printer.FakePrinterConnection
import com.javcabr.printerbridge.printer.PrinterController
import com.javcabr.printerbridge.printer.RealPrinterConnection
import com.javcabr.printerbridge.printer.SafetyLockException
import com.javcabr.printerbridge.usb.SimCh340UsbIo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class PrinterBridgeInstrumentedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val TAG = "PrinterBridge.test"

    private class Events : ControllerEvents {
        val finished = CopyOnWriteArrayList<JSONObject>()
        val errors = CopyOnWriteArrayList<String>()
        override fun onJobFinished(job: JSONObject) { finished.add(job) }
        override fun onPrinterError(message: String) { errors.add(message) }
    }

    private fun controller(ev: Events, name: String,
                           settings: ControllerSettings = ControllerSettings(idleTempPoll = false, idleShutdownS = 300, silenceMs = 5000)): Pair<PrinterController, File> {
        val dir = File(ctx.cacheDir, "t_$name").apply { deleteRecursively(); mkdirs() }
        val gdir = File(dir, "gcode").apply { mkdirs() }
        return PrinterController(File(dir, "history.json"), gdir, ev, settings) to gdir
    }

    private fun writeLines(dir: File, name: String, lines: List<String>): String {
        File(dir, name).writeText(lines.joinToString("\n") + "\n"); return name
    }

    private fun jobState(ctl: PrinterController) = ctl.statusJson().getJSONObject("job")

    private fun writeGcode(dir: File, name: String, n: Int): String {
        File(dir, name).printWriter().use { w ->
            w.println("; test file"); w.println("M104 S200"); w.println("M140 S60"); w.println("G28")
            for (i in 0 until n) w.println("G1 X${i % 200} Y${(i * 7) % 200} E${i * 0.01} ; move $i")
            w.println("M104 S0"); w.println("M140 S0")
        }
        return name
    }

    private fun waitFor(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(50) }
        fail("timed out waiting for $what")
    }

    /** The real Chaquopy path (unmodified ch340_serial.py + printer_link.py) over a simulated CH340. */
    @Test
    fun realConnection_overSimCh340_isDtrSafe_drainsStale_andReconnectsWithoutReset() {
        val marlin = FakeMarlin().apply { lineDelayMs = 2; timeScale = 20.0 }
        var io: SimCh340UsbIo? = null
        val stale = "ø\u0080xþ".toByteArray(Charsets.ISO_8859_1)
        fun conn(lock: Boolean) = RealPrinterConnection(ctx, "sim-usb", { SimCh340UsbIo(marlin, stale).also { io = it } }, { lock }, 750)

        val c1 = conn(lock = true)
        c1.open()
        val info = c1.info()
        Log.i(TAG, "open info: $info")
        assertEquals("0x31", info["chip_version"])
        assertEquals(stale.size, info["stale_bytes_drained"])
        assertEquals(false, info["reset_detected_on_open"])
        c1.writeLine("M115")
        val lines = mutableListOf<String>()
        while (true) { val l = c1.readLine(3000) ?: break; lines.add(l); if (l.startsWith("ok")) break }
        assertTrue("M115 reply: $lines", lines.first().startsWith("FIRMWARE_NAME:") && lines.last() == "ok")
        // safety lock refuses motion before touching USB
        try { c1.writeLine("G28"); fail("G28 not refused") } catch (e: SafetyLockException) { Log.i(TAG, "refused as expected: ${e.message}") }
        try { c1.writeLine("N5 M104 S200*12"); fail("M104 not refused") } catch (e: SafetyLockException) {}
        assertEquals(1L, c1.linesWritten)
        val hs = c1.handshakeValues()
        c1.close()
        val io1 = io!!
        Log.i(TAG, "session1 handshake values=$hs mcrLog=${io1.mcrLog} dtrEdges=${io1.dtrEdges} bootCount=${marlin.bootCount}")
        assertTrue("MCR values sent: ${io1.mcrLog}", io1.mcrLog.isNotEmpty() && io1.mcrLog.all { it == 0 })
        assertEquals(0, io1.dtrEdges)
        assertEquals(0, marlin.bootCount)

        // reconnect: machine state must survive (no reboot)
        marlin.hotendTarget = 123.0
        val c2 = conn(lock = false)
        c2.open()
        assertEquals(false, c2.info()["reset_detected_on_open"])
        c2.writeLine("M105")
        val r = generateSequence { c2.readLine(3000) }.first { it.startsWith("ok") }
        assertTrue("M105 after reconnect: $r", r.contains("/123.00"))
        assertFalse(c2.dtrEverAsserted())
        c2.close()
        assertEquals(0, io!!.dtrEdges)
        assertEquals(0, marlin.bootCount)
        Log.i(TAG, "PASS realConnection_overSimCh340: M115 ok, lock ok, DTR never asserted, 0 reboots across 2 sessions")
    }

    /** Full job through the controller over the real Chaquopy path, with injected checksum errors. */
    @Test
    fun controller_printsOverSimUsb_withResends() {
        val ev = Events()
        val (ctl, gdir) = controller(ev, "simusb")
        val marlin = FakeMarlin().apply { lineDelayMs = 1; timeScale = 50.0; injectResendEvery = 37 }
        val name = writeGcode(gdir, "simusb.gcode", 400)
        ctl.connect(RealPrinterConnection(ctx, "sim-usb", { SimCh340UsbIo(marlin) }, { false }, 300))
        assertTrue(ctl.connectionJson().optString("firmware").contains("FAKE"))
        val t0 = System.currentTimeMillis()
        ctl.startPrint(name)
        waitFor(120_000, "job done") { ev.finished.isNotEmpty() }
        val j = ev.finished.first()
        Log.i(TAG, "sim-usb job finished in ${System.currentTimeMillis() - t0} ms: $j")
        assertEquals("done", j.getString("state"))
        assertEquals(405, j.getInt("lines_done"))
        assertTrue("resends ${j.getInt("resends")}", j.getInt("resends") >= 5)
        assertEquals(0.0, marlin.hotendTarget, 0.001)
        ctl.disconnect()
    }

    @Test
    fun controller_fake_pauseResumeCancel_andErrors() {
        val ev = Events()
        val (ctl, gdir) = controller(ev, "fake")
        val marlin = FakeMarlin().apply { lineDelayMs = 5; timeScale = 50.0 }
        val name = writeGcode(gdir, "a.gcode", 2000)
        val conn = FakePrinterConnection(marlin)
        ctl.connect(conn)

        // pause / resume / cancel
        ctl.startPrint(name)
        waitFor(20_000, "printing") { ctl.statusJson().getJSONObject("job").getInt("lines_done") > 50 }
        ctl.pause()
        val d1 = ctl.statusJson().getJSONObject("job").getInt("lines_done")
        Thread.sleep(1000)
        val d2 = ctl.statusJson().getJSONObject("job").getInt("lines_done")
        assertTrue("paused but progressed $d1 -> $d2", d2 - d1 <= 1)
        assertEquals("paused", ctl.statusJson().getJSONObject("job").getString("state"))
        ctl.resume()
        waitFor(10_000, "progress after resume") { ctl.statusJson().getJSONObject("job").getInt("lines_done") > d2 + 20 }
        marlin.hotendTarget = 200.0
        ctl.cancel()
        waitFor(10_000, "cancelled") { ev.finished.size == 1 }
        assertEquals("cancelled", ev.finished[0].getString("state"))
        waitFor(3000, "heaters off after cancel") { marlin.hotendTarget == 0.0 && marlin.bedTarget == 0.0 }

        // thermal runaway during a print -> job error, printer halted
        ctl.startPrint(name)
        waitFor(20_000, "printing 2") { ctl.statusJson().getJSONObject("job").let { it.getString("state") == "printing" && it.getInt("lines_done") > 50 } }
        marlin.triggerError("thermal_runaway")
        waitFor(10_000, "error") { ev.finished.size == 2 }
        assertEquals("error", ev.finished[1].getString("state"))
        assertTrue(ev.finished[1].getString("error").contains("Thermal Runaway"))
        assertTrue(ctl.connectionJson().getBoolean("halted"))
        Thread.sleep(500)
        assertEquals("no duplicate printer-error event for the 2nd kill() line", 0, ev.errors.size)
        try { ctl.startPrint(name); fail("started while halted") } catch (e: IllegalStateException) {}

        // reboot clears halt; then a USB disconnect mid-print -> job error "connection lost"
        ctl.resetBoard()
        Thread.sleep(800)
        ctl.startPrint(name)
        waitFor(20_000, "printing 3") { ctl.statusJson().getJSONObject("job").let { it.getString("state") == "printing" && it.getInt("lines_done") > 50 } }
        conn.simulateDisconnect()
        waitFor(10_000, "error 2") { ev.finished.size == 3 }
        assertEquals("error", ev.finished[2].getString("state"))
        assertTrue(ev.finished[2].getString("error").startsWith("connection lost"))
        assertEquals("error", ctl.connectionJson().getString("state"))
        assertEquals(3, ctl.history(10).length())
        Log.i(TAG, "PASS controller_fake: pause/resume/cancel/thermal/disconnect; history=${ctl.history(10)}")
    }

    // ---------------------------------------------------------------- M85 (bug 1)

    /** Negative control: the simulator's M85 model kills an armed, idle (M105-polled only) board. */
    @Test
    fun fakeMarlin_m85_killsIdleBoard_evenWhilePolledWithM105() {
        val marlin = FakeMarlin().apply { lineDelayMs = 1; timeScale = 10.0 }
        val c = FakePrinterConnection(marlin); c.open()
        fun cmd(l: String): List<String> { c.writeLine(l); val out = mutableListOf<String>(); while (true) { val r = c.readLine(2000) ?: break; out.add(r); if (r.startsWith("ok")) break }; return out }
        cmd("M85 S3")
        val seen = mutableListOf<String>()
        val end = System.currentTimeMillis() + 2000          // = 20 simulated s > 3 s timeout
        while (System.currentTimeMillis() < end && !marlin.halted) { seen += cmd("M105"); Thread.sleep(100) }
        while (true) { seen += c.readLine(300) ?: break }
        Log.i(TAG, "m85 negative control: halted=${marlin.halted} lines=${seen.filter { it.startsWith("Error") }}")
        assertTrue("armed M85 should kill an M105-only-polled board", marlin.halted)
        assertTrue(seen.any { it.contains("KILL caused by too much inactive time") })
        c.close()
    }

    @Test
    fun controller_disarmsM85_afterDone_cancel_andReconnect_soIdleBoardIsNotKilled() {
        val ev = Events()
        // real-time sim, 3 s idle-kill, idle M105 polling ON like the real app
        val (ctl, gdir) = controller(ev, "m85", ControllerSettings(idleTempPoll = true, idleShutdownS = 3, silenceMs = 5000))
        val marlin = FakeMarlin().apply { lineDelayMs = 5; timeScale = 1.0 }
        val name = writeLines(gdir, "m85.gcode", (0 until 150).map { "G1 X${it % 100} Y${it % 50}" })
        ctl.connect(FakePrinterConnection(marlin))

        // 1. job finishes, then the board idles 6 s (> 3 s timeout) with M105 polling: must NOT halt
        ctl.startPrint(name)
        waitFor(30_000, "done") { ev.finished.size == 1 }
        assertEquals("done", ev.finished[0].getString("state"))
        assertEquals("M85 disarmed at job end", 0.0, marlin.m85TimeoutS, 0.0)
        assertFalse(ctl.idleKillArmed)
        Thread.sleep(6000)
        assertFalse("board killed after a finished job", marlin.halted)
        assertFalse(ctl.connectionJson().getBoolean("halted"))

        // 2. cancel disarms too
        ctl.startPrint(name)
        waitFor(10_000, "printing") { jobState(ctl).getInt("lines_done") > 20 }
        assertEquals(3.0, marlin.m85TimeoutS, 0.0)
        ctl.cancel()
        waitFor(10_000, "cancelled") { ev.finished.size == 2 }
        assertEquals(0.0, marlin.m85TimeoutS, 0.0)
        Thread.sleep(5000)
        assertFalse("board killed after a cancelled job", marlin.halted)

        // 3. link lost mid-job leaves the board's timer armed; reconnecting (no reset) disarms it
        val conn = FakePrinterConnection(marlin)
        ctl.connect(conn)
        ctl.startPrint(name)
        waitFor(10_000, "printing 3") { jobState(ctl).getInt("lines_done") > 20 }
        conn.simulateDisconnect()
        waitFor(10_000, "error") { ev.finished.size == 3 }
        assertEquals(3.0, marlin.m85TimeoutS, 0.0)
        ctl.connect(FakePrinterConnection(marlin))   // within the 3 s window
        assertEquals("reconnect disarms a timer left armed", 0.0, marlin.m85TimeoutS, 0.0)
        Thread.sleep(5000)
        assertFalse(marlin.halted)
        assertEquals(0, ev.errors.size)
        Log.i(TAG, "PASS controller m85: disarmed after done/cancel/reconnect, no kill while idle-polled")
        ctl.disconnect()
    }

    // ---------------------------------------------------------------- pause/cancel during heat waits (bug 2)

    @Test
    fun controller_pauseResumeCancel_duringHeatWait_returnPromptly_andHonestly() {
        val ev = Events()
        val (ctl, gdir) = controller(ev, "heatwait")
        val marlin = FakeMarlin().apply { lineDelayMs = 5; timeScale = 4.0 }
        val name = writeLines(gdir, "heat.gcode", listOf("M140 S60", "M104 S200", "M190 S60", "M109 S200") + (0 until 100).map { "G1 X$it" })
        ctl.connect(FakePrinterConnection(marlin))
        ctl.startPrint(name)
        waitFor(10_000, "M190 in flight") { jobState(ctl).optString("in_flight").startsWith("M190") }

        // pause while the firmware heat wait is in flight
        var t0 = System.currentTimeMillis()
        val p = ctl.pause()
        val pauseMs = System.currentTimeMillis() - t0
        Log.i(TAG, "pause during M190 returned in $pauseMs ms: $p")
        assertTrue("pause took $pauseMs ms", pauseMs < 2500)
        assertEquals("pausing", p.getString("state"))
        assertTrue(p.getString("in_flight").startsWith("M190"))
        val logIdx = marlin.commandLog.size
        waitFor(60_000, "paused after M190 returns") { jobState(ctl).getString("state") == "paused" }
        val j = jobState(ctl)
        assertEquals("only the 3 lines up to and including M190 done", 3, j.getInt("lines_done"))
        assertTrue(j.isNull("in_flight"))
        // no G28 in this file -> position unknown -> no park motion; only the M85 disarm was sent
        assertTrue(j.getJSONObject("pause").getString("park_skipped").startsWith("position unknown"))
        val afterPause = marlin.commandLog.drop(logIdx).filter { !it.startsWith("M190") }
        assertEquals("commands sent by the pause", listOf("M85 S0"), afterPause)
        val nPaused = marlin.nonReportCommands
        Thread.sleep(2000)
        assertEquals("no new lines while paused", nPaused, marlin.nonReportCommands)
        assertEquals(3, jobState(ctl).getInt("lines_done"))

        // resume returns promptly; the nozzle (M104 S200 sent before the M190) is still below
        // target, so resume first waits on M109 ("resuming", in flight), then continues
        t0 = System.currentTimeMillis()
        val r = ctl.resume()
        assertTrue(System.currentTimeMillis() - t0 < 2500)
        assertTrue(r.getString("state"), r.getString("state") in setOf("resuming", "printing"))
        waitFor(60_000, "done") { ev.finished.size == 1 }
        assertEquals("done", ev.finished[0].getString("state"))

        // pause then resume while still in flight withdraws the pause
        val name2 = writeLines(gdir, "heat2.gcode", listOf("M190 S85") + (0 until 50).map { "G1 Y$it" })
        ctl.startPrint(name2)
        waitFor(10_000, "M190 #2 in flight") { jobState(ctl).optString("in_flight").startsWith("M190") }
        assertEquals("pausing", ctl.pause().getString("state"))
        assertEquals("printing", ctl.resume().getString("state"))
        waitFor(60_000, "done 2") { ev.finished.size == 2 }
        assertEquals("done", ev.finished[1].getString("state"))

        // cancel during a heat wait returns at once with cancel_requested; cancels once the wait ends
        val name3 = writeLines(gdir, "heat3.gcode", listOf("M190 S100") + (0 until 50).map { "G1 Z$it" })
        ctl.startPrint(name3)
        waitFor(10_000, "M190 #3 in flight") { jobState(ctl).optString("in_flight").startsWith("M190") }
        t0 = System.currentTimeMillis()
        val c = ctl.cancel()
        val cancelMs = System.currentTimeMillis() - t0
        Log.i(TAG, "cancel during M190 returned in $cancelMs ms: $c")
        assertTrue("cancel took $cancelMs ms", cancelMs < 500)
        assertTrue(c.getBoolean("cancel_requested"))
        assertEquals("printing", c.getString("state"))   // honest: not cancelled yet
        waitFor(90_000, "cancelled") { ev.finished.size == 3 }
        assertEquals("cancelled", ev.finished[2].getString("state"))
        assertEquals(1, ev.finished[2].getInt("lines_done"))
        assertEquals(0.0, marlin.bedTarget, 0.0)
        Log.i(TAG, "PASS heat-wait pause/resume/cancel: pause ${pauseMs}ms, cancel ${cancelMs}ms")
    }

    // ---------------------------------------------------------------- pause / park / resume

    private fun parkFile(gdir: File, name: String, moves: Int, home: Boolean = true) = writeLines(gdir, name,
        (if (home) listOf("G28") else emptyList()) +
        listOf("M83", "G90", "M140 S60", "M104 S200", "M190 S60", "M109 S200", "M106 S200", "G1 Z0.3 F600") +
        (0 until moves).map { "G1 X${50 + it % 20} Y${60 + it % 10} E0.05 F1500" } + listOf("M107"))

    private fun pauseSettings(standbyS: Int, idleShutdownS: Int = 20) = ControllerSettings(
        idleTempPoll = true, idleShutdownS = idleShutdownS, silenceMs = 5000,
        pause = com.javcabr.printerbridge.printer.PauseSettings(nozzleStandbyS = standbyS))

    private fun num(x: Any?) = (x as Number).toDouble()

    @Test
    fun pause_parks_coolsAfterStandby_longPauseNoKill_resumeReheatsAndRestoresExactly() {
        val ev = Events()
        val (ctl, gdir) = controller(ev, "park", pauseSettings(standbyS = 2, idleShutdownS = 20))
        val marlin = FakeMarlin().apply { lineDelayMs = 10; timeScale = 10.0; snapshotPrefixes = setOf("M400", "M85 S20") }
        val name = parkFile(gdir, "park.gcode", 400)
        ctl.connect(FakePrinterConnection(marlin))
        ctl.startPrint(name)
        waitFor(60_000, "printing moves") { jobState(ctl).getInt("lines_done") > 120 }

        val logIdx = marlin.commandLog.size
        val p = ctl.pause()
        Log.i(TAG, "pause -> ${p.getString("state")} stage=${p.getJSONObject("pause").getString("stage")}")
        waitFor(10_000, "paused") { jobState(ctl).getString("state") == "paused" }
        val pj = jobState(ctl).getJSONObject("pause")
        val saved = pj.getJSONObject("saved")
        assertTrue(pj.getBoolean("parked")); assertTrue(pj.isNull("park_skipped"))
        assertEquals(5.0, pj.getDouble("retracted_mm"), 0.0)
        // the pause's own command sequence, exactly (at most one job line was still in flight)
        val pauseCmds = marlin.commandLog.drop(logIdx).dropWhile { it.startsWith("G1 X") }
        val z = saved.getDouble("z")
        assertEquals(listOf("M85 S0", "M400", "M83", "G1 E-5 F1800", "G90", "G1 Z${fmt(z + 10)} F600", "G1 X10 Y210 F6000", "M107", "M400"), pauseCmds)
        // parked state on the machine; saved values = true pre-park state (snapshot at the first M400)
        val pre = marlin.snapshots.first { it.first == "M400" }.second
        assertEquals(num(pre["x"]), saved.getDouble("x"), 0.005); assertEquals(num(pre["y"]), saved.getDouble("y"), 0.005)
        assertEquals(num(pre["z"]), z, 0.005); assertEquals(num(pre["e"]), saved.getDouble("e"), 0.005)
        assertEquals(10.0, marlin.x, 0.0); assertEquals(210.0, marlin.y, 0.0); assertEquals(z + 10, marlin.z, 1e-6)
        assertEquals(0, marlin.fan); assertEquals(200, saved.getInt("fan")); assertEquals(1500.0, saved.getDouble("feedrate"), 0.0)
        assertTrue(saved.getBoolean("relative_e")); assertFalse(saved.getBoolean("relative_xyz"))
        assertEquals("M85 disarmed during pause", 0.0, marlin.m85TimeoutS, 0.0)
        assertEquals(200.0, marlin.hotendTarget, 0.0); assertEquals(60.0, marlin.bedTarget, 0.0)

        // nozzle stays hot until the standby timeout, then cools; bed stays on
        Thread.sleep(1000)
        assertEquals("nozzle still on before standby timeout", 200.0, marlin.hotendTarget, 0.0)
        waitFor(5000, "standby cooldown") { marlin.hotendTarget == 0.0 }
        assertTrue(jobState(ctl).getJSONObject("pause").getBoolean("nozzle_cooled"))
        assertEquals("bed kept on", 60.0, marlin.bedTarget, 0.0)
        // long pause: > M85 timeout (20 sim s = 2 s real) several times over, board must not be killed
        Thread.sleep(4000)
        assertFalse("killed during a long pause", marlin.halted)
        assertEquals(0, ev.errors.size)
        val linesWhilePaused = jobState(ctl).getInt("lines_done")

        // resume: returns promptly while reheating (in flight), then restores and continues
        val resIdx = marlin.commandLog.size
        var t0 = System.currentTimeMillis()
        val r = ctl.resume()
        val resumeMs = System.currentTimeMillis() - t0
        Log.i(TAG, "resume returned in $resumeMs ms: state=${r.getString("state")} in_flight=${r.opt("in_flight")}")
        assertTrue(resumeMs < 2500)
        assertEquals("resuming", r.getString("state"))
        assertTrue(r.optString("in_flight").startsWith("M10"))
        assertEquals(linesWhilePaused, jobState(ctl).getInt("lines_done"))
        waitFor(60_000, "printing again") { jobState(ctl).getString("state") == "printing" }
        val resumeCmds = marlin.commandLog.drop(resIdx).takeWhile { !it.startsWith("G1 X") || it.endsWith("F6000") }
        val e = saved.getDouble("e")
        assertEquals(listOf("M104 S200", "M109 S200", "M83", "G1 E5 F1800", "G1 E1.5 F300", "G90",
            "G1 X${fmt(saved.getDouble("x"))} Y${fmt(saved.getDouble("y"))} F6000", "G1 Z${fmt(z)} F600",
            "G92 E${String.format(java.util.Locale.US, "%.5f", e)}", "M83", "G1 F1500", "M106 S200", "M85 S20"), resumeCmds)
        // machine state right before the M85 re-arm (= end of restore) equals the pre-pause state
        val post = marlin.snapshots.last { it.first == "M85 S20" }.second
        assertEquals(saved.getDouble("x"), num(post["x"]), 1e-6); assertEquals(saved.getDouble("y"), num(post["y"]), 1e-6)
        assertEquals(z, num(post["z"]), 1e-6); assertEquals(e, num(post["e"]), 1e-6)
        assertEquals(200, post["fan"]); assertEquals(1500.0, num(post["feedrate"]), 0.0)
        assertEquals(true, post["relativeE"]); assertEquals(false, post["relativeAll"])
        assertEquals(200.0, num(post["hotendTarget"]), 0.0); assertEquals(60.0, num(post["bedTarget"]), 0.0)
        assertEquals("M85 re-armed", 20.0, marlin.m85TimeoutS, 0.0)
        assertEquals(0, marlin.coldExtrusionsPrevented)
        waitFor(60_000, "done") { ev.finished.size == 1 }
        assertEquals("done", ev.finished[0].getString("state"))
        assertEquals(410, ev.finished[0].getInt("lines_done"))
        Log.i(TAG, "PASS park/standby/long-pause/resume-restore; resume returned in ${resumeMs}ms")
    }

    @Test
    fun pause_withoutPosition_orWithSafetyLock_sendsNoMotion() {
        // 1. fake, no G28 in the file -> position unknown
        val ev = Events()
        val (ctl, gdir) = controller(ev, "nopos", pauseSettings(standbyS = 300, idleShutdownS = 300))
        val marlin = FakeMarlin().apply { lineDelayMs = 10; timeScale = 20.0 }
        ctl.connect(FakePrinterConnection(marlin))
        ctl.startPrint(parkFile(gdir, "nohome.gcode", 300, home = false))
        waitFor(60_000, "printing") { jobState(ctl).getInt("lines_done") > 50 }
        var idx = marlin.commandLog.size
        ctl.pause()
        waitFor(10_000, "paused") { jobState(ctl).getString("state") == "paused" }
        var pj = jobState(ctl).getJSONObject("pause")
        assertFalse(pj.getBoolean("parked"))
        assertTrue(pj.getString("park_skipped"), pj.getString("park_skipped").startsWith("position unknown"))
        assertEquals(listOf("M85 S0"), marlin.commandLog.drop(idx).dropWhile { it.startsWith("G1 X") })
        ctl.resume()
        waitFor(60_000, "done") { ev.finished.size == 1 }
        assertEquals("done", ev.finished[0].getString("state"))
        ctl.disconnect()

        // 2. real connection class (sim-usb) with the safety lock switched ON during the print
        val ev2 = Events()
        val (ctl2, gdir2) = controller(ev2, "lock", pauseSettings(standbyS = 300, idleShutdownS = 300))
        val m2 = FakeMarlin().apply { lineDelayMs = 5; timeScale = 20.0 }
        // lock ON exactly while the job is pausing/paused (a lock flipped mid-print would fail the next job line)
        ctl2.connect(RealPrinterConnection(ctx, "sim-usb", { SimCh340UsbIo(m2) }, { ctl2.currentJobState in setOf("pausing", "paused") }, 300))
        ctl2.startPrint(parkFile(gdir2, "lock.gcode", 300))
        waitFor(60_000, "printing 2") { jobState(ctl2).getInt("lines_done") > 50 }
        ctl2.pause()
        waitFor(10_000, "paused 2") { jobState(ctl2).getString("state") == "paused" }
        idx = m2.commandLog.size
        pj = jobState(ctl2).getJSONObject("pause")
        assertFalse(pj.getBoolean("parked"))
        assertTrue(pj.getString("park_skipped"), pj.getString("park_skipped").contains("safety lock"))
        Thread.sleep(1000)
        assertEquals("nothing but job lines reached the printer", emptyList<String>(), m2.commandLog.drop(idx))
        assertTrue(m2.commandLog.none { it.startsWith("G1 X10 Y210") || it == "M85 S0" })
        ctl2.resume()
        waitFor(60_000, "done 2") { ev2.finished.size == 1 }
        assertEquals("done", ev2.finished[0].getString("state"))
        Log.i(TAG, "PASS pause without position / with safety lock: no motion sent")
        ctl2.disconnect()
    }

    @Test
    fun pauseDuringResumeReheat_returnsToPaused_thenCancelWhilePaused_coolsDown() {
        val ev = Events()
        val (ctl, gdir) = controller(ev, "rpc", pauseSettings(standbyS = 1, idleShutdownS = 20))
        val marlin = FakeMarlin().apply { lineDelayMs = 10; timeScale = 6.0 }
        ctl.connect(FakePrinterConnection(marlin))
        ctl.startPrint(parkFile(gdir, "rpc.gcode", 2000))
        waitFor(90_000, "printing") { jobState(ctl).getInt("lines_done") > 60 }
        ctl.pause()
        waitFor(10_000, "paused") { jobState(ctl).getString("state") == "paused" }
        waitFor(5000, "cooled") { marlin.hotendTarget == 0.0 }
        Thread.sleep(3000)   // let it cool well below 200 so M109 really waits
        val r = ctl.resume()
        assertEquals("resuming", r.getString("state"))
        waitFor(5000, "M109 in flight") { jobState(ctl).optString("in_flight").startsWith("M109") }
        val p = ctl.pause()
        assertEquals("resuming", p.getString("state")); assertTrue(p.getBoolean("pause_requested"))
        waitFor(60_000, "back to paused after reheat") { jobState(ctl).getString("state") == "paused" }
        val pj = jobState(ctl).getJSONObject("pause")
        assertTrue(pj.getBoolean("parked")); assertFalse(pj.getBoolean("nozzle_cooled"))
        assertEquals("still parked", 10.0, marlin.x, 0.0)
        assertEquals("no unretract happened", 0, marlin.commandLog.count { it == "G1 E5 F1800" })
        // cancel while paused
        val c = ctl.cancel()
        assertTrue(c.getBoolean("cancel_requested"))
        waitFor(10_000, "cancelled") { ev.finished.size == 1 }
        assertEquals("cancelled", ev.finished[0].getString("state"))
        val tail = marlin.commandLog.takeLast(4)
        assertEquals(listOf("M104 S0", "M140 S0", "M107", "M84"), tail)
        assertEquals(0.0, marlin.hotendTarget, 0.0); assertEquals(0.0, marlin.bedTarget, 0.0); assertEquals(0.0, marlin.m85TimeoutS, 0.0)
        assertFalse(marlin.halted)
        Log.i(TAG, "PASS pause during resume reheat -> paused; cancel while paused -> cooldown")
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

    // ---------------------------------------------------------------- no homing/motion while a job is active

    @Test
    fun homeMotionHeaterAndReset_refusedInEveryActiveJobState_nothingSent() {
        val ev = Events()
        val (ctl, gdir) = controller(ev, "guard", pauseSettings(standbyS = 1, idleShutdownS = 300))
        val marlin = FakeMarlin().apply { lineDelayMs = 20; timeScale = 6.0 }
        val mcp = com.javcabr.printerbridge.server.McpHandler { ctl }
        ctl.connect(FakePrinterConnection(marlin))
        val probes = listOf("G28", "G1 X11 Y11 F3000", "M104 S123", "M140 S77", "G92 E0")
        fun mcpCall(name: String, args: JSONObject = JSONObject()): JSONObject = JSONObject(mcp.handle(JSONObject()
            .put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
            .put("params", JSONObject().put("name", name).put("arguments", args)).toString()).body!!).getJSONObject("result")
        val checked = mutableListOf<String>()

        fun tryAll(expected: String) {
            assertEquals(expected, jobState(ctl).getString("state"))
            val idx = marlin.commandLog.size
            val boots = marlin.bootCount
            fun refused(what: String, f: () -> Any?) {
                val t0 = System.currentTimeMillis()
                try { f(); fail("$what was accepted in state $expected") } catch (e: com.javcabr.printerbridge.printer.BusyException) {
                    assertTrue("$what message: ${e.message}", e.message!!.contains("refused") && e.message!!.contains(expected))
                }
                assertTrue("$what refusal took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 1000)
            }
            refused("home") { ctl.home() }
            refused("reset_board") { ctl.resetBoard() }
            for (g in listOf("G1 X11 Y11 F3000", "M104 S123", "M140 S77", "G92 E0", "G28 X")) refused("gcode $g") { ctl.sendGcode(g) }
            val m = mcpCall("home")
            assertTrue("MCP home isError in $expected", m.getBoolean("isError"))
            assertTrue(m.getJSONArray("content").getJSONObject(0).getString("text").contains("refused"))
            Thread.sleep(300)
            val sentNow = marlin.commandLog.drop(idx)
            assertTrue("probe commands reached the printer in $expected: $sentNow", sentNow.none { c -> probes.any { c == it || c.startsWith("G28 X") } })
            assertEquals(boots, marlin.bootCount)
            checked += expected
        }

        val name = parkFile(gdir, "guard.gcode", 400)
        // queued: freeze the simulated firmware so the job can't leave QUEUED (M110 stays unanswered)
        marlin.holdCommands = true
        ctl.startPrint(name)
        tryAll("queued")
        marlin.holdCommands = false
        // printing, with a heat wait in flight (worker busy: refusal must still be immediate)
        waitFor(30_000, "M190 in flight") { jobState(ctl).optString("in_flight").startsWith("M190") }
        tryAll("printing")
        // pausing: pause requested while M190 is still in flight
        ctl.pause()
        tryAll("pausing")
        // paused (parked)
        waitFor(60_000, "paused") { jobState(ctl).getString("state") == "paused" }
        tryAll("paused")
        // read-only commands still work while paused
        assertTrue(ctl.sendGcode("M105").getJSONArray("reply").toString().contains("ok"))
        // resuming: nozzle cooled by the 1 s standby, so resume waits on M109
        waitFor(5000, "standby cooldown") { marlin.hotendTarget == 0.0 }
        Thread.sleep(1500)
        ctl.resume()
        waitFor(5000, "resuming with reheat in flight") { jobState(ctl).getString("state") == "resuming" && jobState(ctl).optString("in_flight").startsWith("M10") }
        tryAll("resuming")
        // after the job ends, homing is allowed again
        ctl.cancel()
        waitFor(60_000, "cancelled") { ev.finished.size == 1 }
        assertEquals("cancelled", ev.finished[0].getString("state"))
        val idx = marlin.commandLog.size
        assertTrue(ctl.home().getBoolean("ok"))
        assertTrue(marlin.commandLog.drop(idx).contains("G28"))
        assertEquals(listOf("queued", "printing", "pausing", "paused", "resuming"), checked)
        Log.i(TAG, "PASS home/motion/heater/reset refused (direct + MCP) in $checked; home allowed after cancel")
    }
}
