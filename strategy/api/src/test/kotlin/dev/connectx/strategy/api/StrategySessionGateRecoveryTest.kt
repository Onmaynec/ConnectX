package dev.connectx.strategy.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class StrategySessionGateRecoveryTest {
    @Test
    fun explicitRestartRecoversStaleEvaluatingState() {
        val stale = StrategySessionGate(
            state = StrategySessionGateState.EVALUATING,
            lastDecision = StrategyEvaluationDecision.INCONCLUSIVE,
        )

        val restarted = stale.begin(nowElapsedMillis = 10_000L)

        assertEquals(StrategySessionGateState.EVALUATING, restarted.state)
        assertNull(restarted.cooldownUntilElapsedMillis)
        assertNull(restarted.lastDecision)
    }

    @Test
    fun cooldownStillCannotBeBypassedByExplicitRestart() {
        val coolingDown = StrategySessionGate(
            state = StrategySessionGateState.COOLDOWN,
            cooldownUntilElapsedMillis = 20_000L,
            lastDecision = StrategyEvaluationDecision.ROLLBACK_CONFIRMED,
        )

        assertThrows(IllegalStateException::class.java) {
            coolingDown.begin(nowElapsedMillis = 19_999L)
        }
        assertEquals(
            StrategySessionGateState.EVALUATING,
            coolingDown.begin(nowElapsedMillis = 20_000L).state,
        )
    }

    @Test
    fun disabledGateStillCannotRecover() {
        val disabled = StrategySessionGate(
            state = StrategySessionGateState.DISABLED,
        )

        assertThrows(IllegalStateException::class.java) {
            disabled.begin(nowElapsedMillis = Long.MAX_VALUE)
        }
    }
}
