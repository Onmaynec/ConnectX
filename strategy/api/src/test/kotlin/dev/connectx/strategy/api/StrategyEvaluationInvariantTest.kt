package dev.connectx.strategy.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StrategyEvaluationInvariantTest {
    private val strategyId = StrategyId("tls-clienthello-split-v1")

    @Test
    fun evaluatorFailureReasonCountsCannotBeMutated() {
        val report = StrategyHealthEvaluator().evaluate(
            strategyId = strategyId,
            baselineSamples = successes(10L),
            strategySamples = listOf(
                StrategyHealthSample.Failure(StrategySampleFailure.TIMEOUT),
            ),
            recoverySamples = successes(10L),
        )

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            val mutable =
                report.strategy.failureReasons as MutableMap<StrategySampleFailure, Int>
            mutable[StrategySampleFailure.TIMEOUT] = 99
        }
        assertEquals(1, report.strategy.failureReasons[StrategySampleFailure.TIMEOUT])
    }

    @Test
    fun reportRejectsDecisionReasonMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            StrategyEvaluationReport(
                strategyId = strategyId,
                baseline = successfulSummary(StrategyHealthPhase.BASELINE),
                strategy = successfulSummary(StrategyHealthPhase.STRATEGY),
                recovery = successfulSummary(StrategyHealthPhase.RECOVERY),
                decision = StrategyEvaluationDecision.INCONCLUSIVE,
                reason = StrategyEvaluationReason.BASELINE_FAILURE_BUDGET_EXCEEDED,
                latencyDeltaMillis = null,
                allowedStrategyLatencyMillis = null,
            )
        }
    }

    @Test
    fun keepReportRequiresLatencyEvidence() {
        assertThrows(IllegalArgumentException::class.java) {
            StrategyEvaluationReport(
                strategyId = strategyId,
                baseline = successfulSummary(StrategyHealthPhase.BASELINE),
                strategy = successfulSummary(StrategyHealthPhase.STRATEGY),
                recovery = successfulSummary(StrategyHealthPhase.RECOVERY),
                decision = StrategyEvaluationDecision.KEEP_FOR_LAB_SESSION,
                reason = StrategyEvaluationReason.PASSED_WITHIN_LATENCY_BUDGET,
                latencyDeltaMillis = null,
                allowedStrategyLatencyMillis = null,
            )
        }
    }

    @Test
    fun nonLatencyReportRejectsLatencyEvidence() {
        assertThrows(IllegalArgumentException::class.java) {
            StrategyEvaluationReport(
                strategyId = strategyId,
                baseline = successfulSummary(StrategyHealthPhase.BASELINE),
                strategy = successfulSummary(StrategyHealthPhase.STRATEGY),
                recovery = successfulSummary(StrategyHealthPhase.RECOVERY),
                decision = StrategyEvaluationDecision.REJECT_BASELINE_UNHEALTHY,
                reason = StrategyEvaluationReason.BASELINE_FAILURE_BUDGET_EXCEEDED,
                latencyDeltaMillis = 1L,
                allowedStrategyLatencyMillis = 11L,
            )
        }
    }

    private fun successes(vararg latencies: Long): List<StrategyHealthSample> =
        latencies.map(StrategyHealthSample::Success)

    private fun successfulSummary(phase: StrategyHealthPhase) = StrategyPhaseSummary(
        phase = phase,
        successes = 1,
        failures = 0,
        medianLatencyMillis = 10L,
        failureReasons = emptyMap(),
    )
}
