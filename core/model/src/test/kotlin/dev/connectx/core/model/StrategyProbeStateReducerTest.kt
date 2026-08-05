package dev.connectx.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyProbeStateReducerTest {
    @Test
    fun startMarksOnlyStrategyProbeRunning() {
        val state = ConnectionStateReducer.reduce(
            ConnectionUiState(),
            ConnectionEvent.StartRequested(EngineMode.NATIVE_TLS_SPLIT_PROBE),
        )

        assertEquals(ConnectionState.STARTING, state.state)
        assertEquals(EngineMode.NATIVE_TLS_SPLIT_PROBE, state.mode)
        assertTrue(state.strategyProbe.running)
        assertFalse(state.probe.running)
        assertFalse(state.udpProbe.running)
        assertFalse(state.dnsProbe.running)
    }

    @Test
    fun completionStoresTypedStrategyMetricsAndReturnsOff() {
        val running = ConnectionStateReducer.reduce(
            ConnectionUiState(),
            ConnectionEvent.StartRequested(EngineMode.NATIVE_TLS_SPLIT_PROBE),
        )
        val completed = ConnectionStateReducer.reduce(
            running,
            ConnectionEvent.StrategyProbeCompleted(
                strategyId = "tls-clienthello-split-v1",
                segments = 2,
                splitOffset = 43,
                latencyMillis = 12,
                uploadedBytes = 44,
                downloadedBytes = 44,
                relayConnections = 1,
                nativeVersion = "connectx-go-bridge/0.2.0-alpha.6",
                abi = "x86_64",
            ),
        )

        assertEquals(ConnectionState.OFF, completed.state)
        assertEquals(EngineMode.FOUNDATION, completed.mode)
        assertFalse(completed.strategyProbe.running)
        assertEquals(true, completed.strategyProbe.lastSuccess)
        assertEquals("tls-clienthello-split-v1", completed.strategyProbe.strategyId)
        assertEquals(2, completed.strategyProbe.segments)
        assertEquals(43, completed.strategyProbe.splitOffset)
        assertEquals(12L, completed.strategyProbe.latencyMillis)
        assertEquals(44L, completed.strategyProbe.uploadedBytes)
        assertEquals(44L, completed.strategyProbe.downloadedBytes)
        assertEquals(1L, completed.strategyProbe.relayConnections)
        assertNull(completed.strategyProbe.error)
    }

    @Test
    fun failureIsScopedToStrategyProbe() {
        val running = ConnectionStateReducer.reduce(
            ConnectionUiState(),
            ConnectionEvent.StartRequested(EngineMode.NATIVE_TLS_SPLIT_PROBE),
        )
        val failed = ConnectionStateReducer.reduce(
            running,
            ConnectionEvent.Failed("lab failure"),
        )

        assertEquals(ConnectionState.ERROR, failed.state)
        assertFalse(failed.strategyProbe.running)
        assertEquals(false, failed.strategyProbe.lastSuccess)
        assertEquals("lab failure", failed.strategyProbe.error)
        assertNull(failed.probe.error)
        assertNull(failed.udpProbe.error)
        assertNull(failed.dnsProbe.error)
    }
}
