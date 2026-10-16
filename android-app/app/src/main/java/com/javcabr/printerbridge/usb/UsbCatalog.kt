package com.javcabr.printerbridge.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/** How the app talks to a USB serial device. */
enum class Transport(val wire: String) {
    /** WCH CH340 behind the stock Ender 3 board: vendor control requests, driven by ch340_serial.py. */
    CH340("ch340"),
    /** CDC-ACM (native USB, e.g. STM32 boards): plain bulk endpoints plus class requests, no Python driver. */
    CDC_ACM("cdc-acm"),
}

/**
 * What the app knows about a kind of USB serial device before it has spoken to it.
 * [assertDtr]: raise DTR+RTS after opening a CDC port. Native-USB Marlin boards only
 * talk when the host has opened the port, and a DTR edge does not reset them. The
 * generic CDC entry leaves it off, because an Arduino-style USB-serial bridge reboots
 * its board on a DTR edge.
 */
data class UsbProfile(
    val id: String,
    val name: String,
    val transport: Transport,
    val vid: Int?,
    val pid: Int?,
    val assertDtr: Boolean = false,
    /** True if this device has been tried on real hardware with this app's code. */
    val verified: Boolean = false,
)

/**
 * Maps USB descriptors to a [UsbProfile]. Known vendor/product ids first, then a
 * generic CDC-ACM fallback (communications interface class 0x02 plus a data
 * interface class 0x0A). The firmware behind it is identified later, from M115.
 */
object UsbCatalog {
    val KNOWN = listOf(
        UsbProfile("ch340", "WCH CH340 (stock Ender 3 board)", Transport.CH340, 0x1a86, 0x7523, verified = true),
        UsbProfile("stm32-cdc", "STMicroelectronics Virtual COM Port (SKR Mini E3 V3.0)", Transport.CDC_ACM,
            0x0483, 0x5740, assertDtr = true),
    )
    val GENERIC_CDC = UsbProfile("cdc-generic", "Generic CDC-ACM serial device", Transport.CDC_ACM, null, null)

    /** Pure descriptor logic, so tests can run it without Android classes. */
    fun identify(vid: Int, pid: Int, interfaceClasses: List<Int>): UsbProfile? =
        KNOWN.firstOrNull { it.vid == vid && it.pid == pid }
            ?: if (UsbConstants.USB_CLASS_COMM in interfaceClasses && UsbConstants.USB_CLASS_CDC_DATA in interfaceClasses)
                GENERIC_CDC else null

    fun identify(d: UsbDevice): UsbProfile? =
        identify(d.vendorId, d.productId, (0 until d.interfaceCount).map { d.getInterface(it).interfaceClass })

    /**
     * What the USB descriptor strings say about the firmware behind a device, before anything is
     * sent to it. Klipper boards enumerate as manufacturer "Klipper" with the MCU as product
     * (`/dev/serial/by-id/usb-Klipper_stm32g0b1xx_<serial>` on Linux); a board still on stock Marlin
     * reports `MARLIN_STM32G0B1RE CDC in FS Mode` (read from the SKR Mini E3 V3.0 on 2026-10-14).
     */
    enum class FirmwareHint { MARLIN, KLIPPER, BOOTLOADER, UNKNOWN }

    fun hint(vid: Int, pid: Int, manufacturer: String?, product: String?): FirmwareHint {
        val text = "${manufacturer ?: ""} ${product ?: ""}".lowercase()
        return when {
            vid == 0x0483 && pid == 0xdf11 -> FirmwareHint.BOOTLOADER        // STM32 ROM DFU bootloader
            "klipper" in text -> FirmwareHint.KLIPPER
            "katapult" in text || "canboot" in text -> FirmwareHint.BOOTLOADER   // Klipper bootloaders (names from memory, not seen here)
            "marlin" in text -> FirmwareHint.MARLIN
            else -> FirmwareHint.UNKNOWN
        }
    }

    fun hint(d: UsbDevice): FirmwareHint = hint(d.vendorId, d.productId, d.manufacturerName, d.productName)

    /** Why a device with this [hint] can't be driven by the Marlin backend, or null if it can. */
    fun refusal(hint: FirmwareHint): String? = when (hint) {
        FirmwareHint.KLIPPER -> "this board runs Klipper, not Marlin: the Marlin backend cannot drive it (it needs a Klipper/Moonraker backend, or reflash Marlin)"
        FirmwareHint.BOOTLOADER -> "this board is in bootloader/DFU mode, not running a printer firmware: power-cycle it, or flash firmware first"
        else -> null
    }

    class Found(val device: UsbDevice, val profile: UsbProfile) {
        val hint: FirmwareHint get() = hint(device)
    }

    /** The first attached device we can drive. Known ids win over the generic CDC fallback. */
    fun find(manager: UsbManager): Found? {
        val all = manager.deviceList.values.mapNotNull { d -> identify(d)?.let { Found(d, it) } }
        return all.firstOrNull { it.profile !== GENERIC_CDC } ?: all.firstOrNull()
    }

    /** A device that is plugged in but not drivable (bootloader/DFU), for a clearer "nothing attached" error. */
    fun findRefused(manager: UsbManager): Pair<UsbDevice, String>? =
        manager.deviceList.values.firstNotNullOfOrNull { d -> refusal(hint(d))?.let { d to it } }
}
