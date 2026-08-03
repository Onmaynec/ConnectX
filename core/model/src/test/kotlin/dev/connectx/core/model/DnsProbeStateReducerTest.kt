package dev.connectx.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsProbeStateReducerTest {
    @Test
    fun `DNS permission flow preserves mode and running flag`() {
        val starting = ConnectionStateReducer.reduce(
            current = ConnectionUiState(),
            event = ConnectionEvent.StartRequested(EngineMode.NATIVE_DNS_PROBE),
        )
        val result = ConnectionStateReducer.reduce(
            current = starting,
            event = ConnectionEvent.PermissionRequired,
        )

        assertEquals(ConnectionState.PERMISSION_REQUIRED, result.state)
        assertEquals(EngineMode.NATIVE_DNS_PROBE, result.mode)
        assertTrue(result.dnsProbe.running)
        assertFalse(result.probe.running)
        assertFalse(result.udpProbe.running)
    }

    @Test
    fun `DNS completion stores strict answer and transport metrics`() {
        val result = ConnectionStateReducer.reduce(
            current = ConnectionUiState(
                state = ConnectionState.LOCAL_TUN_ACTIVE,
                mode = EngineMode.NATIVE_DNS_PROBE,
                diagnostics = NativeBridgeDiagnostics(running = true),
                dnsProbe = DnsProbeDiagnostics(running = true),
            ),
            event = ConnectionEvent.DnsProbeCompleted(
                latencyMillis = 31,
                uploadedBytes = 34,
                downloadedBytes = 50,
                relayAssociations = 1,
                datagrams = 1,
                queries = 1,
                responses = 1,
                answer = "192.0.2.42",
                nativeVersion = "connectx-go-bridge/0.2.0-alpha.5",
                abi = "x86_64",
            ),
        )

        assertEquals(ConnectionState.OFF, result.state)
        assertEquals(EngineMode.FOUNDATION, result.mode)
        assertFalse(result.diagnostics.running)
        assertTrue(result.dnsProbe.lastSuccess == true)
        assertEquals(31L, result.dnsProbe.latencyMillis)
        assertEquals(34L, result.dnsProbe.uploadedBytes)
        assertEquals(50L, result.dnsProbe.downloadedBytes)
        assertEquals(1L, result.dnsProbe.relayAssociations)
        assertEquals(1L, result.dnsProbe.datagrams)
        assertEquals(1L, result.dnsProbe.queries)
        assertEquals(1L, result.dnsProbe.responses)
        assertEquals("192.0.2.42", result.dnsProbe.answer)
        assertNull(result.errorMessage)
    }

    @Test
    fun `DNS failure is isolated from TCP and UDP diagnostics`() {
        val result = ConnectionStateReducer.reduce(
            current = ConnectionUiState(
                state = ConnectionState.LOCAL_TUN_ACTIVE,
                mode = EngineMode.NATIVE_DNS_PROBE,
                diagnostics = NativeBridgeDiagnostics(running = true),
                dnsProbe = DnsProbeDiagnostics(running = true),
            ),
            event = ConnectionEvent.Failed("DNS transaction ID mismatch"),
        )

        assertEquals(ConnectionState.ERROR, result.state)
        assertFalse(result.dnsProbe.running)
        assertEquals(false, result.dnsProbe.lastSuccess)
        assertEquals("DNS transaction ID mismatch", result.dnsProbe.error)
        assertNull(result.probe.lastSuccess)
        assertNull(result.udpProbe.lastSuccess)
    }
}
