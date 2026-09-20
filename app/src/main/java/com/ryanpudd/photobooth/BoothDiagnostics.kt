package com.ryanpudd.photobooth

/**
 * A point-in-time view of everything worth knowing when the booth misbehaves.
 * Collected by MainActivity (which needs Android APIs), formatted here (which
 * deliberately does not, so it can be unit tested).
 */
data class DiagnosticsSnapshot(
    val boothState: String,
    val cameraOpened: Boolean,
    val cameraPreviewing: Boolean,
    val usbDeviceAttached: Boolean,
    val usbPermissionGranted: Boolean,
    val deviceName: String?,
    val vendorId: Int?,
    val productId: Int?,
    val alertPhase: String,
    val batteryLevelPercent: Int,
    val batteryPluggedRaw: Int,
    val appUptimeMs: Long,
    val lastCameraErrors: List<String>
)

object BoothDiagnostics {

    fun format(snapshot: DiagnosticsSnapshot): String {
        val errors = if (snapshot.lastCameraErrors.isEmpty()) {
            "none"
        } else {
            snapshot.lastCameraErrors.joinToString(" | ")
        }
        val device = snapshot.deviceName ?: "none"
        val vid = snapshot.vendorId?.toString() ?: "none"
        val pid = snapshot.productId?.toString() ?: "none"

        return buildString {
            append("🔍 **Photobooth diagnostics**\n")
            append("```\n")
            append("booth state : ${snapshot.boothState}\n")
            append("alert phase : ${snapshot.alertPhase}\n")
            append("camera      : opened=${snapshot.cameraOpened} previewing=${snapshot.cameraPreviewing}\n")
            append("usb         : attached=${snapshot.usbDeviceAttached} permission=${snapshot.usbPermissionGranted}\n")
            append("device      : $device\n")
            append("ids         : vendor=$vid product=$pid\n")
            append("battery     : ${snapshot.batteryLevelPercent}% plugged=${snapshot.batteryPluggedRaw}\n")
            append("uptime      : ${formatUptime(snapshot.appUptimeMs)}\n")
            append("errors: $errors\n")
            append("```")
        }
    }

    fun formatUptime(uptimeMs: Long): String {
        val totalMinutes = uptimeMs / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return "%dh%02dm".format(hours, minutes)
    }
}
