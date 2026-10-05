package com.example.chaquopyspike

import android.content.Context
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/** All spike checks, shared by MainActivity (logcat) and the instrumented test. */
object Spike {
    const val TAG = FakeCh340.TAG

    fun py(context: Context): Python {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        return Python.getInstance()
    }

    /** Runs every check, logs PASS/FAIL lines, returns true if all passed. */
    fun runAll(context: Context): List<Pair<String, String>> {
        val py = py(context)
        val bridge = py.getModule("spike_bridge")
        val drv = py.getModule("ch340_serial")
        val results = mutableListOf<Pair<String, String>>()

        fun check(name: String, block: () -> String) {
            val r = try {
                "PASS " + block()
            } catch (e: Throwable) {
                Log.e(TAG, "check $name threw", e)
                "FAIL " + e
            }
            Log.i(TAG, "RESULT $name: $r")
            results.add(name to r)
        }

        check("python_info") { bridge.callAttr("python_info").toString() }

        check("divisor_115200") {
            val v = drv.callAttr("ch341_get_divisor", 115200).toInt()
            require(v == 0xCC03) { "got 0x%04x".format(v) }
            "ch341_get_divisor(115200) = 0x%04x".format(v)
        }

        check("listener_callbacks") {
            val got = mutableListOf<String>()
            val l = object : Listener {
                override fun onEvent(msg: String, i: Int): Int {
                    got.add("$msg@${Thread.currentThread().name}"); return i * 10
                }
                override fun boom(msg: String) = throw IllegalStateException(msg)
            }
            val sum = bridge.callAttr("call_listener", l, 5).toInt()
            val sumT = bridge.callAttr("call_listener_from_thread", l, 3).toInt()
            require(sum == 100 && sumT == 30) { "sums $sum $sumT" }
            require(got.size == 8) { "got ${got.size} callbacks" }
            "8 callbacks received, sums=$sum/$sumT, $got"
        }

        check("binary_bytes") {
            val p = object : BinaryProbe {
                override fun reverse(data: ByteArray): ByteArray {
                    require(data.size == 256 && data[255] == 0xFF.toByte()) { "bad input in Kotlin" }
                    return data.reversedArray()
                }
            }
            "256 bytes Python->Kotlin->Python ok (n=${bridge.callAttr("binary_roundtrip", p).toInt()})"
        }

        check("kotlin_exception_into_python") {
            val l = object : Listener {
                override fun onEvent(msg: String, i: Int) = 0
                override fun boom(msg: String) = throw IllegalStateException(msg)
            }
            val r = bridge.callAttr("catch_kotlin_exception", l).toString()
            require(r.startsWith("caught") && r.contains("thrown-from-kotlin")) { r }
            r
        }

        check("python_exception_into_kotlin") {
            try {
                bridge.callAttr("raise_python_error"); error("no exception")
            } catch (e: PyException) {
                require(e.message!!.contains("CH340Error")) { e.message!! }
                "PyException: ${e.message}"
            }
        }

        check("pyusb_backend_on_android") { bridge.callAttr("probe_pyusb").toString() }

        check("driver_over_kotlin_usb") {
            val dev = FakeCh340()
            val r = bridge.callAttr("driver_roundtrip", dev, "M115").toString()
            val calls = dev.calls.map { it.what }
            require(r.contains("reply=b'ok\\n'")) { r }
            require(calls.first() == "ctrlIn req=0x5f value=0x0000 index=0x0000 len=2") { calls.first() }
            // divisor 0xCC03 | 0x80 (version 0x31 > 0x27) written to reg pair 0x1312
            require("ctrlOut req=0x9a value=0x1312 index=0xcc83" in calls) { "no divisor write: $calls" }
            require("ctrlOut req=0x9a value=0x2518 index=0x00c3" in calls) { "no LCR write" }
            require(calls.any { it.startsWith("bulkWrite M115") })
            require(dev.closed)
            val readerThreads = dev.calls.filter { it.what.startsWith("bulkRead") }.map { it.thread }.toSet()
            "$r; ${calls.size} Kotlin calls; bulkRead thread(s)=$readerThreads"
        }
        return results
    }
}
