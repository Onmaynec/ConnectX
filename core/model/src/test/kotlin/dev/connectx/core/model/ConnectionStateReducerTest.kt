package dev.connectx.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionStateReducerTest {
    @Test
    fun `permission request moves state to permission required`() {
        val result = ConnectionStateReducer.reduce(
            current = ConnectionUiState(),
            event = ConnectionEvent.PermissionRequired,
        )

        assertEquals(ConnectionState.PERMISSION_REQUIRED, result.state)
        assertNull(result.errorMessage)
    }

    @Test
    fun `tunnel started moves state to local active`() {
        val result = ConnectionStateReducer.reduce(
            current = ConnectionUiState(ConnectionState.STARTING),
            event = ConnectionEvent.TunnelStarted,
        )

        assertEquals(ConnectionState.LOCAL_TUN_ACTIVE, result.state)
    }

    @Test
    fun `failure stores readable error`() {
        val result = ConnectionStateReducer.reduce(
            current = ConnectionUiState(ConnectionState.STARTING),
            event = ConnectionEvent.Failed("Не удалось создать локальный TUN"),
        )

        assertEquals(ConnectionState.ERROR, result.state)
        assertEquals("Не удалось создать локальный TUN", result.errorMessage)
    }

    @Test
    fun `stop event returns state to off`() {
        val result = ConnectionStateReducer.reduce(
            current = ConnectionUiState(ConnectionState.STOPPING),
            event = ConnectionEvent.TunnelStopped,
        )

        assertEquals(ConnectionState.OFF, result.state)
    }
}
