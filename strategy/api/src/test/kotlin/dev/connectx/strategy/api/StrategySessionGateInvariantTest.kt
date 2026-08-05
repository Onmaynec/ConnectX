package dev.connectx.strategy.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class StrategySessionGateInvariantTest {
    private val strategyId = StrategyId("tls-clienthello-split-v1")

    @Test
    fun publicEntryPointCreatesOnlyInitialReadyState() {
        val gate = StrategySessionGate()

        assertEquals(StrategySessionGateState.READY, gate.state)
        assertNull(gate.cooldownUntilElapsedMillis)
        assertNull(gate.lastDecision)
    }

    @Test
    fun evaluatingStateCannotCarryAPreviousDecision() {
        assertThrows(IllegalArgumentException::class.java) {
            StrategySessionGate(
                state = StrategySessionGateState.EVALUATING,
                lastDecision = StrategyEvaluationDecision.INCONCLUSIVE,
            )
        }
    }

    @Test
    fun approvedStateRequiresExactKeepDecision() {
        assertThrows(IllegalArgumentException::class.java) {
            StrategySessionGate(state = StrategySessionGateState.LAB_APPROVED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrategySessionGate(
                state = StrategySessionGateState.LAB_APPROVED,
                lastDecision = StrategyEvaluationDecision.ROLLBACK_CONFIRMED,
            )
        }
    }

    @Test
    fun cooldownRequiresANonKeepDecision() {
        assertThrows(IllegalArgumentException::class.java) {
            StrategySessionGate(
                state = StrategySessionGateState.COOLDOWN,
                cooldownUntilElapsedMillis = 10_000L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrategySessionGate(
                state = StrategySessionGateState.COOLDOWN,
                cooldownUntilElapsedMillis = 10_000L,
                lastDecision = StrategyEvaluationDecision.KEEP_FOR_LAB_SESSION,
            )
        }
    }

    @Test
    fun resettingApprovedSessionClearsThePreviousDecision() {
        val keepReport = StrategyHealthEvaluator().evaluate(
            strategyId = strategyId,
            baselineSamples = successes(10L),
            strategySamples = successes(10L),
            recoverySamples = successes(10L),
        )
        val approved = StrategySessionGate()
            .begin(nowElapsedMillis = 0L)
            .complete(
                report = keepReport,
                nowElapsedMillis = 1L,
            )

        val reset = approved.resetApprovedSession()

        assertEquals(StrategySessionGateState.READY, reset.state)
        assertNull(reset.cooldownUntilElapsedMillis)
        assertNull(reset.lastDecision)
    }

    private fun successes(vararg latencies: Long): List<StrategyHealthSample> =
        latencies.map(StrategyHealthSample::Success)
}
