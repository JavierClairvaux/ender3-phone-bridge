package com.example.chaquopyspike

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import org.json.JSONObject

/**
 * Phase 2: the same driver path as Spike's `driver_over_kotlin_usb`, but the
 * UsbIo is [AndroidUsbIo] talking to the real CH340 + Ender 3 board.
 *
 * Safety: the only G-code sent is M115 and M105 (read-only). DTR/RTS pulses
 * (which reboot an idle Marlin) are the only side effect.
 *
 * Sessions, each on its own freshly opened UsbDeviceConnection, in this order:
 *  1. passive_open      Kotlin only: openDevice + claimInterface, read chip
 *                       version and the baud/LCR registers (vendor IN reads),
 *                       then just listen on bulk IN for 4 s. No writes at all.
 *  2. lines_never_asserted  real driver init, DTR/RTS never asserted, M115
 *  3. driver_no_reset   real driver, reset_on_open=False (as-is), M115
 *  4. driver_reset_on_open  real driver, reset_on_open=True (the phase-1 path), M115,
 *                       with the same call-sequence checks as phase 1
 *  5. lines_never_asserted again (repeatability, after a known reset), M115 + 50x M105
 */
object HwSpike {
    const val TAG = FakeCh340.TAG

    fun runAll(context: Context, manager: UsbManager, device: UsbDevice,
               out: (String, String) -> Unit): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        fun check(name: String, block: () -> String) {
            val r = try { "PASS " + block() } catch (e: Throwable) {
                Log.e(TAG, "hw check $name threw", e); "FAIL $e"
            }
            Log.i(TAG, "HWRESULT $name: $r")
            results.add(name to r)
            out(name, r)
        }

        check("device") { AndroidUsbIo.describe(device) }

        // Phase-1 suite (against the fake) on the real arm64 phone.
        check("phase1_suite_on_phone") {
            val r = Spike.runAll(context)
            val failed = r.filter { !it.second.startsWith("PASS") }
            require(failed.isEmpty()) { "failed: $failed" }
            "${r.size}/${r.size} pass; ${r.first().second}"
        }

        val bridge = Spike.py(context).getModule("spike_bridge")

        check("1_passive_open") {
            val io = AndroidUsbIo(manager, device, "[passive]")
            try {
                val ver = io.controlIn(0x5F, 0, 0, 2, 1000)
                val baud = io.controlIn(0x95, 0x1312, 0, 2, 1000)
                val lcr = io.controlIn(0x95, 0x2518, 0, 2, 1000)
                val end = io.elapsed() + 4000
                while (io.elapsed() < end) io.bulkRead(io.maxPacketSize(), 200)
                val rx = synchronized(io.rxLog) { io.rxLog.toString() }
                fun hex(b: ByteArray?) = b?.joinToString("") { "%02x".format(it) } ?: "FAIL"
                "version=${hex(ver)} reg1312=${hex(baud)} reg2518=${hex(lcr)} " +
                    "rx_in_4s=${rx.length} bytes ${JSONObject.quote(rx)}"
            } finally { io.close() }
        }
        Thread.sleep(1000)

        fun session(name: String, mode: String, extraM105: Int = 0,
                    verify: ((AndroidUsbIo, JSONObject) -> Unit)? = null) = check(name) {
            val io = AndroidUsbIo(manager, device, "[$mode]")
            val json = try {
                bridge.callAttr("hw_session", io, mode, 4.0, "M115", 8.0, extraM105).toString()
            } finally { io.close() }
            val j = JSONObject(json)
            Log.i(TAG, "HWJSON $name $json")
            require(j.getBoolean("reply_ok")) { "no ok reply: $json" }
            require(j.getBoolean("is_marlin")) { "reply not Marlin M115: $json" }
            verify?.invoke(io, j)
            val readerThreads = io.calls.filter { it.what.startsWith("bulkRead") }.map { it.thread }.toSet()
            "RESET_ON_OPEN=${j.getBoolean("reset_detected")} markers=${j.get("boot_markers")} " +
                "unsolicited=${j.getString("unsolicited").length}B reply_s=${j.get("reply_s")} " +
                "calls=${io.calls.size} bulkReadErr(-1)=${io.bulkReadErrors} reader=$readerThreads; " +
                "json=$json"
        }

        session("2_lines_never_asserted", "lines_never_asserted")
        Thread.sleep(1000)
        session("3_driver_no_reset", "driver_no_reset")
        Thread.sleep(1000)
        session("4_driver_reset_on_open", "driver_reset_on_open") { io, _ ->
            // Same sequence assertions as phase 1's driver_over_kotlin_usb.
            val calls = io.calls.map { it.what }
            require(calls[1].startsWith("ctrlIn req=0x5f value=0x0000 index=0x0000 len=2 -> 31")) { calls[1] }
            require(calls.any { it.startsWith("ctrlOut req=0x9a value=0x1312 index=0xcc83 -> ") }) { "no divisor write" }
            require(calls.any { it.startsWith("ctrlOut req=0x9a value=0x2518 index=0x00c3 -> ") }) { "no LCR write" }
            require(calls.any { it.startsWith("bulkWrite M115") })
            require(io.closed)
        }
        Thread.sleep(1000)
        session("5_lines_never_asserted_again", "lines_never_asserted", extraM105 = 50)
        return results
    }

    /**
     * Run 2 (after run 1 showed corrupted RX and a probable reset on close):
     *  6  one session toggling one modem line at a time (listen 5 s after each)
     *  7  next session, lines never asserted: did 6's close() (DTR+RTS dropped) reset?
     *  8  RX integrity, direct per-packet bulkTransfer, logging off
     *  9  RX integrity, Kotlin UsbRequest reader thread, logging off
     *  10 RX integrity, direct + verbose logging + driver debug (= run 1 conditions)
     */
    fun runV2(context: Context, manager: UsbManager, device: UsbDevice,
              out: (String, String) -> Unit, integrityOnly: Boolean = false): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        fun check(name: String, block: () -> String) {
            val r = try { "PASS " + block() } catch (e: Throwable) {
                Log.e(TAG, "hw check $name threw", e); "FAIL $e"
            }
            Log.i(TAG, "HWRESULT $name: $r")
            results.add(name to r); out(name, r)
        }
        val bridge = Spike.py(context).getModule("spike_bridge")
        fun <T> withIo(label: String, queued: Boolean, verbose: Boolean, f: (AndroidUsbIo) -> T): T {
            val io = AndroidUsbIo(manager, device, label, queuedReader = queued, verbose = verbose)
            try { return f(io) } finally { io.close() }
        }

        if (!integrityOnly) {
        check("6_line_edges") {
            val json = withIo("[edges]", true, false) { bridge.callAttr("hw_edges", it, 5.0).toString() }
            Log.i(TAG, "HWJSON 6_line_edges $json")
            val j = JSONObject(json)
            val steps = j.getJSONArray("steps")
            (0 until steps.length()).joinToString("; ") {
                val s = steps.getJSONObject(it)
                "${s.getString("step")} -> reset=${s.getBoolean("reset")} (${s.getInt("bytes")}B)"
            } + "; M115 after ok=${j.getBoolean("m115_after_ok")}"
        }
        Thread.sleep(1000)
        check("7_after_close_with_lines_on") {
            val json = withIo("[afterclose]", true, false) {
                bridge.callAttr("hw_session", it, "lines_never_asserted", 5.0, "M115", 8.0, 0, false).toString()
            }
            Log.i(TAG, "HWJSON 7_after_close_with_lines_on $json")
            val j = JSONObject(json)
            "previous close reset board=${j.getBoolean("reset_detected")} markers=${j.get("boot_markers")} " +
                "unsolicited=${JSONObject.quote(j.getString("unsolicited").take(80))} reply_ok=${j.getBoolean("reply_ok")}"
        }
        Thread.sleep(1000)
        }

        fun integrity(name: String, queued: Boolean, verbose: Boolean, debug: Boolean) = check(name) {
            var reads = 0; var errs = 0
            val json = withIo("[$name]", queued, verbose) {
                val r = bridge.callAttr("hw_integrity", it, 10, 3, 100, debug).toString()
                reads = it.bulkReadCalls; errs = it.bulkReadErrors; r
            }
            Log.i(TAG, "HWJSON $name $json")
            val j = JSONObject(json)
            val m115 = j.getJSONObject("m115"); val m503 = j.getJSONObject("m503"); val m105 = j.getJSONObject("m105")
            val summary = "reset=${j.getBoolean("unsolicited_reset")} M115 valid ${m115.getInt("valid")}/${m115.getInt("n")} " +
                "distinct=${m115.getInt("distinct_replies")}; M503 valid ${m503.getInt("valid")}/${m503.getInt("n")} " +
                "distinct=${m503.getInt("distinct")} bytes=${m503.get("bytes")}; M105 ok ${m105.getInt("ok")}/${m105.getInt("n")} " +
                "avg=${m105.get("avg_ms")}ms p50=${m105.get("p50_ms")}ms max=${m105.get("max_ms")}ms; bulkRead calls=$reads (-1s=$errs)"
            require(m115.getInt("valid") == m115.getInt("n") && m115.getInt("distinct_replies") == 1 &&
                m503.getInt("valid") == m503.getInt("n") && m503.getInt("distinct") == 1 &&
                m105.getInt("ok") == m105.getInt("n")) { "DATA NOT INTACT: $summary" }
            summary
        }
        if (integrityOnly) {
            // v3: repeatability of direct vs queued reads, no resets involved
            for (k in 1..3) {
                integrity("v3_${k}_direct_quiet", queued = false, verbose = false, debug = false)
                Thread.sleep(1000)
                integrity("v3_${k}_queued_reader", queued = true, verbose = false, debug = false)
                Thread.sleep(1000)
            }
            return results
        }
        integrity("8_integrity_direct_quiet", queued = false, verbose = false, debug = false)
        Thread.sleep(1000)
        integrity("9_integrity_queued_reader", queued = true, verbose = false, debug = false)
        Thread.sleep(1000)
        integrity("10_integrity_direct_verbose_run1_conditions", queued = false, verbose = true, debug = true)
        return results
    }
}
