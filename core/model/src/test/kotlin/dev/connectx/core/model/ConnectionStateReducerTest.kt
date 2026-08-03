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
    fun `permission request preserves requested TCP probe mode`() {
        val starting = ConnectionStateReducer.reduce(
            current = ConnectionUiState(),
            event = ConnectionEvent.StartRequested(EngineMode.NATIVE_TCP_PROBE),
        )
        val result = ConnectionStateReducer.reduce(
            current = starting,
            event = ConnectionEvent.PermissionRequired,
        )

        assertEquals(ConnectionState.PERMISSION_REQUIRED, result.state)
        assertEquals(EngineMode.NATIVE_TCP_PROBE, result.mode)
        assertTrue(result.probe.running)
        assertFalse(result.udpProbe.running)
    }

    @Test
    fun `permission request preserves requested UDP probe mode`() {
        val starting = ConnectionStateReducer.reduce(
            current = ConnectionUiState(),
            event = ConnectionEvent.StartRequested(EngineMode.NATIVE_UDP_PROBE),
        )
        val result = ConnectionStateReducer.reduce(
            current = starting,
            event = ConnectionEvent.PermissionRequired,
        )

        assertEquals(ConnectionState.PERMISSION_REQUIRED, result.state)
        assertEquals(EngineMode.NATIVE_UDP_PROBE, result.mode)
        assertFalse(result.probe.running)
        assertTrue(result.udpProbe.running)
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
    fun `TCP probe completion stores measured path and returns to off`() {
        val result = ConnectionStateReducer.reduce(
            current = ConnectionUiState(
                state = ConnectionState.LOCAL_TUN_ACTIVE,
                mode = EngineMode.NATIVE_TCP_PROBE,
                diagnostics = NativeBridgeDiagnostics(running = true),
                probe = TcpProbeDiagnostics(running = true),
            ),
            event = ConnectionEvent.ProbeCompleted(
                latencyMillis = 37,
                uploadedBytes = 64,
                downloadedBytes = 64,
                relayConnections = 1,
                nativeVersion = "connectx-go-bridge/0.2.0-alpha.5",
                abi = "x86_64",
            ),
        )

        assertEquals(ConnectionState.OFF, result.state)
        assertEquals(EngineMode.FOUNDATION, result.mode)
        assertFalse(result.diagnostics.running)
        assertTrue(result.probe.lastSuccess == true)
        assertEquals(37L, result.probe.latencyMillis)
        assertEquals(64L, result.probe.uploadedBytes)
        assertEquals(64L, result.probe.downloadedBytes)
        assertEquals(1L, result.probe.relayConnections)
        assertNull(result.errorMessage)
    }

    @Test
    fun `UDP probe completion stores datagram metrics and returns to off`() {
        val result = ConnectionStateReducer.reduce(
            current = ConnectionUiState(
                state = ConnectionState.LOCAL_TUN_ACTIVE,
                mode = EngineMode.NATIVE_UDP_PROBE,
                diagnostics = NativeBridgeDiagnostics(running = true),
                udpProbe = UdpProbeDiagnostics(running = true),
            ),
            event = ConnectionEvent.UdpProbeCompleted(
                latencyMillis = 29,
                uploadedBytes = 64,
                downloadedBytes = 64,
                relayAssociations = 1,
                datagrams = 1,
                nativeVersion = "connectx-go-bridge/0.2.0-alpha.5",
                abi = "x86_64",
            ),
        )

        assertEquals(ConnectionState.OFF, result.state)
        assertEquals(EngineMode.FOUNDATION, result.mode)
        assertFalse(result.diagnostics.running)
        assertTrue(result.udpProbe.lastSuccess == true)
        assertEquals(29L, result.udpProbe.latencyMillis)
        assertEquals(64L, result.udpProbe.uploadedBytes)
        assertEquals(64L, result.udpProbe.downloadedBytes)
        assertEquals(1L, result.udpProbe.relayAssociations)
        assertEquals(1L, result.udpProbe.datagrams)
        assertNull(result.errorMessage)
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
    fun `TCP probe failure stores readable error and stops probe flag`() {
        val result = ConnectionStateReducer.reduce(
            current = ConnectionUiState(
                state = ConnectionState.LOCAL_TUN_ACTIVE,
                mode = EngineMode.NATIVE_TCP_PROBE,
                diagnostics = NativeBridgeDiagnostics(running = true),
                probe = TcpProbeDiagnostics(running = true),
            ),
            event = ConnectionEvent.Failed("Echo nonce mismatch"),
        )

        assertEquals(ConnectionState.ERROR, result.state)
        assertEquals("Echo nonce mismatch", result.errorMessage)
        assertFalse(result.diagnostics.running)
        assertFalse(result.probe.running)
        assertEquals(false, result.probe.lastSuccess)
        assertEquals("Echo nonce mismatch", result.probe.error)
    }

    @Test
    fun `UDP probe failure stores readable error and stops UDP flag`() {
        val result = ConnectionStateReducer.reduce(
            current = ConnectionUiState(
                state = ConnectionState.LOCAL_TUN_ACTIVE,
                mode = EngineMode.NATIVE_UDP_PROBE,
                diagnostics = NativeBridgeDiagnostics(running = true),
                udpProbe = UdpProbeDiagnostics(running = true),
            ),
            event = ConnectionEvent.Failed("UDP nonce mismatch"),
        )

        assertEquals(ConnectionState.ERROR, result.state)
        assertEquals("UDP nonce mismatch", result.errorMessage)
        assertFalse(result.diagnostics.running)
        assertFalse(result.udpProbe.running)
        assertEquals(false, result.udpProbe.lastSuccess)
        assertEquals("UDP nonce mismatch", result.udpProbe.error)
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
