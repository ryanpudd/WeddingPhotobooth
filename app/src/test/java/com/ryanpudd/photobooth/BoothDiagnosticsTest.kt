package com.ryanpudd.photobooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoothDiagnosticsTest {

    private fun snapshot(
        boothState: String = "IDLE",
        cameraOpened: Boolean = true,
        cameraPreviewing: Boolean = true,
        usbDeviceAttached: Boolean = true,
        usbPermissionGranted: Boolean = true,
        deviceName: String? = "/dev/bus/usb/001/016",
        vendorId: Int? = 1133,
        productId: Int? = 2142,
        alertPhase: String = "HEALTHY",
        batteryLevelPercent: Int = 87,
        batteryPluggedRaw: Int = 2,
        appUptimeMs: Long = 3_600_000L,
        lastCameraErrors: List<String> = emptyList()
    ) = DiagnosticsSnapshot(
        boothState = boothState,
        cameraOpened = cameraOpened,
        cameraPreviewing = cameraPreviewing,
        usbDeviceAttached = usbDeviceAttached,
        usbPermissionGranted = usbPermissionGranted,
        deviceName = deviceName,
        vendorId = vendorId,
        productId = productId,
        alertPhase = alertPhase,
        batteryLevelPercent = batteryLevelPercent,
        batteryPluggedRaw = batteryPluggedRaw,
        appUptimeMs = appUptimeMs,
        lastCameraErrors = lastCameraErrors
    )

    @Test
    fun format_includesEveryDiagnosticField() {
        val report = BoothDiagnostics.format(snapshot())
        listOf(
            "IDLE", "opened=true", "previewing=true", "attached=true",
            "permission=true", "/dev/bus/usb/001/016", "1133", "2142",
            "HEALTHY", "87%", "plugged=2"
        ).forEach { assertTrue("missing '$it' in:\n$report", report.contains(it)) }
    }

    @Test
    fun format_wrapsInACodeBlockSoDiscordKeepsTheLayout() {
        val report = BoothDiagnostics.format(snapshot())
        assertTrue(report.contains("```"))
    }

    @Test
    fun formatUptime_rendersHoursAndZeroPaddedMinutes() {
        assertEquals("1h05m", BoothDiagnostics.formatUptime(3_600_000L + 300_000L))
        assertEquals("0h00m", BoothDiagnostics.formatUptime(0L))
        assertEquals("12h30m", BoothDiagnostics.formatUptime((12 * 60 + 30) * 60_000L))
    }

    @Test
    fun format_handlesNoDeviceAttached() {
        val report = BoothDiagnostics.format(
            snapshot(usbDeviceAttached = false, deviceName = null, vendorId = null, productId = null)
        )
        assertTrue(report.contains("attached=false"))
        assertTrue(report.contains("none"))
    }

    @Test
    fun format_listsRecentCameraErrors() {
        val report = BoothDiagnostics.format(
            snapshot(lastCameraErrors = listOf("open failed", "timeout"))
        )
        assertTrue(report.contains("open failed"))
        assertTrue(report.contains("timeout"))
    }

    @Test
    fun format_saysNoneWhenThereAreNoErrors() {
        val report = BoothDiagnostics.format(snapshot(lastCameraErrors = emptyList()))
        assertTrue(report.contains("errors: none"))
    }
}
