package com.javcabr.printerbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.javcabr.printerbridge.notify.TelegramNotifier
import com.javcabr.printerbridge.printer.ControllerEvents
import com.javcabr.printerbridge.printer.FakeMarlin
import com.javcabr.printerbridge.printer.FakePrinterConnection
import com.javcabr.printerbridge.printer.PrinterConnection
import com.javcabr.printerbridge.printer.PrinterController
import com.javcabr.printerbridge.printer.RealPrinterConnection
import com.javcabr.printerbridge.server.ApiServer
import com.javcabr.printerbridge.server.BridgeHost
import com.javcabr.printerbridge.ui.MainActivity
import com.javcabr.printerbridge.usb.AndroidUsbIo
import com.javcabr.printerbridge.usb.SimCh340UsbIo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Foreground service (type connectedDevice) that owns the PrinterController
 * (and through it the active PrinterConnection and all job state), the
 * embedded HTTP server (dashboard + REST + MCP) and Telegram notifications.
 *
 * Survives the activity going away and screen-off/Doze: it is a foreground
 * service (exempt from Doze's network and wake-lock restrictions), holds a
 * partial wake lock (always if keep_awake, else only while a job is active)
 * and a Wi-Fi lock, and is START_STICKY.
 *
 * Connection management relies on the DTR-safe real connection: connecting,
 * reconnecting (after app restart, USB re-attach, backend switch) and
 * disconnecting never reboot Marlin, so no re-homing is needed.
 *
 * adb control (all extras optional):
 *   am start-foreground-service -n <pkg>/com.javcabr.printerbridge.service.PrinterService \
 *     --es backend fake|real|sim-usb --ez idle_temp_poll false --el real_settle_ms 4000 \
 *     --ez auto_connect false --ez connect true | --ez disconnect true | --ez stop true
 */
class PrinterService : Service(), ControllerEvents, BridgeHost {
    override lateinit var controller: PrinterController
    override lateinit var settings: AppSettings
    override lateinit var telegram: TelegramNotifier
    // HTTP/HTTPS listeners; (re)configured only on the "net-config" thread (see reconfigureServers)
    lateinit var tls: TlsManager
    @Volatile private var httpServer: ApiServer? = null
    @Volatile private var httpsServer: ApiServer? = null
    @Volatile private var httpError: String? = null
    @Volatile private var httpsError: String? = null
    private var httpKey: String? = null
    @Volatile private var httpsCertStamp = 0L
    private var httpsKey: String? = null
    private val netExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "net-config").apply { isDaemon = true } }
    private lateinit var usb: UsbManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val createdAt = SystemClock.elapsedRealtime()
    @Volatile private var permissionRequested = false
    private var lastNotifText = ""

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = deviceExtra(i)
            val isCh340 = dev != null && dev.vendorId == AndroidUsbIo.VID && dev.productId == AndroidUsbIo.PID
            when (i.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (isCh340) {
                    Log.w(TAG, "CH340 detached")
                    if (controller.connection()?.kind == "real") controller.onDeviceDetached("USB device detached")
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (isCh340 && settings.backend == "real" && settings.autoConnect) {
                    Log.i(TAG, "CH340 attached: auto-connecting"); connectAsync()
                }
                ACTION_USB_PERMISSION -> {
                    val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "USB permission result: granted=$granted")
                    permissionRequested = false
                    if (granted && settings.backend == "real") connectAsync()
                }
            }
        }
    }

    private val ticker = object : Runnable {
        var n = 0L
        override fun run() {
            updateNotification()
            updateLocks()
            // hot-reload HTTPS when the keystore file was replaced (ACME renewal, regeneration, adb push)
            if (::tls.isInitialized && settings.tlsEnabled && httpsServer != null && tls.certStamp() != httpsCertStamp)
                reconfigureServers("certificate changed")
            if (n++ % 15 == 0L) heartbeat()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = AppSettings(this)
        settings.importConfigFile(getExternalFilesDir(null))
        telegram = TelegramNotifier(settings)
        tls = TlsManager(this, settings, settings.secrets)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        createChannel()
        val n = buildNotification("Starting", null)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIF_ID, n)
        controller = PrinterController(File(filesDir, "history.json"), File(filesDir, "gcode").apply { mkdirs() }, this, settings.controllerSettings())
        applyRuntimeSettings()
        reconfigureServers("start")
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, RECEIVER_NOT_EXPORTED) else registerReceiver(usbReceiver, f)
        handler.post(ticker)
        Log.i(TAG, "service created: backend=${settings.backend} http=${settings.httpEnabled}@${settings.httpBind}:${settings.httpPort} " +
            "tls=${settings.tlsEnabled}:${settings.httpsPort} api_token_set=${settings.apiToken.isNotEmpty()}")
    }

    private var firstStart = true

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { i ->
            if (i.getBooleanExtra("stop", false)) { stopSelf(); return START_NOT_STICKY }
            i.getStringExtra("backend")?.let { settings.backend = it }
            if (i.hasExtra("idle_temp_poll")) settings.idleTempPoll = i.getBooleanExtra("idle_temp_poll", true)
            if (i.hasExtra("real_settle_ms")) settings.realSettleMs = i.getLongExtra("real_settle_ms", 750)
            applyRuntimeSettings()
            if (i.getBooleanExtra("disconnect", false)) Thread { runCatching { disconnectPrinter() }.onFailure { Log.w(TAG, "disconnect: $it") } }.start()
            if (i.hasExtra("auto_connect")) settings.autoConnect = i.getBooleanExtra("auto_connect", true)
            if (i.getBooleanExtra("connect", false)) { firstStart = false; connectAsync() }
        }
        // Initial auto-connect happens here (not in onCreate) so start extras apply first.
        if (firstStart) { firstStart = false; if (settings.autoConnect) connectAsync() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "service destroyed")
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        netExec.execute { httpServer?.stop(); httpsServer?.stop() }
        netExec.shutdown()
        Thread { runCatching { controller.disconnect() } }.start()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ BridgeHost

    fun makeConnection(backend: String): PrinterConnection = when (backend) {
        "fake" -> FakePrinterConnection(FakeMarlin.shared)
        "sim-usb" -> RealPrinterConnection(this, "sim-usb",
            { SimCh340UsbIo(FakeMarlin.shared, staleOnOpen = byteArrayOf(0xf8.toByte(), 0x80.toByte(), 0x78, 0xfe.toByte(), 0x00, 0x86.toByte(), 0x0e)) },
            { false }, settings.realSettleMs)
        "real" -> RealPrinterConnection(this, "real", {
            val d = AndroidUsbIo.findCh340(usb) ?: throw IOException("CH340 (1a86:7523) not attached")
            if (!usb.hasPermission(d)) { requestUsbPermission(d); throw IllegalStateException("USB permission not granted yet: accept the dialog on the phone, then connect again") }
            AndroidUsbIo(usb, d)
        }, { settings.realSafetyLock }, settings.realSettleMs)
        else -> throw IllegalArgumentException("unknown backend $backend")
    }

    override fun connectBackend(backend: String?): JSONObject {
        if (backend != null) settings.backend = backend
        applyRuntimeSettings()
        return controller.connect(makeConnection(settings.backend))
    }

    fun connectAsync() = Thread {
        try { connectBackend(null) } catch (e: Exception) { Log.w(TAG, "connect (${settings.backend}) failed: ${e.message}") }
    }.start()

    override fun disconnectPrinter(): JSONObject = controller.disconnect()

    fun requestUsbPermission(d: UsbDevice) {
        if (permissionRequested) return
        permissionRequested = true
        Log.i(TAG, "requesting USB permission for ${AndroidUsbIo.describe(d)}")
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        usb.requestPermission(d, pi)
    }

    override fun simulateError(kind: String): JSONObject {
        val c = controller.connection() ?: throw IllegalStateException("not connected")
        when {
            c is FakePrinterConnection && kind == "disconnect" -> c.simulateDisconnect()
            c.kind == "fake" || c.kind == "sim-usb" -> FakeMarlin.shared.triggerError(kind)
            else -> throw IllegalStateException("simulated errors are only available on the fake / sim-usb backends")
        }
        return JSONObject().put("triggered", kind).put("backend", c.kind)
    }

    override fun applyRuntimeSettings() {
        // listener changes (ports, bind, TLS on/off) apply shortly after, so the HTTP response
        // carrying the config change still gets out before its listener may restart
        if (::tls.isInitialized) handler.postDelayed({ reconfigureServers("settings") }, 400)
        controller.settings = settings.controllerSettings()
        FakeMarlin.shared.lineDelayMs = settings.fakeLineDelayMs
        FakeMarlin.shared.timeScale = settings.fakeTimeScale
        FakeMarlin.shared.injectResendEvery = settings.fakeInjectResendEvery
    }

    override fun serviceInfo(): JSONObject {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val port = settings.httpPort
        val urls = mutableListOf<String>()
        if (httpServer != null) urls += if (settings.httpBind == "127.0.0.1") listOf("http://127.0.0.1:$port/") else ipv4Addresses().map { "http://$it:$port/" }
        if (httpsServer != null) {
            if (settings.tlsDomain.isNotEmpty()) urls += "https://${settings.tlsDomain}:${settings.httpsPort}/"
            urls += ipv4Addresses().map { "https://$it:${settings.httpsPort}/" }
        }
        return JSONObject()
            .put("uptime_s", (SystemClock.elapsedRealtime() - createdAt) / 1000)
            .put("backend_setting", settings.backend)
            .put("http_port", port).put("http_enabled", settings.httpEnabled).put("http_bind", settings.httpBind)
            .put("http_running", httpServer != null).put("http_error", httpError ?: JSONObject.NULL)
            .put("tls", tls.shortJson(httpsServer != null))
            .put("urls", JSONArray(urls))
            .put("wake_lock_held", wakeLock?.isHeld == true).put("wifi_lock_held", wifiLock?.isHeld == true)
            .put("device_idle_mode", pm.isDeviceIdleMode).put("interactive", pm.isInteractive)
            .put("ignoring_battery_optimizations", pm.isIgnoringBatteryOptimizations(packageName))
            .put("usb_permission", AndroidUsbIo.findCh340(usb)?.let { usb.hasPermission(it) } ?: JSONObject.NULL)
            .put("real_safety_lock", settings.realSafetyLock)
    }

    // ------------------------------------------------------------------ ControllerEvents

    override fun onJobFinished(job: JSONObject) {
        val st = job.optString("state")
        if (st == "done" || st == "error" || (st == "cancelled" && settings.notifyOnCancel))
            telegram.notify("job_$st", TelegramNotifier.jobMessage(job))
        handler.post { updateNotification(force = true) }
    }

    override fun onPrinterError(message: String) {
        telegram.notify("printer_error", "[Printer Bridge] Printer error: $message")
        handler.post { updateNotification(force = true) }
    }

    override fun onStateChanged() { handler.post { updateNotification() } }

    // ------------------------------------------------------------------ internals

    /**
     * Brings the listeners in line with the settings and the current certificate. Runs on the
     * single "net-config" thread, never on printer-io, so a print job is unaffected. Restarting
     * the HTTPS listener (hot reload) drops in-flight HTTPS connections for a moment only.
     */
    fun reconfigureServers(reason: String) {
        if (netExec.isShutdown) return
        netExec.execute {
            try { doReconfigure(reason) } catch (t: Throwable) { Log.e(TAG, "reconfigure ($reason) failed: ${t.javaClass.simpleName}: ${t.message}") }
        }
    }

    private fun doReconfigure(reason: String) {
        val timeout = fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT
        // plain HTTP
        val hk = "${settings.httpBind}:${settings.httpPort}"
        if (!settings.httpEnabled || hk != httpKey) {
            httpServer?.let { it.stop(); Log.i(TAG, "HTTP listener stopped ($reason)") }
            httpServer = null; httpKey = null
        }
        if (settings.httpEnabled && httpServer == null) {
            try {
                httpServer = ApiServer(this, settings.httpBind, settings.httpPort, this).also { it.start(timeout, false) }
                httpKey = hk; httpError = null
                Log.i(TAG, "HTTP listener on $hk ($reason)")
            } catch (e: IOException) { httpError = e.toString(); Log.e(TAG, "HTTP listener failed: $e") }
        }
        // HTTPS
        if (settings.tlsEnabled) {
            try { tls.ensureCert() } catch (e: Exception) { httpsError = "certificate: ${e.javaClass.simpleName}"; Log.e(TAG, "self-signed certificate failed", e) }
        }
        val wantHttps = settings.tlsEnabled && tls.hasCert()
        val stamp = tls.certStamp()
        val sk = "${settings.httpsPort}:$stamp"
        if (!wantHttps || sk != httpsKey) {
            httpsServer?.let { it.stop(); Log.i(TAG, "HTTPS listener stopped ($reason)") }
            httpsServer = null; httpsKey = null
        }
        if (wantHttps && httpsServer == null) {
            try {
                val srv = ApiServer(this, "0.0.0.0", settings.httpsPort, this, secure = true)
                srv.makeSecure(tls.sslServerSocketFactory(), null)
                srv.start(timeout, false)
                httpsServer = srv; httpsKey = sk; httpsCertStamp = stamp; httpsError = null
                val info = tls.certInfo()
                Log.i(TAG, "HTTPS listener on 0.0.0.0:${settings.httpsPort} ($reason), cert ${info?.optString("subject_cn")} " +
                    "issuer ${info?.optString("issuer_cn")} serial ${info?.optString("serial")} expires ${info?.optString("not_after")}")
            } catch (e: Exception) { httpsError = "${e.javaClass.simpleName}: ${e.message}"; Log.e(TAG, "HTTPS listener failed: $httpsError") }
        }
    }

    // ------------------------------------------------------------------ TLS (BridgeHost)

    override fun tlsStatus(): JSONObject = tls.statusJson(httpsServer != null, httpsError)

    override fun tlsSelfSigned(confirm: Boolean): JSONObject {
        if (tls.source() == "acme" && !confirm)
            throw IllegalArgumentException("the current certificate was issued by ACME; send {\"confirm\": true} to replace it with a self-signed one")
        tls.makeSelfSigned()
        reconfigureServers("self-signed certificate regenerated")
        return tlsStatus()
    }

    override fun tlsIssue(): JSONObject = throw IllegalStateException("ACME issuance is not available in this build")

    override fun tlsChainPem(): String? = if (tls.hasCert()) tls.chainPem() else null
    override fun tlsCaPem(): String? = if (tls.hasCert()) tls.caPem() else null

    private fun updateLocks() {
        val jobActive = controller.statusJson().optJSONObject("job")?.optString("state") in setOf("queued", "printing", "pausing", "paused", "resuming")
        val want = settings.keepAwake || jobActive
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PrinterBridge:service").apply { setReferenceCounted(false) }
        if (want && wakeLock?.isHeld != true) { wakeLock?.acquire(); Log.i(TAG, "wake lock acquired") }
        if (!want && wakeLock?.isHeld == true) { wakeLock?.release(); Log.i(TAG, "wake lock released") }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PrinterBridge:wifi").apply { setReferenceCounted(false) }
        }
        if (wifiLock?.isHeld != true) wifiLock?.acquire()
    }

    private fun heartbeat() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val s = controller.statusJson()
        val j = s.optJSONObject("job")
        Log.i(TAG, "HEARTBEAT uptime=${(SystemClock.elapsedRealtime() - createdAt) / 1000}s idle=${pm.isDeviceIdleMode} " +
            "interactive=${pm.isInteractive} conn=${s.getJSONObject("connection").optString("state")} " +
            "job=${j?.optString("state")} ${j?.optInt("lines_done")}/${j?.optInt("lines_total")} (${j?.optDouble("progress_pct")}%) " +
            "hotend=${s.getJSONObject("temps").opt("hotend")} wake=${wakeLock?.isHeld}")
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Printer status", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(text: String, progress: Int?): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Printer Bridge")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .apply { if (progress != null) setProgress(1000, progress, false) }
            .build()
    }

    private fun updateNotification(force: Boolean = false) {
        if (!::controller.isInitialized) return
        val s = controller.statusJson()
        val conn = s.getJSONObject("connection")
        val j = s.optJSONObject("job")
        val t = s.getJSONObject("temps")
        fun temp(k: String) = if (t.isNull(k)) "-" else "%.0f".format(t.getDouble(k))
        val temps = "T ${temp("hotend")}/${temp("hotend_target")} B ${temp("bed")}/${temp("bed_target")}"
        val text = if (j != null && j.optString("state") in setOf("queued", "printing", "pausing", "paused", "resuming"))
            "${j.optString("state")} ${j.optString("file")} ${j.optDouble("progress_pct")}% | $temps"
        else "${conn.optString("backend", "-")} ${conn.optString("state")}${j?.let { " | last job ${it.optString("state")}" } ?: ""} | $temps"
        if (!force && text == lastNotifText) return
        lastNotifText = text
        val prog = if (j != null && j.optString("state") in setOf("printing", "pausing", "paused", "resuming")) (j.optDouble("progress_pct") * 10).toInt() else null
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text, prog))
    }

    private fun deviceExtra(i: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    companion object {
        const val TAG = "PrinterBridge.svc"
        const val CHANNEL = "printer"
        const val NOTIF_ID = 1
        const val ACTION_USB_PERMISSION = "com.javcabr.printerbridge.USB_PERMISSION"
        @Volatile var instance: PrinterService? = null

        fun ipv4Addresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().map { it.hostAddress ?: "" }
        } catch (e: Exception) { emptyList() }
    }
}
