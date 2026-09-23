package com.ryanpudd.photobooth

/** What MainActivity should do in response to a camera event. */
enum class AlertEffect {
    SHOW_FAULT_SCREEN,
    HIDE_FAULT_SCREEN,
    SEND_DISCONNECT_ALERT,
    SEND_ALL_CLEAR
}

/**
 * Decides when an outage becomes an alert. MainActivity runs one instance for
 * the camera and a second for external power loss.
 *
 * The screen effect appears the instant the thing is lost, so anyone looking
 * at the booth sees it right away. The Discord alert waits [alertDelayMs] so a
 * knocked cable that recovers in a few seconds never reaches anyone's phone.
 *
 * Pure and time-injected: callers pass the clock in. Not thread-safe; drive it
 * from the main thread only.
 */
class CameraAlertState(private val alertDelayMs: Long = DEFAULT_ALERT_DELAY_MS) {

    enum class Phase { HEALTHY, PENDING, ALERTED }

    var phase: Phase = Phase.HEALTHY
        private set

    private var lostAtMs: Long = 0L

    /**
     * USBMonitor fires both onDisconnect and onDettach for one unplug, so this
     * is idempotent: only the first loss while healthy produces effects.
     *
     * Generic on purpose: MainActivity runs a second instance of this class to
     * track external-power loss, not just the camera, so the method names
     * don't reference "camera" specifically.
     */
    fun onLost(nowMs: Long): List<AlertEffect> {
        if (phase != Phase.HEALTHY) return emptyList()
        phase = Phase.PENDING
        lostAtMs = nowMs
        return listOf(AlertEffect.SHOW_FAULT_SCREEN)
    }

    fun onBack(nowMs: Long): List<AlertEffect> {
        return when (phase) {
            Phase.HEALTHY -> emptyList()
            Phase.PENDING -> {
                phase = Phase.HEALTHY
                listOf(AlertEffect.HIDE_FAULT_SCREEN)
            }
            Phase.ALERTED -> {
                phase = Phase.HEALTHY
                listOf(AlertEffect.HIDE_FAULT_SCREEN, AlertEffect.SEND_ALL_CLEAR)
            }
        }
    }

    fun onTick(nowMs: Long): List<AlertEffect> {
        if (phase != Phase.PENDING) return emptyList()
        if (nowMs - lostAtMs < alertDelayMs) return emptyList()
        phase = Phase.ALERTED
        return listOf(AlertEffect.SEND_DISCONNECT_ALERT)
    }

    companion object {
        const val DEFAULT_ALERT_DELAY_MS = 30_000L
    }
}
