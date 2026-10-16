package com.javcabr.printerbridge

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javcabr.printerbridge.printer.BoardInfo
import com.javcabr.printerbridge.printer.CdcPrinterConnection
import com.javcabr.printerbridge.printer.ControllerEvents
import com.javcabr.printerbridge.printer.ControllerSettings
import com.javcabr.printerbridge.printer.FakeMarlin
import com.javcabr.printerbridge.printer.PrinterController
import com.javcabr.printerbridge.printer.SafetyLockException
import com.javcabr.printerbridge.usb.SimCdcUsbIo
import com.javcabr.printerbridge.usb.Transport
import com.javcabr.printerbridge.usb.UsbCatalog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Board detection (USB ids + M115) and the native-USB CDC path, all against simulators. */
@RunWith(AndroidJUnit4::class)
class CdcBoardInstrumentedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val TAG = "PrinterBridge.cdctest"
    private val stm32 = UsbCatalog.KNOWN.first { it.id == "stm32-cdc" }

    private class Events : ControllerEvents {
        val finished = CopyOnWriteArrayList<JSONObject>()
        override fun onJobFinished(job: JSONObject) { finished.add(job) }
        override fun onPrinterError(message: String) {}
    }

    private fun waitFor(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(50) }
        fail("timed out waiting for $what")
    }

    private fun readUntilOk(c: CdcPrinterConnection, timeoutMs: Long = 3000): List<String> {
        val lines = mutableListOf<String>()
        while (true) { val l = c.readLine(timeoutMs) ?: break; lines.add(l); if (l.startsWith("ok")) break }
        return lines
    }

    // ---- layer 1: USB descriptors -------------------------------------------------------

    @Test
    fun catalog_identifiesKnownGenericAndUnknownDevices() {
        assertEquals("ch340", UsbCatalog.identify(0x1a86, 0x7523, listOf(0xff))?.id)
        assertEquals(Transport.CH340, UsbCatalog.identify(0x1a86, 0x7523, listOf(0xff))?.transport)
        val skr = UsbCatalog.identify(0x0483, 0x5740, listOf(0x02, 0x0a))
        assertEquals("stm32-cdc", skr?.id)
        assertEquals(Transport.CDC_ACM, skr?.transport)
        assertTrue("the SKR profile raises DTR", skr!!.assertDtr)
        // known ids win even if the descriptors were not readable
        assertEquals("stm32-cdc", UsbCatalog.identify(0x0483, 0x5740, emptyList())?.id)
        // an unlisted CDC-ACM device is still usable, but never gets DTR (Arduino-style bridges reboot on it)
        val generic = UsbCatalog.identify(0x2341, 0x0042, listOf(0x02, 0x0a))
        assertSame(UsbCatalog.GENERIC_CDC, generic)
        assertFalse(generic!!.assertDtr)
        // not a serial device
        assertNull(UsbCatalog.identify(0x1234, 0x5678, listOf(0x08)))
        assertNull(UsbCatalog.identify(0x2341, 0x0042, listOf(0x02)))   // comm interface without a data interface
    }

    // ---- layer 2: M115 ---------------------------------------------------------------------

    private val skrReply = """
        FIRMWARE_NAME:Marlin bugfix-2.0.x (Oct 11 2022 14:32:21) SOURCE_CODE_URL:github.com/MarlinFirmware/Marlin PROTOCOL_VERSION:1.0 MACHINE_TYPE:Ender-3 EXTRUDER_COUNT:1 UUID:cede2a2f-41a2-4748-9b12-c55c62f367ff
        Cap:SERIAL_XON_XOFF:0
        Cap:BINARY_FILE_TRANSFER:0
        Cap:EEPROM:1
        Cap:AUTOLEVEL:0
        Cap:Z_PROBE:0
        Cap:EMERGENCY_PARSER:1
        Cap:SDCARD:1
        Cap:THERMAL_PROTECTION:1
        ok
    """.trimIndent().lines()

    @Test
    fun boardInfo_parsesTheRealSkrReply_andTheOldEnderReply() {
        val skr = BoardInfo.parse(skrReply)!!
        assertEquals("bugfix-2.0.x", skr.version)
        assertEquals("marlin-2.x", skr.family)
        assertEquals("Ender-3", skr.machineType)
        assertEquals("cede2a2f-41a2-4748-9b12-c55c62f367ff", skr.uuid)
        assertTrue(skr.emergencyParser); assertTrue(skr.eeprom); assertTrue(skr.sdCard); assertTrue(skr.thermalProtection)
        assertFalse(skr.autolevel); assertFalse(skr.zProbe)
        assertEquals(8, skr.capabilities.size)
        assertEquals("marlin-2.x", skr.toJson().getString("family"))

        val old = BoardInfo.parse(listOf(
            "FIRMWARE_NAME:Marlin 1.1.6.2 (Github) SOURCE_CODE_URL:https://github.com/MarlinFirmware/Marlin PROTOCOL_VERSION:1.0 MACHINE_TYPE:Ender-3 EXTRUDER_COUNT:1 UUID:cede2a2f-41a2-4748-9b12-c55c62f367ff",
            "Cap:EEPROM:1", "Cap:AUTOLEVEL:0", "Cap:EMERGENCY_PARSER:0", "ok"))!!
        assertEquals("1.1.6.2", old.version)
        assertEquals("marlin-1.1", old.family)
        assertFalse(old.emergencyParser)

        assertEquals("marlin-2.x", BoardInfo.parse(listOf("FIRMWARE_NAME:Marlin 2.1.2.5 SOURCE_CODE_URL:x MACHINE_TYPE:Foo EXTRUDER_COUNT:1"))!!.family)
        assertEquals("unknown", BoardInfo.parse(listOf("FIRMWARE_NAME:Klipper 0.12 SOURCE_CODE_URL:x MACHINE_TYPE:Foo EXTRUDER_COUNT:1"))!!.family)
        assertNull(BoardInfo.parse(listOf("ok")))
        assertNull(BoardInfo.parse(emptyList()))
    }

    @Test
    fun boardInfo_firmwareKind_followsOctoPrintsNameMatching() {
        fun kind(name: String) = BoardInfo.parse(listOf("FIRMWARE_NAME:$name SOURCE_CODE_URL:x MACHINE_TYPE:Foo EXTRUDER_COUNT:1 UUID:u"))!!
        assertEquals("marlin", kind("Marlin bugfix-2.0.x (Oct 11 2022 14:32:21)").kind)
        assertNull(kind("Marlin 1.1.6.2").warning)
        assertEquals("klipper", kind("Klipper v0.12.0").kind)
        assertEquals("repetier", kind("Repetier_1.0.3").kind)
        assertEquals("prusa", kind("Prusa-Firmware 3.9").kind)
        assertEquals("reprapfirmware", kind("RepRapFirmware for Duet").kind)
        assertEquals("unknown", kind("Something else").kind)
        assertTrue(kind("Klipper v0.12.0").warning!!.contains("not Marlin"))
        assertTrue(kind("Marlin 2.1.2").complete)
        assertFalse(BoardInfo.parse(listOf("FIRMWARE_NAME:Marlin 2.0 SOURCE_CODE_URL:x"))!!.complete)   // identity line cut short
    }

    @Test
    fun usbHint_readsTheDescriptorStrings_likeKlipperDoes() {
        val h = UsbCatalog.FirmwareHint.values().associateBy { it.name }
        // stock Marlin on the SKR, as read from the real board
        assertEquals(h["MARLIN"], UsbCatalog.hint(0x0483, 0x5740, "STMicroelectronics", "MARLIN_STM32G0B1RE CDC in FS Mode"))
        // Klipper names itself in the manufacturer string (usb-Klipper_stm32g0b1xx_<serial> on Linux)
        assertEquals(h["KLIPPER"], UsbCatalog.hint(0x1d50, 0x614e, "Klipper", "stm32g0b1xx"))
        assertEquals(h["BOOTLOADER"], UsbCatalog.hint(0x0483, 0xdf11, "STMicroelectronics", "STM32  BOOTLOADER"))
        assertEquals(h["BOOTLOADER"], UsbCatalog.hint(0x1d50, 0x6177, "katapult", "stm32g0b1xx"))
        assertEquals(h["UNKNOWN"], UsbCatalog.hint(0x1a86, 0x7523, null, "USB Serial"))
        assertEquals(h["UNKNOWN"], UsbCatalog.hint(0x1a86, 0x7523, null, null))
        assertNull(UsbCatalog.refusal(UsbCatalog.FirmwareHint.MARLIN))
        assertNull(UsbCatalog.refusal(UsbCatalog.FirmwareHint.UNKNOWN))
        assertTrue(UsbCatalog.refusal(UsbCatalog.FirmwareHint.KLIPPER)!!.contains("Klipper"))
        assertTrue(UsbCatalog.refusal(UsbCatalog.FirmwareHint.BOOTLOADER)!!.contains("bootloader"))
    }

    @Test
    fun simulatorIdentities_matchWhatTheBoardsReport() {
        val m = FakeMarlin()
        fun m115(): List<String> {
            val out = mutableListOf<String>()
            m.attach { out.add(it) }
            m.feed("M115")
            waitFor(3000, "M115 reply") { out.lastOrNull() == "ok" }
            m.detach()
            return out
        }
        val old = BoardInfo.parse(m115())!!
        assertFalse(old.emergencyParser)
        m.marlin2 = true
        val skr = BoardInfo.parse(m115())!!
        assertEquals("marlin-2.x", skr.family)
        assertTrue(skr.emergencyParser)
        assertTrue("caps ${skr.capabilities.size}", skr.capabilities.size >= 25)
        assertEquals("Ender-3", skr.machineType)
    }

    // ---- the CDC connection over the simulated board ------------------------------------

    @Test
    fun cdcConnection_assertsDtrOnce_neverResets_andReconnects() {
        val marlin = FakeMarlin().apply { lineDelayMs = 2; timeScale = 20.0 }
        var io: SimCdcUsbIo? = null
        fun conn(lock: Boolean) = CdcPrinterConnection("sim-cdc", stm32, { SimCdcUsbIo(marlin, requireDtr = true).also { io = it } }, { lock }, 300)

        val c1 = conn(lock = true)
        c1.open()
        val info = c1.info()
        Log.i(TAG, "open info: $info")
        assertEquals("cdc-acm", info["transport"])
        assertEquals("stm32-cdc", info["usb_profile"])
        assertEquals(false, info["reset_detected_on_open"])
        assertEquals(true, info["dtr_asserted"])
        assertEquals(listOf("line_coding:115200", "lines:dtr=true,rts=true"), io!!.controlLog.toList())
        c1.writeLine("M115")
        val lines = readUntilOk(c1)
        assertTrue("M115 reply: $lines", lines.first().startsWith("FIRMWARE_NAME:Marlin bugfix-2.0.x") && lines.last() == "ok")
        // the safety lock refuses motion before anything reaches USB
        try { c1.writeLine("G28"); fail("G28 not refused") } catch (e: SafetyLockException) {}
        try { c1.writeLine("N5 M104 S200*12"); fail("M104 not refused") } catch (e: SafetyLockException) {}
        try { c1.resetBoard(); fail("reset not refused") } catch (e: SafetyLockException) {}
        assertEquals(1L, c1.linesWritten)
        c1.close()
        assertEquals("lines:dtr=false,rts=false", io!!.controlLog.last())
        assertEquals(0, marlin.bootCount)

        // reconnect with the lock off: the board kept its state (nothing rebooted it)
        marlin.hotendTarget = 123.0
        val c2 = conn(lock = false)
        c2.open()
        assertEquals(false, c2.info()["reset_detected_on_open"])
        c2.writeLine("M105")
        val r = readUntilOk(c2).first { it.contains("/123.00") }
        assertTrue(r.isNotEmpty())
        // a native-USB board has no DTR reset
        try { c2.resetBoard(); fail("reset should be unavailable") } catch (e: java.io.IOException) { assertFalse(e is SafetyLockException) }
        c2.close()
        assertEquals(0, marlin.bootCount)
        // io is the second session's: one setup, one raise, one lower, no other line-state changes
        assertEquals(listOf("line_coding:115200", "lines:dtr=true,rts=true", "lines:dtr=false,rts=false"), io!!.controlLog.toList())
    }

    @Test
    fun cdcConnection_withoutDtr_staysSilentOnAPortThatNeedsIt_andFlagsBootBanner() {
        // The generic profile never raises DTR; a port that needs it stays silent (documents why the SKR profile asserts it).
        val marlin = FakeMarlin().apply { lineDelayMs = 2 }
        val c = CdcPrinterConnection("sim-cdc", UsbCatalog.GENERIC_CDC, { SimCdcUsbIo(marlin, requireDtr = true) }, { false }, 200)
        c.open()
        assertEquals(false, c.info()["dtr_asserted"])
        c.writeLine("M115")
        assertNull("no reply expected without DTR", c.readLine(1000))
        c.close()

        // boot text found right after open is reported (the board had just reset or been powered up)
        val c2 = CdcPrinterConnection("sim-cdc", stm32,
            { SimCdcUsbIo(marlin, requireDtr = false, staleOnOpen = "start\necho: External Reset\n".toByteArray()) }, { false }, 300)
        c2.open()
        assertEquals(true, c2.info()["reset_detected_on_open"])
        assertTrue((c2.info()["boot_markers_on_open"] as String).contains("start"))
        c2.close()
    }

    @Test
    fun cdcConnection_failsCleanlyWhenTheDeviceHasNoCdcControl() {
        val c = CdcPrinterConnection("sim-cdc", stm32,
            { com.javcabr.printerbridge.usb.SimCh340UsbIo(FakeMarlin()) }, { false }, 100)
        try { c.open(); fail("open should fail on a non-CDC transport") } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("CDC")) }
        assertFalse(c.isOpen)
    }

    @Test
    fun controller_printsOverSimCdc_withResends_andReportsTheBoard() {
        val ev = Events()
        val dir = File(ctx.cacheDir, "t_simcdc").apply { deleteRecursively(); mkdirs() }
        val gdir = File(dir, "gcode").apply { mkdirs() }
        val ctl = PrinterController(File(dir, "history.json"), gdir, ev,
            ControllerSettings(idleTempPoll = false, idleShutdownS = 300, silenceMs = 5000))
        val marlin = FakeMarlin().apply { lineDelayMs = 1; timeScale = 50.0; injectResendEvery = 37 }
        File(gdir, "cdc.gcode").printWriter().use { w ->
            w.println("M104 S200"); w.println("M140 S60"); w.println("G28")
            for (i in 0 until 400) w.println("G1 X${i % 200} Y${(i * 7) % 200} E${i * 0.01}")
            w.println("M104 S0"); w.println("M140 S0")
        }
        ctl.connect(CdcPrinterConnection("sim-cdc", stm32, { SimCdcUsbIo(marlin, requireDtr = true) }, { false }, 300))

        val conn = ctl.connectionJson()
        Log.i(TAG, "connection: $conn")
        assertTrue(conn.getString("firmware").contains("bugfix-2.0.x"))
        val board = conn.getJSONObject("board")
        assertEquals("marlin-2.x", board.getString("family"))
        assertTrue(board.getBoolean("emergency_parser"))
        assertEquals("cdc-acm", conn.getJSONObject("details").getString("transport"))

        ctl.startPrint("cdc.gcode")
        waitFor(120_000, "job done") { ev.finished.isNotEmpty() }
        val j = ev.finished.first()
        Log.i(TAG, "sim-cdc job: $j")
        assertEquals("done", j.getString("state"))
        assertEquals(405, j.getInt("lines_done"))
        assertTrue("resends ${j.getInt("resends")}", j.getInt("resends") >= 5)
        assertEquals(0.0, marlin.hotendTarget, 0.001)
        assertEquals(0, marlin.bootCount)

        // connecting a different board clears the old identity
        ctl.disconnect()
        marlin.marlin2 = false
        assertNotNull(ctl.connectionJson())
    }
}
