package com.example.chaquopyspike

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Phase 2 screen: finds the CH340 (1a86:7523), requests USB permission
 * (system dialog, user must tap), then on the "Run hardware checks" button
 * (or when started with `--ez run true`) runs [HwSpike] against the real
 * printer. Results go to the screen and to logcat (tag ChaquopySpike).
 */
class HwActivity : Activity() {
    private lateinit var tv: TextView
    private lateinit var btn: Button
    private lateinit var usb: UsbManager
    private var device: UsbDevice? = null
    private var autoRun = false
    @Volatile private var running = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Log.i(TAG, "USB permission result: granted=$granted")
            say("USB permission ${if (granted) "GRANTED" else "DENIED"} by user")
            if (granted) onPermission() else btn.isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        autoRun = intent.getBooleanExtra("run", false)
        tv = TextView(this).apply { setPadding(24, 24, 24, 24); textSize = 12f }
        btn = Button(this).apply {
            text = "Run hardware checks (M115/M105 only)"
            isEnabled = false
            setOnClickListener { startRun() }
        }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@HwActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(btn); addView(tv)
            })
        })
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)

        val all = usb.deviceList.values
        say("UsbManager.getDeviceList(): ${all.size} device(s)")
        all.forEach { say("  " + AndroidUsbIo.describe(it)) }
        val d = AndroidUsbIo.findCh340(usb)
        device = d
        if (d == null) { say("CH340 1a86:7523 NOT FOUND"); return }
        if (usb.hasPermission(d)) {
            say("Already have USB permission for CH340")
            onPermission()
        } else {
            say("Requesting USB permission: a system dialog should appear, tap OK/Allow")
            val pi = PendingIntent.getBroadcast(this, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            usb.requestPermission(d, pi)
        }
    }

    private fun onPermission() {
        btn.isEnabled = true
        if (autoRun) startRun()
    }

    private fun startRun() {
        val d = device ?: return
        if (running) return
        running = true
        btn.isEnabled = false
        say("\n=== running hardware checks ===")
        Thread {
            val suite = intent.getStringExtra("suite") ?: "v1"
            say("suite=$suite")
            val r = if (suite == "v2" || suite == "v3")
                        HwSpike.runV2(this, usb, d, { name, res -> say("$name: $res\n") }, integrityOnly = suite == "v3")
                    else HwSpike.runAll(this, usb, d) { name, res -> say("$name: $res\n") }
            val pass = r.count { it.second.startsWith("PASS") }
            Log.i(TAG, "HWDONE $pass/${r.size} pass")
            say("=== DONE: $pass/${r.size} PASS ===")
            running = false
            runOnUiThread { btn.isEnabled = true }
        }.start()
    }

    private fun say(s: String) {
        Log.i(TAG, "UI $s")
        runOnUiThread { tv.append(s + "\n") }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    companion object {
        const val TAG = FakeCh340.TAG
        const val ACTION_USB_PERMISSION = "com.example.chaquopyspike.USB_PERMISSION"
    }
}
