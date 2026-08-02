package dev.connectx.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateReducerTest {
    @Test
    fun `permission request preserves requested native mode`() {
        val starting = ConnectionStateReducer.reduce(
            current = ConnectionUiState(),
            event = ConnectionEvent.StartRequested(EngineMode.NATIVE_SELF_TEST),
        )
        val result = ConnectionStateReducer.reduce(
            current = starting,
            event = ConnectionEvent.PermissionRequired,
        )

        assertEquals(ConnectionState.PERMISSION_REQUIRED, result.state)
        assertEquals(EngineMode.NATIVE_SELF_TEST, result.mode)
        assertNull(result.errorMessage)
    }

    @Test
    fun `foundation tunnel starts without marking native bridge running`() {
        val result = ConnectionStateReducer.reduce(
            current = ConnectionUiState(state = ConnectionState.STARTING),
            event = ConnectionEvent.TunnelStarted(EngineMode.FOUNDATION),
        )

        assertEquals(ConnectionState.LOCAL_TUN_ACTIVE, result.state)
        assertEquals(EngineMode.FOUNDATION, result.mode)
        assertFalse(result.diagnostics.running)
    }

    @Test
    fun `native tunnel start stores version abi and running state`() {
        val result = ConnectionStateReducer.reduce(
            current = ConnectionUiState(
                state = ConnectionState.STARTING,
                mode = EngineMode.NATIVE_SELF_TEST,
            ),
            event = ConnectionEvent.TunnelStarted(
                mode = EngineMode.NATIVE_SELF_TEST,
                nativeVersion = "connectx-go-bridge/test",
                abi = "arm64-v8a",
            ),
        )

        assertEquals(ConnectionState.LOCAL_TUN_ACTIVE, result.state)
        assertEquals(EngineMode.NATIVE_SELF_TEST, result.mode)
        assertTrue(result.diagnostics.running)
        assertEquals("connectx-go-bridge/test", result.diagnostics.version)
        assertEquals("arm64-v8a", result.diagnostics.abi)
    }

    @Test
    fun `native stop returns to foundation mode and keeps diagnostic result`() {
        val result = ConnectionStateReducer.reduce(
            current = ConnectionUiState(
                state = ConnectionState.STOPPING,
                mode = EngineMode.NATIVE_SELF_TEST,
                diagnostics = NativeBridgeDiagnostics(running = true),
            ),
            event = ConnectionEvent.TunnelStopped,
        )

        assertEquals(ConnectionState.OFF, result.state)
        assertEquals(EngineMode.FOUNDATION, result.mode)
        assertFalse(result.diagnostics.running)
        assertEquals(
            "Native self-test остановлен без перехвата обычного трафика",
            result.diagnostics.lastResult,
        )
    }

    @Test
    fun `failure stores readable error and stops native running flag`() {
        val result = ConnectionStateReducer.reduce(
            current = ConnectionUiState(
                state = ConnectionState.STARTING,
                mode = EngineMode.NATIVE_SELF_TEST,
                diagnostics = NativeBridgeDiagnostics(running = true),
            ),
            event = ConnectionEvent.Failed("JNI self-check failed"),
        )

        assertEquals(ConnectionState.ERROR, result.state)
        assertEquals("JNI self-check failed", result.errorMessage)
        assertFalse(result.diagnostics.running)
        assertEquals("JNI self-check failed", result.diagnostics.lastResult)
    }
}
