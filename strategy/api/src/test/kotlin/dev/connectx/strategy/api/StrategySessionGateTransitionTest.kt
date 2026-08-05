package dev.connectx.strategy.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class StrategySessionGateTransitionTest {
    private val strategyId = StrategyId("tls-clienthello-split-v1")

    @Test
    fun cooldownExpiryClearsThePreviousDecision() {
        val policy = StrategyEvaluationPolicy(cooldownMillis = 5_000L)
        val rollback = StrategyHealthEvaluator().evaluate(
            strategyId = strategyId,
            baselineSamples = successes(10L),
            strategySamples = listOf(
                StrategyHealthSample.Failure(StrategySampleFailure.TIMEOUT),
            ),
            recoverySamples = successes(10L),
        )
        val coolingDown = StrategySessionGate()
            .begin(nowElapsedMillis = 1_000L)
            .complete(
                report = rollback,
                nowElapsedMillis = 2_000L,
                policy = policy,
            )

        val ready = coolingDown.refresh(nowElapsedMillis = 7_000L)

        assertEquals(StrategySessionGateState.READY, ready.state)
        assertNull(ready.cooldownUntilElapsedMillis)
        assertNull(ready.lastDecision)
    }

    @Test
    fun disablingAnApprovedSessionClearsTheKeepDecision() {
        val keep = StrategyHealthEvaluator().evaluate(
            strategyId = strategyId,
            baselineSamples = successes(10L),
            strategySamples = successes(10L),
            recoverySamples = successes(10L),
        )
        val approved = StrategySessionGate()
            .begin(nowElapsedMillis = 0L)
            .complete(
                report = keep,
                nowElapsedMillis = 1L,
            )

        val disabled = approved.disable()

        assertEquals(StrategySessionGateState.DISABLED, disabled.state)
        assertNull(disabled.cooldownUntilElapsedMillis)
        assertNull(disabled.lastDecision)
    }

    @Test
    fun terminalStatesRejectInjectedDecisions() {
        assertThrows(IllegalArgumentException::class.java) {
            StrategySessionGate(
                state = StrategySessionGateState.READY,
                lastDecision = StrategyEvaluationDecision.INCONCLUSIVE,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrategySessionGate(
                state = StrategySessionGateState.DISABLED,
                lastDecision = StrategyEvaluationDecision.ROLLBACK_CONFIRMED,
            )
        }
    }

    private fun successes(vararg latencies: Long): List<StrategyHealthSample> =
        latencies.map(StrategyHealthSample::Success)
}
