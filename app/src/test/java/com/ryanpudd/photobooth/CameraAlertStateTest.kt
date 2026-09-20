package com.ryanpudd.photobooth

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraAlertStateTest {

    private val delay = 30_000L

    @Test
    fun cameraLost_showsFaultScreenImmediatelyButSendsNothing() {
        val state = CameraAlertState(delay)
        assertEquals(listOf(AlertEffect.SHOW_FAULT_SCREEN), state.onCameraLost(1_000L))
        assertEquals(CameraAlertState.Phase.PENDING, state.phase)
    }

    @Test
    fun repeatedLossEventsAreIdempotent() {
        // USBMonitor fires both onDisconnect and onDettach for a single unplug.
        val state = CameraAlertState(delay)
        state.onCameraLost(1_000L)
        assertEquals(emptyList<AlertEffect>(), state.onCameraLost(1_100L))
        assertEquals(emptyList<AlertEffect>(), state.onCameraLost(1_200L))
    }

    @Test
    fun tickBeforeDelayElapses_sendsNothing() {
        val state = CameraAlertState(delay)
        state.onCameraLost(1_000L)
        assertEquals(emptyList<AlertEffect>(), state.onTick(20_000L))
        assertEquals(CameraAlertState.Phase.PENDING, state.phase)
    }

    @Test
    fun tickAfterDelayElapses_sendsTheAlertExactlyOnce() {
        val state = CameraAlertState(delay)
        state.onCameraLost(1_000L)
        assertEquals(listOf(AlertEffect.SEND_DISCONNECT_ALERT), state.onTick(31_000L))
        assertEquals(CameraAlertState.Phase.ALERTED, state.phase)
        assertEquals(emptyList<AlertEffect>(), state.onTick(90_000L))
        assertEquals(emptyList<AlertEffect>(), state.onTick(600_000L))
    }

    @Test
    fun reconnectWithinDebounce_hidesScreenAndSendsNothing() {
        // A kicked cable that recovers in 3 seconds must never ping anyone's phone.
        val state = CameraAlertState(delay)
        state.onCameraLost(1_000L)
        assertEquals(listOf(AlertEffect.HIDE_FAULT_SCREEN), state.onCameraBack(4_000L))
        assertEquals(CameraAlertState.Phase.HEALTHY, state.phase)
    }

    @Test
    fun reconnectAfterAlert_hidesScreenAndSendsAllClear() {
        val state = CameraAlertState(delay)
        state.onCameraLost(1_000L)
        state.onTick(31_000L)
        assertEquals(
            listOf(AlertEffect.HIDE_FAULT_SCREEN, AlertEffect.SEND_ALL_CLEAR),
            state.onCameraBack(60_000L)
        )
        assertEquals(CameraAlertState.Phase.HEALTHY, state.phase)
    }

    @Test
    fun reconnectWhenAlreadyHealthy_doesNothing() {
        val state = CameraAlertState(delay)
        assertEquals(emptyList<AlertEffect>(), state.onCameraBack(1_000L))
        assertEquals(CameraAlertState.Phase.HEALTHY, state.phase)
    }

    @Test
    fun tickWhenHealthy_doesNothing() {
        val state = CameraAlertState(delay)
        assertEquals(emptyList<AlertEffect>(), state.onTick(500_000L))
    }

    @Test
    fun secondOutageAfterRecovery_alertsAgain() {
        val state = CameraAlertState(delay)
        state.onCameraLost(1_000L)
        state.onTick(31_000L)
        state.onCameraBack(60_000L)

        assertEquals(listOf(AlertEffect.SHOW_FAULT_SCREEN), state.onCameraLost(100_000L))
        assertEquals(listOf(AlertEffect.SEND_DISCONNECT_ALERT), state.onTick(131_000L))
    }
}
