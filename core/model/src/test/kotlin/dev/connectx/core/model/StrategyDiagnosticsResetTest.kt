package dev.connectx.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StrategyDiagnosticsResetTest {
    @Test
    fun newEvaluationClearsPreviousVerdictBeforeRunning() {
        val previous = completedEvaluationState()

        val starting = ConnectionStateReducer.reduce(
            previous,
            ConnectionEvent.StartRequested(EngineMode.NATIVE_STRATEGY_EVALUATION),
        )

        assertEquals(ConnectionState.STARTING, starting.state)
        assertEquals(EngineMode.NATIVE_STRATEGY_EVALUATION, starting.mode)
        assertEquals(true, starting.strategyProbe.running)
        assertNull(starting.strategyProbe.lastSuccess)
        assertNull(starting.strategyProbe.evaluationDecision)
        assertNull(starting.strategyProbe.evaluationReason)
        assertNull(starting.strategyProbe.baselineLatencyMillis)
        assertNull(starting.strategyProbe.strategyLatencyMillis)
        assertNull(starting.strategyProbe.recoveryLatencyMillis)
        assertNull(starting.strategyProbe.error)
    }

    @Test
    fun evaluationFailureDoesNotRetainPreviousSuccessfulEvidence() {
        val running = ConnectionStateReducer.reduce(
            completedEvaluationState(),
            ConnectionEvent.StartRequested(EngineMode.NATIVE_STRATEGY_EVALUATION),
        )

        val failed = ConnectionStateReducer.reduce(
            running,
            ConnectionEvent.Failed("native setup failed"),
        )

        assertEquals(ConnectionState.ERROR, failed.state)
        assertFalse(failed.strategyProbe.running)
        assertEquals(false, failed.strategyProbe.lastSuccess)
        assertEquals("native setup failed", failed.strategyProbe.error)
        assertNull(failed.strategyProbe.strategyId)
        assertNull(failed.strategyProbe.evaluationDecision)
        assertNull(failed.strategyProbe.gateState)
        assertNull(failed.strategyProbe.uploadedBytes)
    }

    @Test
    fun unrelatedProbeStartKeepsLastStrategyResultButClearsItsError() {
        val current = completedEvaluationState().copy(
            strategyProbe = completedEvaluationState().strategyProbe.copy(
                error = "old transient error",
            ),
        )

        val starting = ConnectionStateReducer.reduce(
            current,
            ConnectionEvent.StartRequested(EngineMode.NATIVE_TCP_PROBE),
        )

        assertFalse(starting.strategyProbe.running)
        assertEquals("KEEP_FOR_LAB_SESSION", starting.strategyProbe.evaluationDecision)
        assertNull(starting.strategyProbe.error)
    }

    private fun completedEvaluationState(): ConnectionUiState = ConnectionUiState(
        strategyProbe = StrategyProbeDiagnostics(
            running = false,
            lastSuccess = true,
            strategyId = "tls-clienthello-split-v1",
            segments = 2,
            splitOffset = 43,
            uploadedBytes = 150L,
            downloadedBytes = 150L,
            relayConnections = 3L,
            evaluationDecision = "KEEP_FOR_LAB_SESSION",
            evaluationReason = "PASSED_WITHIN_LATENCY_BUDGET",
            baselineLatencyMillis = 10L,
            strategyLatencyMillis = 12L,
            recoveryLatencyMillis = 9L,
            latencyDeltaMillis = 2L,
            allowedStrategyLatencyMillis = 110L,
            gateState = "LAB_APPROVED",
        ),
    )
}
