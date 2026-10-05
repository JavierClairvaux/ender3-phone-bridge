# Chaquopy spike summary

> **The full app built on these spikes is described in `APP_SUMMARY.md`.** Spike-only code now lives in `spike-archive/`.

> **Phase 2 (real phone + real printer) is in `PHASE2_SUMMARY.md`.** The round trip works on hardware. The per-packet read path used here loses RX bytes on the real chip, so use a Kotlin `UsbRequest` reader. Only DTR edges reset the board; opening the device does not.

**Verdict: YES, sound to build the full app on.** Built and run on an Android 14 x86_64 emulator; every check passed in both an instrumented test and the app itself. Not yet run on USB hardware or the arm64 phone.

## Versions

- Chaquopy 17.0.0, Python 3.14.0 inside the app
- AGP 8.7.3, Gradle 8.11.1, Kotlin 2.0.21, JDK 21, compileSdk/targetSdk 35, minSdk 24
- ABIs: `arm64-v8a` (phone) and `x86_64` (emulator)
- Build uses the laptop's `/usr/bin/python3` (3.14); it must match the embedded Python's major.minor
- Emulator: Android 14 default x86_64 image, AVD `spike34`, headless with KVM

## What was run and observed

`./gradlew connectedDebugAndroidTest` reported 1 test, 0 failed. Launching `MainActivity` showed the same 8 PASS lines. Evidence is in `evidence/` (logcat, JUnit XML, screenshot).

- **Python starts in the app:** `Python 3.14.0 on android (x86_64)`.
- **Kotlin calls the real driver:** `ch341_get_divisor(115200)` returned `0xcc03`. The signature is `(speed, limited_prescaler=False)` and it returns the value without bit 0x80; the caller adds +0x80 for chip versions above 0x27 (on-wire value `0xCC83`).
- **Python calls back into Kotlin:** a Kotlin interface object was called 8 times, from the calling thread and from a thread Python created. All calls arrived with correct return values.
- **Byte data survives both directions:** all 256 byte values went Python -> Kotlin -> Python intact.
- **Errors cross cleanly both ways:** a Kotlin exception in a callback is caught in Python as `IllegalStateException`; a Python `CH340Error` arrives in Kotlin as a `PyException`.
- **Full open sequence over Kotlin USB calls:** `KotlinUsbBackend` forwards every USB transfer to a Kotlin `UsbIo` object (in the real app, a wrapper over `UsbDeviceConnection`; in the spike, the fake device `FakeCh340`). The unmodified `CH340Serial`:
  - read the chip version (fake returns 0x31, as the real chip reported)
  - ran quirk detection and serial init (`0xA1`)
  - wrote divisor `0x9A 0x1312 = 0xCC83`
  - wrote line settings `0x2518 = 0xC3`, then the DTR/RTS reset pulse
  - sent `write("M115\n")`
  - its Python-created reader thread called Kotlin's `bulkRead` and got `ok\n`
  - `readline()` returned `b'ok\n'`; `close()` dropped DTR and closed the Kotlin side

  Kotlin logged all 12 calls; the driver's debug output appears in logcat as `python.stderr`.

## Gotchas importing the real driver

- **pyusb import is not a problem.** The driver imports `usb` only when the pyusb backend is created. Top-level imports are all stdlib (ctypes, fcntl, glob, struct, threading) and work in Chaquopy. No stubbing needed. Requesting the pyusb backend fails cleanly with `ModuleNotFoundError: No module named 'usb'`, irrelevant to the app.
- **Python 3.14 needed no changes.**
- **New backend needed.** The driver only knows pyusb and usbfs backends. The spike overrides `_open_backend` in a subclass. For the full app, add a proper option such as `backend=<object>`.
- **Stall errors can't be detected.** Android's `controlTransfer` returns only -1 on failure, so a USB STALL looks like any error. The Kotlin backend reports "not a stall", so the `limited_prescaler` quirk flag never gets set. Doesn't matter at 115200.

## Not tested

- Real USB hardware, a real `UsbDeviceConnection` implementation, the USB permission flow
- Running on the arm64 phone
- Heavy use, e.g. streaming a 4000-line print (multi-thread callbacks worked but weren't load-tested)
- Debug APK is about 35 MB

## Other notes

- The local `ender3-phone-bridge` and phone `printer-bridge-phone` copies of `ch340_serial.py` differ only in the top docstring ("CONFIRMED WORKING" locally vs "NOT YET TESTED" on the phone); code is identical. The local copy was used.
- Toolchain installed in the home directory (no sudo): JDK 21 at `~/.local/opt/jdk-21.0.12.1+1`, Gradle 8.11.1 at `~/.local/opt/gradle-8.11.1`, Android SDK at `~/Android/Sdk`, AVD `spike34`.

## Project layout

- `app/src/main/python/ch340_serial.py`: the real driver, unchanged
- `app/src/main/python/spike_bridge.py`: Kotlin-forwarding USB backend and test helpers
- `app/src/main/java/com/example/chaquopyspike/`: `Interfaces.kt` (`UsbIo`), `FakeCh340.kt`, `Spike.kt` (all checks), `MainActivity.kt`
- `app/src/androidTest/.../SpikeInstrumentedTest.kt`
- `evidence/`: logcat, JUnit XML, screenshot
